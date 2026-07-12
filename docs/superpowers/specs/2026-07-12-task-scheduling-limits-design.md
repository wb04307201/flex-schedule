# 任务上下限保护 — 设计文档

> 日期：2026-07-12
> 状态：已通过用户审阅，等待进入实施

## 1. 背景与目标

`flex-schedule` 当前允许任意间隔 / 无限期存活的调度任务，缺乏全局防护：

1. **缺少触发频率下限**：调用方可能写出 `interval=PT1S` 的 FIXED_RATE 任务，把线程池打满
2. **缺少生命周期上限**：忘记清理的临时任务会无限期占用资源

本设计引入**配置驱动的上下限保护**，让运维可以在不修改业务代码的前提下统一约束。

## 2. 设计决策（已与用户确认）

| # | 决策 | 选择 |
|---|------|------|
| 1 | "最长持续时间"的含义 | **任务最长生命周期**（注册后 N 时间自动 cancel） |
| 2 | 违规处理模式 | **三档可配**：`STRICT` / `WARN` / `OFF`，默认 `STRICT` |
| 3 | 最短间隔作用范围 | **仅 `FIXED_DELAY` / `FIXED_RATE` / `ONE_SHOT`**（CRON 豁免） |
| 4 | 最长生命周期作用范围 | **`FIXED_DELAY` / `FIXED_RATE` / `CRON`**（ONE_SHOT 豁免） |
| 5 | 生命周期跟踪方式 | **懒检查**（每次 fire 时检查，无后台线程） |
| 6 | `replaceXxxTask` 行为 | **重置 createdAt**（重新计时） |
| 7 | 默认值 | **`min-interval` / `max-lifetime` 默认 null（无限制）** |

## 3. 配置模型

`FlexScheduleProperties` 新增 `Limits` 嵌套类：

```yaml
flex:
  schedule:
    limits:
      min-interval: PT10M      # Duration；可选；空 = 无下限
      max-lifetime: P7D        # Duration；可选；空 = 无上限
      mode: strict             # strict | warn | off；默认 strict
```

```java
@Data
public static class Limits {
    private Duration minInterval;
    private Duration maxLifetime;
    private Mode mode = Mode.STRICT;

    public enum Mode { STRICT, WARN, OFF }
}
```

不可变快照 `TaskLimits`（record）：

```java
public record TaskLimits(Duration minInterval, Duration maxLifetime, Mode mode) {
    public static final TaskLimits DISABLED = new TaskLimits(null, null, Mode.OFF);

    public boolean hasMinInterval() { return minInterval != null; }
    public boolean hasMaxLifetime() { return maxLifetime != null; }
    public boolean isEnforcing()   { return mode != Mode.OFF; }
}
```

### Mode 语义

| Mode | 触发条件 | 行为 |
|------|---------|------|
| `STRICT` | `min-interval` 或 `max-lifetime` 任一非空 | 违规抛 `TaskLimitExceededException`；超期静默 cancel + 日志 |
| `WARN`   | 同上 | 违规记录 WARN 日志后放行；超期同 STRICT（日志 + cancel） |
| `OFF`    | — | 完全跳过所有检查 |

> `max-lifetime` 即便在 `WARN` 模式也会真去 cancel（不 cancel 就没意义）；`OFF` 模式完全跳过。

## 4. 校验入口矩阵

| 方法 | 校验 `interval ≥ min-interval` | 校验 `delay ≥ min-interval` | 校验 `max-lifetime` |
|------|:--:|:--:|:--:|
| `addCronTask(...)` | — | — | —（懒检查） |
| `addFixedDelayTask(...)` | ✅ | — | —（懒检查） |
| `addFixedRateTask(...)` | ✅ | — | —（懒检查） |
| `schedule(...)` (ONE_SHOT) | — | ✅ | —（一次性无生命周期） |
| `replaceCronTask(...)` | — | — | —（懒检查） |
| `replaceFixedDelayTask(...)` | ✅ | — | —（懒检查） |
| `replaceFixedRateTask(...)` | ✅ | — | —（懒检查） |

> **`initialDelay` 不校验**：它只是首跑延迟，不影响触发频率。

