# FlexSchedule 灵动调度

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3-brightgreen.svg)](https://spring.io/projects/spring-boot)

基于 Spring Boot 的轻量化动态调度线程池，支持运行时动态添加、移除、替换定时任务。

[English](README.md) | [中文](README.zh-CN.md)

---

## 特性

- **动态任务管理** — 运行时添加、取消、替换、暂停、恢复任务
- **3 种调度模式** — Cron、FixedDelay、FixedRate
- **时区支持** — Cron 任务可指定时区（`addCronTask(name, cron, ZoneId, runnable)`）
- **一次性延迟任务** — 延迟执行一次后自动移除
- **失败重试** — 固定间隔或指数退避，可配置最大延迟
- **执行超时** — 自动中断超时任务
- **任务依赖链** — 使用 `TaskChain` 构建器顺序执行任务
- **执行历史** — 按任务记录的内存环形缓冲区
- **集群感知调度** — `DistributedLock` 接口支持多节点协调（ShedLock/Redis/ZooKeeper）
- **线程安全** — ConcurrentHashMap + 原子操作
- **自动配置** — 引入 Starter 即可零配置使用
- **优雅停机** — 可配置的等待超时
- **生命周期监听** — 执行前 / 执行后 / 异常回调
- **Micrometer 指标** — 执行计数器、计时器、活跃数（可选）
- **健康检查** — 向 `/actuator/health` 报告调度器状态（可选）
- **Actuator 端点** — REST CRUD 管理 `/actuator/flexschedule`，含访问控制（可选）
- **反射调度** — `BeanMethodRunnable` 按名称调用 Bean 方法，支持 AOP 代理

## 环境要求

| 依赖 | 版本 |
|------|------|
| JDK | 17+ |
| Spring Boot | 3.x |

## 安装

### Maven

```xml
<depkendency>
    <groupId>io.github.wb04307201</groupId>
    <artifactId>flex-schedule-spring-boot-starter</artifactId>
    <version>${latest.version}</version>
</depkendency>
```

### Gradle

```groovy
implementation 'io.github.wb04307201:flex-schedule-spring-boot-starter:${latest.version}'
```

## 快速开始

```java
@Service
public class DemoService {

    @Autowired
    private FlexScheduledTaskService taskService;

    public void demo() {
        // Cron 任务
        taskService.add("cronJob", "0/5 * * * * ?", () -> doWork());

        // 固定延迟（秒）
        taskService.addFixedDelayTask("delayJob", 10, 0, () -> doWork());

        // 固定频率（Duration 精度）
        taskService.addFixedRateTask("rateJob", Duration.ofSeconds(5), Duration.ZERO, () -> doWork());

        // 一次性延迟任务（执行后自动移除）
        taskService.schedule("notify", Duration.ofMinutes(5), () -> sendEmail());

        // 替换已有任务或新增
        taskService.replaceCronTask("cronJob", "0/10 * * * * ?", () -> doNewWork());

        // 取消
        taskService.cancel("cronJob");

        // 查询
        taskService.exists("rateJob");           // true
        taskService.listTasks();                 // List<TaskInfo>
        taskService.getTaskDetail("rateJob");    // Optional<TaskDetail>
    }
}
```

## 配置

```yaml
flex:
  schedule:
    enabled: true                       # 启用自动配置（默认 true）
    pool-size: 16                       # 线程池大小
    thread-name-prefix: "FlexScheduleThreadPool-" # 线程名前缀
    remove-on-cancel: true              # 取消后从线程池移除
    await-termination-seconds: 30       # 优雅停机超时（秒）
```

## API 参考

### 核心操作

| 方法 | 说明 |
|------|------|
| `add(name, cron, runnable [, retryPolicy])` | 注册 Cron 任务 |
| `add(name, cron, ZoneId, runnable)` | 注册带时区的 Cron 任务 |
| `add(name, cron, timeout, runnable)` | 注册带执行超时的 Cron 任务 |
| `addFixedDelayTask(name, interval, initialDelay, runnable [, retryPolicy])` | 注册固定延迟任务 |
| `addFixedRateTask(name, interval, initialDelay, runnable [, retryPolicy])` | 注册固定频率任务 |
| `schedule(name, delay, runnable)` | 调度一次性延迟任务 |
| `cancel(name)` | 取消任务 |
| `pause(name)` | 暂停任务（跳过执行直到恢复） |
| `resume(name)` | 恢复暂停的任务 |
| `isPaused(name)` | 检查任务是否暂停 |
| `exists(name)` | 判断任务是否存在 |
| `listTasks()` | 列出所有任务（`List<TaskInfo>`） |
| `getTaskDetail(name)` | 获取任务详情（`Optional<TaskDetail>`） |
| `replaceCronTask / replaceFixedDelayTask / replaceFixedRateTask(...)` | 替换或新增任务 |
| `addListener / removeListener(listener)` | 管理生命周期监听器 |
| `getExecutionHistory(name, limit)` | 获取任务执行历史 |
| `getAllExecutionHistory(limit)` | 获取所有执行历史 |
| `setDistributedLock(lock)` | 设置集群分布式锁 |

- `interval` / `initialDelay` / `delay` 接受 `long`（秒）或 `Duration`（任意精度）
- `retryPolicy` 可选，详见[重试策略](#重试策略)

### TaskInfo 与 TaskDetail

```java
record TaskInfo(String taskName, String taskType, String schedule) {}
// taskType: CRON | FIXED_DELAY | FIXED_RATE | ONE_SHOT

record TaskDetail(String taskName, String taskType, String schedule,
                  boolean oneShot, RetryPolicy retryPolicy, Instant createdAt,
                  boolean paused) {}
```

## 高级用法

### 任务执行监听器

```java
@Component
public class MyListener implements TaskExecutionListener {
    @Override public void beforeExecution(String name) { /* ... */ }
    @Override public void afterExecution(String name) { /* ... */ }
    @Override public void onError(String name, Throwable error) { /* ... */ }
}

// 注册
taskService.addListener(myListener);
taskService.removeListener(myListener);
```

所有方法均有默认空实现。监听器异常会被捕获并记录日志。

### 重试策略

```java
// 固定：最多重试 3 次，每次间隔 5 秒
RetryPolicy fixed = RetryPolicy.fixed(3, Duration.ofSeconds(5));
taskService.add("task", "0 * * * * ?", runnable, fixed);

// 指数退避：延迟逐次翻倍（1s → 2s → 4s）
RetryPolicy exponential = RetryPolicy.exponential(3, Duration.ofSeconds(1));
taskService.addFixedDelayTask("task", Duration.ofMinutes(1), Duration.ZERO, runnable, exponential);
```

| 退避策略 | 第 N 次重试延迟 |
|---------|---------------|
| `FIXED` | `delay` |
| `EXPONENTIAL` | `delay × 2^(N-1)` |

每次重试触发完整的监听器生命周期。全部重试失败后异常正常抛出。

### Bean 方法反射调度

```java
// 按名称调用 Bean 方法
Runnable runnable = new BeanMethodRunnable("myService", "doWork");
taskService.add("task", "0 0/1 * * * ?", runnable);

// 带参数
Runnable withParams = new BeanMethodRunnable("myService", "process", List.of("hello", 42));
taskService.add("task", "0 0/5 * * * ?", withParams);
```

支持基本类型与包装类型自动匹配（`Integer` → `int`），同时支持 AOP 代理 Bean（CGLIB/JDK 动态代理）。

### 暂停 / 恢复

```java
// 暂停运行中的任务（跳过执行但保持注册状态）
taskService.pause("myTask");

// 检查是否暂停
taskService.isPaused("myTask");  // true

// 恢复
taskService.resume("myTask");
```

### 执行超时

```java
// 任务超过 30 秒将被中断
taskService.add("longTask", "0 * * * * ?", Duration.ofSeconds(30), () -> {
    // 如果执行超过 30 秒，抛出 ExecutionTimeoutException
    doLongRunningWork();
});

// 同样适用于固定延迟和固定频率
taskService.addFixedRateTask("rateTask", Duration.ofMinutes(1), Duration.ZERO,
    Duration.ofSeconds(45), () -> doWork());
```

### 任务依赖链

```java
// 顺序执行任务
TaskChain.create(registrar)
    .then("extract", () -> extractData())
    .then("transform", Duration.ofSeconds(30), () -> transformData())  // 带超时
    .then("load", () -> loadData())
    .execute();  // 返回 CompletableFuture<Void>

// 延迟调度一个任务链
TaskChain.create(registrar)
    .then("step1", () -> step1())
    .then("step2", () -> step2())
    .schedule("nightlyPipeline", Duration.ofHours(6));
```

任一步骤失败时，链停止并传播错误。

### 执行历史

```java
// 启用内存执行历史（每个任务 100 条记录）
registrar.setExecutionHistory(new InMemoryExecutionHistory(100));

// 查询历史
List<ExecutionRecord> records = taskService.getExecutionHistory("myTask", 10);
// 每条记录包含：taskName, taskType, startTime, duration, success, error

// 获取所有任务的历史
List<ExecutionRecord> all = taskService.getAllExecutionHistory(50);

// 清除历史
taskService.clearExecutionHistory("myTask");  // 指定任务
taskService.clearExecutionHistory(null);       // 全部
```

### 集群感知调度（分布式锁）

在多实例部署中，使用 `DistributedLock` 确保只有一个节点执行任务：

```java
// 实现接口（或使用提供的适配器）
@Bean
public DistributedLock myDistributedLock(RedisTemplate<String, String> redis) {
    return new DistributedLock() {
        @Override
        public boolean tryLock(String taskName, Duration lockDuration) {
            return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent("lock:" + taskName, "1", lockDuration));
        }
        @Override
        public void unlock(String taskName) {
            redis.delete("lock:" + taskName);
        }
    };
}

// 设置到注册器
taskService.setDistributedLock(myDistributedLock);
```

锁在每次执行前获取，执行后释放（即使失败也会释放）。如果另一个节点持有锁，则跳过执行。

### Micrometer 指标

当 `spring-boot-starter-actuator` 在 classpath 时自动启用：

| 指标 | 类型 | Tags |
|------|------|------|
| `flex.schedule.task.executions` | Counter | `taskName`, `taskType`, `result` |
| `flex.schedule.task.duration` | Timer | `taskName`, `taskType` |
| `flex.schedule.active.tasks` | Gauge | — |

可对接 Prometheus、Grafana 等 Micrometer 兼容系统。Micrometer 不在 classpath 时零开销。

### Actuator 端点

当 `spring-boot-starter-actuator` + `spring-boot-starter-web` 在 classpath 时启用。

```yaml
management:
  endpoints:
    web:
      exposure:
        include: flexschedule
```

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/actuator/flexschedule` | 列出所有任务 |
| `GET` | `/actuator/flexschedule/{name}` | 获取任务详情 |
| `POST` | `/actuator/flexschedule` | 添加任务 |
| `DELETE` | `/actuator/flexschedule/{name}` | 取消任务 |

**POST 请求体：**

```json
{
  "taskName": "cleanupJob",
  "taskType": "CRON",
  "cron": "0 0 * * * *",
  "beanName": "cleanupService",
  "methodName": "run",
  "methodParams": []
}
```

`FIXED_DELAY` / `FIXED_RATE` 使用 `intervalSeconds`（+ 可选 `initialDelaySeconds`）替代 `cron`。
REST 端点仅支持 `BeanMethodRunnable` 方式。

#### 端点安全

**写操作（POST/DELETE）默认关闭**，以防止未授权的任务操作：

```yaml
flex:
  schedule:
    endpoint:
      write-enabled: true              # 启用 POST/DELETE（默认 false）
      allowed-beans:                   # 可选 Bean 白名单（空 = 不限制）
        - cleanupService
        - reportService
```

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `write-enabled` | `false` | 设为 `true` 才能执行 POST/DELETE 操作 |
| `allowed-beans` | `[]` | 只有列表中的 Bean 可以通过 REST 调度。空 = 不限制 |

如需自定义授权逻辑，实现 `EndpointAccessControl` 接口并注册为 Spring Bean。

> **安全警告**：在没有适当安全措施（Spring Security、网络限制）的情况下启用写操作，可能会使应用暴露于远程代码执行风险（通过反射调用 Bean 方法）。

## 异常

| 异常 | 触发场景 |
|------|---------|
| `TaskAlreadyExistsException` | 添加重复名称的任务 |
| `BeanMethodRunnableException` | 反射调用 Bean 方法失败 |
| `IllegalArgumentException` | 参数校验（null/空） |

均继承自 `FlexScheduleException`。

## 模块

| 模块 | artifactId | 说明 |
|------|------------|------|
| `flex-schedule/` | `flex-schedule` | 核心库 |
| `flex-schedule-spring-boot-autoconfigure/` | `flex-schedule-spring-boot-autoconfigure` | 自动配置、指标、端点 |
| `flex-schedule-spring-boot-starter/` | `flex-schedule-spring-boot-starter` | Starter（仅依赖） |
| `flex-schedule-test/` | `flex-schedule-test` | 测试（198 个用例） |

## 构建

```bash
./mvnw clean install
```

## FAQ

**任务名必须唯一吗？**
是的。使用 `replace*()` 原子替换，或先 `cancel()` 再 `add()`。

**时间单位？**
`long` 参数 = 秒。`Duration` 参数 = 任意精度。

**取消不存在的任务？**
记录 WARN 日志，不抛异常。

**关闭时如何处理？**
取消所有任务 → `shutdown()` → 等待 `await-termination-seconds`（默认 30s）→ 超时则 `shutdownNow()`。

**如何禁用？**
设置 `flex.schedule.enabled=false`。

## 许可证

[Apache License 2.0](LICENSE)