## 5. `LimitsChecker` 设计

```java
final class LimitsChecker {
    private final TaskLimits limits;

    void assertInterval(String taskName, Duration interval) {
        if (!limits.isEnforcing() || !limits.hasMinInterval()) return;
        if (interval.compareTo(limits.minInterval()) >= 0) return;

        switch (limits.mode()) {
            case STRICT -> throw new TaskLimitExceededException(
                "Task [%s] interval %s is below minimum %s"
                    .formatted(taskName, interval, limits.minInterval()));
            case WARN -> log.warn(
                "Task [{}] interval {} is below minimum {} — allowed due to mode=warn",
                taskName, interval, limits.minInterval());
            case OFF -> { /* unreachable */ }
        }
    }

    /** 懒检查：true 表示任务已过期需 cancel */
    boolean isExpired(String taskName, Instant createdAt) {
        if (!limits.isEnforcing() || !limits.hasMaxLifetime()) return false;
        if (Duration.between(createdAt, Instant.now()).compareTo(limits.maxLifetime()) < 0) {
            return false;
        }
        log.info("Task [{}] exceeded max lifetime {}, auto-cancelling",
                 taskName, limits.maxLifetime());
        return true;
    }
}
```

## 6. `instrument()` 改动

过期检查放在**最前面**（早于 paused / lock / 监听器）：

```java
private Runnable instrument(String taskName, Runnable delegate) {
    return () -> {
        // 1. 过期检查（NEW）
        ScheduledTaskEntry entry = taskMap.get(taskName);
        if (entry != null && limitsChecker.isExpired(taskName, entry.createdAt())) {
            cancel(taskName);
            return;
        }

        // 2. 暂停检查
        if (pausedTasks.contains(taskName)) { ... return; }

        // 3. 锁检查（原有）
        // ... listener fires
        // ... try/catch/finally
    };
}
```

## 7. 暂停中到期的处理

被暂停的任务永远不会 fire → 懒检查永远不触发 → 任务会无限期停留在 `taskMap`。

**解决方案**：`resume()` 内补一次过期检查。

```java
public void resume(String taskName) {
    Assert.hasText(taskName, "taskName must not be empty");
    ScheduledTaskEntry entry = taskMap.get(taskName);
    if (entry == null) { log.warn(...); return; }
    if (limitsChecker.isExpired(taskName, entry.createdAt())) {
        cancel(taskName);
        log.info("Task [{}] exceeded max lifetime during pause, cancelled instead of resumed", taskName);
        return;
    }
    if (pausedTasks.remove(taskName)) {
        log.info("Resumed task [{}]", taskName);
    } else {
        log.warn("Task [{}] was not paused, resume skipped", taskName);
    }
}
```

## 8. 持久化与 replace 行为

- **`restoreTasks()`**：从 `TaskDefinition.createdAt` 读出原始时间传入 `ScheduledTaskEntry`，跨重启累计寿命
- **`replaceXxxTask(...)`**：构造新的 `ScheduledTaskEntry(... Instant.now())` —— **重置计时**

## 9. 边界情况汇总

| 场景 | 行为 |
|------|------|
| 过期的那一刻刚好 fire | 取消并 skip 本次；后续不再 fire |
| 暂停中到期 | 保留在 taskMap；下次 `resume()` 时取消 |
| 重启后第一次 fire 跨过 lifetime | 取消 |
| replace 一个过期的任务 | 重置 createdAt，相当于"续命" |
| `mode=OFF` 且未设任何 limit | `isExpired()` 直接返回 false，零开销 |
| ONE_SHOT 任务不参与生命周期 | 不在 instrument 检查路径 |

## 10. 异常类型

新增 `TaskLimitExceededException`，继承 `FlexScheduleException`：

```java
public class TaskLimitExceededException extends FlexScheduleException {
    public TaskLimitExceededException(String message) { super(message); }
}
```

仅在 `STRICT` 模式抛出。

## 11. 构造函数兼容性

```java
// 旧：完全兼容，内部传 DISABLED
public FlexScheduledTaskRegistrar(ThreadPoolTaskScheduler scheduler, long awaitTerminationSeconds) {
    this(scheduler, awaitTerminationSeconds, TaskLimits.DISABLED);
}

// 新：生产用
public FlexScheduledTaskRegistrar(ThreadPoolTaskScheduler scheduler,
                                   long awaitTerminationSeconds,
                                   TaskLimits limits) {
    super();
    this.setScheduler(scheduler);
    this.awaitTerminationSeconds = awaitTerminationSeconds;
    this.limitsChecker = new LimitsChecker(limits != null ? limits : TaskLimits.DISABLED);
}
```

`FlexScheduleAutoConfiguration` 改为使用 3 参构造器，从 properties 装配 `TaskLimits`。

## 12. 文件改动清单

| 文件 | 改动 |
|------|------|
| `flex-schedule/.../core/TaskLimits.java` | **新增** record |
| `flex-schedule/.../core/LimitsChecker.java` | **新增** 工具类 |
| `flex-schedule/.../core/FlexScheduledTaskRegistrar.java` | 注入 `LimitsChecker`；4 处 `add` + 3 处 `replace` + `schedule` + `instrument` + `resume` 增加校验/检查 |
| `flex-schedule/.../exception/TaskLimitExceededException.java` | **新增** |
| `flex-schedule-spring-boot-autoconfigure/.../FlexScheduleProperties.java` | 新增 `Limits` 嵌套类 + `Mode` 枚举 |
| `flex-schedule-spring-boot-autoconfigure/.../FlexScheduleAutoConfiguration.java` | 新增 `TaskLimits` bean；改用 3 参构造器 |
| `flex-schedule-test/.../FlexScheduledTaskLimitsTest.java` | **新增** ~13 个测试用例 |
| `README.md` / `README.zh-CN.md` | 新增「任务上下限保护」章节 |
| `CLAUDE.md` | Configuration Properties 表 +3 行；Important Design Decisions +1 条 |

## 13. 测试计划

新增 `FlexScheduledTaskLimitsTest`，覆盖：

| 用例 | 覆盖点 |
|------|--------|
| `minInterval_strictReject` | FIXED_DELAY < min → 抛 |
| `minInterval_warnAllow` | WARN 模式不抛 |
| `minInterval_offBypass` | OFF 模式无校验 |
| `minInterval_nullConfig` | minInterval=null 不校验 |
| `minInterval_initialDelayExempt` | initialDelay < min 不抛 |
| `minInterval_oneShot` | ONE_SHOT delay < min → 抛 |
| `minInterval_cronExempt` | CRON 不受 minInterval 影响 |
| `maxLifetime_lazyCancel` | FIXED_DELAY 跨过 lifetime 在 fire 时被取消 |
| `maxLifetime_cronLazyCancel` | cron 到 lifetime 后 fire 时取消 |
| `maxLifetime_pausedResumeCancel` | 暂停中到期，resume() 触发取消 |
| `maxLifetime_restoredKeepsCreatedAt` | 重启后 first fire 按原始 createdAt 判定 |
| `replaceResetsCreatedAt` | replace* 后计时归零 |
| `replace_strictRejectsViolation` | replace 一个不合规参数 → 抛 |

现有 9 个测试文件**无需修改**（继续走 2 参构造器，等价 DISABLED）。

## 14. 文档更新

- `README.zh-CN.md` / `README.md`：新增「任务上下限保护」章节
- `CLAUDE.md`：
  - Configuration Properties 表新增 3 行（`flex.schedule.limits.min-interval` / `max-lifetime` / `mode`）
  - Important Design Decisions 追加第 8 条：懒过期检查 + replace 重置语义

## 15. 非目标（明确不做）

- **per-task 例外**：本期不提供 `@TaskLimits` 注解或 per-task 配置；如需例外，整体切到 `mode=off` 或调整全局值即可
- **CRON 最小间隔校验**：CRON 表达式难以计算最小间隔，本期豁免
- **后台扫描器**：用户已选懒检查，不引入额外线程
- **生命周期上限应用于 ONE_SHOT**：ONE_SHOT 本身就是一次性的，无生命周期概念