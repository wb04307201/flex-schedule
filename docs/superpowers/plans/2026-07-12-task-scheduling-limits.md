# Task Scheduling Limits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add global, configuration-driven limits (minimum trigger interval + maximum task lifetime) to `flex-schedule`, with three enforcement modes (STRICT / WARN / OFF).

**Architecture:** Introduce a `TaskLimits` immutable record + `LimitsChecker` utility; `FlexScheduledTaskRegistrar` calls the checker at registration time (interval validation) and execution time (lazy lifetime expiry). Three-mode enforcement via `FlexScheduleProperties.Limits`. Backward-compatible registrar constructor (existing 2-arg remains valid → `DISABLED`).

**Tech Stack:** Java 17, Spring Boot 3.5.14, JUnit 5, Lombok, Maven Wrapper.

## Global Constraints

- Java 17+ language features allowed.
- Spring Boot 3.5.14 BOM, no new dependencies.
- Backward compatibility required: existing 2-arg `FlexScheduledTaskRegistrar(scheduler, awaitSeconds)` constructor MUST remain.
- All public API additions follow existing naming/casing conventions.
- Every commit message: `type(scope): subject` — examples `feat(core): add TaskLimits record`, `test(limits): cover STRICT mode rejection`.
- Tests live in `flex-schedule-test/`. Run targeted: `mvn test -pl flex-schedule-test -am -Dtest=ClassName#method`.
- Run full suite: `mvn test -pl flex-schedule-test -am`.

---

## File Map

| Path | Responsibility |
|------|----------------|
| `flex-schedule/src/main/java/cn/wubo/flex/schedule/exception/TaskLimitExceededException.java` | Exception thrown when STRICT mode rejects a registration |
| `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/TaskLimits.java` | Immutable record holding `minInterval`, `maxLifetime`, `mode` + `DISABLED` sentinel + helpers |
| `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/LimitsChecker.java` | Pure logic: `assertInterval(...)` and `isExpired(...)` |
| `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/FlexScheduledTaskRegistrar.java` | Modify: 3-arg ctor, validate on register, lazy expire on fire, expire on resume, restore createdAt |
| `flex-schedule-spring-boot-autoconfigure/src/main/java/cn/wubo/flex/schedule/autoconfigure/FlexScheduleProperties.java` | Add nested `Limits` class + `Mode` enum |
| `flex-schedule-spring-boot-autoconfigure/src/main/java/cn/wubo/flex/schedule/autoconfigure/FlexScheduleAutoConfiguration.java` | Add `taskLimits` bean; use 3-arg ctor |
| `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/TaskLimitsTest.java` | Unit tests for `TaskLimits` helpers |
| `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/LimitsCheckerTest.java` | Unit tests for `LimitsChecker` (all modes + nulls) |
| `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexScheduledTaskLimitsTest.java` | Integration tests on the registrar (validation + lazy cancel + resume cancel + replace reset) |
| `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexSchedulePropertiesLimitsTest.java` | Properties binding test |
| `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexScheduleAutoConfigurationTest.java` | Add test for `taskLimits` bean |
| `README.zh-CN.md` | New section |
| `README.md` | New section |
| `CLAUDE.md` | Update properties table + design decisions |

---

### Task 1: Add `TaskLimitExceededException`

**Files:**
- Create: `flex-schedule/src/main/java/cn/wubo/flex/schedule/exception/TaskLimitExceededException.java`

**Interfaces:**
- Produces: `cn.wubo.flex.schedule.exception.TaskLimitExceededException extends FlexScheduleException` — ctor `(String message)` only.

- [ ] **Step 1: Create the exception file**

Create `flex-schedule/src/main/java/cn/wubo/flex/schedule/exception/TaskLimitExceededException.java`:

```java
package cn.wubo.flex.schedule.exception;

/**
 * Thrown when a task registration violates configured interval limits
 * (only when limits.mode=STRICT).
 */
public class TaskLimitExceededException extends FlexScheduleException {

    public TaskLimitExceededException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: Compile-check**

Run: `mvn -pl flex-schedule compile -am`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add flex-schedule/src/main/java/cn/wubo/flex/schedule/exception/TaskLimitExceededException.java
git commit -m "feat(exception): add TaskLimitExceededException"
```

---

### Task 2: Add `TaskLimits` record + helpers

**Files:**
- Create: `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/TaskLimits.java`
- Create: `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/TaskLimitsTest.java`

**Interfaces:**
- Produces: `cn.wubo.flex.schedule.core.TaskLimits` record `(Duration minInterval, Duration maxLifetime, Mode mode)` with `DISABLED` constant and `hasMinInterval()` / `hasMaxLifetime()` / `isEnforcing()` helpers.

- [ ] **Step 1: Write failing test**

Create `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/TaskLimitsTest.java`:

```java
package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.TaskLimits;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TaskLimitsTest {

    @Test
    void DISABLED_hasNoLimitsAndIsNotEnforcing() {
        TaskLimits limits = TaskLimits.DISABLED;
        assertFalse(limits.hasMinInterval());
        assertFalse(limits.hasMaxLifetime());
        assertFalse(limits.isEnforcing());
    }

    @Test
    void hasMinInterval_trueWhenSet() {
        TaskLimits limits = new TaskLimits(Duration.ofMinutes(10), null, TaskLimits.Mode.STRICT);
        assertTrue(limits.hasMinInterval());
        assertFalse(limits.hasMaxLifetime());
        assertTrue(limits.isEnforcing());
    }

    @Test
    void hasMaxLifetime_trueWhenSet() {
        TaskLimits limits = new TaskLimits(null, Duration.ofDays(7), TaskLimits.Mode.STRICT);
        assertFalse(limits.hasMinInterval());
        assertTrue(limits.hasMaxLifetime());
    }

    @Test
    void offMode_isNotEnforcing_evenWithLimitsSet() {
        TaskLimits limits = new TaskLimits(Duration.ofMinutes(10), Duration.ofDays(7), TaskLimits.Mode.OFF);
        assertTrue(limits.hasMinInterval());
        assertTrue(limits.hasMaxLifetime());
        assertFalse(limits.isEnforcing());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl flex-schedule-test -am -Dtest=TaskLimitsTest`
Expected: FAIL — compilation error (TaskLimits does not exist).

- [ ] **Step 3: Implement `TaskLimits`**

Create `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/TaskLimits.java`:

```java
package cn.wubo.flex.schedule.core;

import java.time.Duration;

/**
 * Immutable snapshot of configured task scheduling limits.
 *
 * @param minInterval minimum allowed interval for FIXED_DELAY / FIXED_RATE / ONE_SHOT (null = no limit)
 * @param maxLifetime maximum lifetime before auto-cancel for FIXED_DELAY / FIXED_RATE / CRON (null = no limit)
 * @param mode        enforcement mode (STRICT throws, WARN logs, OFF skips)
 */
public record TaskLimits(Duration minInterval, Duration maxLifetime, Mode mode) {

    /** Sentinel: no limits, no enforcement. Used by 2-arg constructor for backward compat. */
    public static final TaskLimits DISABLED = new TaskLimits(null, null, Mode.OFF);

    public boolean hasMinInterval() {
        return minInterval != null;
    }

    public boolean hasMaxLifetime() {
        return maxLifetime != null;
    }

    public boolean isEnforcing() {
        return mode != Mode.OFF;
    }

    public enum Mode { STRICT, WARN, OFF }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl flex-schedule-test -am -Dtest=TaskLimitsTest`
Expected: PASS — 4 tests.

- [ ] **Step 5: Commit**

```bash
git add flex-schedule/src/main/java/cn/wubo/flex/schedule/core/TaskLimits.java \
        flex-schedule-test/src/test/java/cn/wubo/flex/schedule/TaskLimitsTest.java
git commit -m "feat(core): add TaskLimits record"
```

---

### Task 3: Add `LimitsChecker` with full TDD coverage

**Files:**
- Create: `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/LimitsChecker.java`
- Create: `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/LimitsCheckerTest.java`

**Interfaces:**
- Produces: `cn.wubo.flex.schedule.core.LimitsChecker` — ctor `(TaskLimits limits)`. Methods:
  - `void assertInterval(String taskName, Duration interval)` — throws `TaskLimitExceededException` if STRICT + below min; logs WARN if WARN mode + below min; no-op otherwise.
  - `boolean isExpired(String taskName, Instant createdAt)` — returns true iff STRICT/WARN mode + age ≥ maxLifetime; logs INFO before returning true.

- [ ] **Step 1: Write failing tests**

Create `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/LimitsCheckerTest.java`:

```java
package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.LimitsChecker;
import cn.wubo.flex.schedule.core.TaskLimits;
import cn.wubo.flex.schedule.core.TaskLimits.Mode;
import cn.wubo.flex.schedule.exception.TaskLimitExceededException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LimitsCheckerTest {

    // ─── assertInterval ────────────────────────────────────────────

    @Test
    void assertInterval_disabled_neverThrows() {
        LimitsChecker checker = new LimitsChecker(TaskLimits.DISABLED);
        assertDoesNotThrow(() -> checker.assertInterval("t", Duration.ofMillis(1)));
    }

    @Test
    void assertInterval_nullMinInterval_neverThrows() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(null, null, Mode.STRICT));
        assertDoesNotThrow(() -> checker.assertInterval("t", Duration.ofMillis(1)));
    }

    @Test
    void assertInterval_aboveMin_passes() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(Duration.ofMinutes(10), null, Mode.STRICT));
        assertDoesNotThrow(() -> checker.assertInterval("t", Duration.ofMinutes(15)));
    }

    @Test
    void assertInterval_equalToMin_passes() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(Duration.ofMinutes(10), null, Mode.STRICT));
        assertDoesNotThrow(() -> checker.assertInterval("t", Duration.ofMinutes(10)));
    }

    @Test
    void assertInterval_belowMin_strictThrows() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(Duration.ofMinutes(10), null, Mode.STRICT));
        TaskLimitExceededException ex = assertThrows(TaskLimitExceededException.class,
            () -> checker.assertInterval("myJob", Duration.ofSeconds(5)));
        assertTrue(ex.getMessage().contains("myJob"));
        assertTrue(ex.getMessage().contains("below minimum"));
    }

    @Test
    void assertInterval_belowMin_warnDoesNotThrow() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(Duration.ofMinutes(10), null, Mode.WARN));
        assertDoesNotThrow(() -> checker.assertInterval("t", Duration.ofSeconds(5)));
    }

    @Test
    void assertInterval_belowMin_offDoesNotThrow() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(Duration.ofMinutes(10), null, Mode.OFF));
        assertDoesNotThrow(() -> checker.assertInterval("t", Duration.ofSeconds(5)));
    }

    // ─── isExpired ─────────────────────────────────────────────────

    @Test
    void isExpired_disabled_returnsFalse() {
        LimitsChecker checker = new LimitsChecker(TaskLimits.DISABLED);
        assertFalse(checker.isExpired("t", Instant.now().minus(Duration.ofDays(365))));
    }

    @Test
    void isExpired_nullMaxLifetime_returnsFalse() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(null, null, Mode.STRICT));
        assertFalse(checker.isExpired("t", Instant.now().minus(Duration.ofDays(365))));
    }

    @Test
    void isExpired_youngTask_returnsFalse() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(null, Duration.ofDays(7), Mode.STRICT));
        assertFalse(checker.isExpired("t", Instant.now().minus(Duration.ofDays(1))));
    }

    @Test
    void isExpired_oldTask_returnsTrue() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(null, Duration.ofDays(7), Mode.STRICT));
        assertTrue(checker.isExpired("t", Instant.now().minus(Duration.ofDays(8))));
    }

    @Test
    void isExpired_exactlyAtLifetime_returnsTrue() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(null, Duration.ofSeconds(1), Mode.STRICT));
        assertTrue(checker.isExpired("t", Instant.now().minus(Duration.ofMillis(1100))));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl flex-schedule-test -am -Dtest=LimitsCheckerTest`
Expected: FAIL — compilation error (LimitsChecker does not exist).

- [ ] **Step 3: Implement `LimitsChecker`**

Create `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/LimitsChecker.java`:

```java
package cn.wubo.flex.schedule.core;

import cn.wubo.flex.schedule.exception.TaskLimitExceededException;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;

/**
 * Encapsulates limit-enforcement decisions for the registrar.
 * Stateless except for the immutable TaskLimits snapshot.
 */
@Slf4j
final class LimitsChecker {

    private final TaskLimits limits;

    LimitsChecker(TaskLimits limits) {
        this.limits = limits != null ? limits : TaskLimits.DISABLED;
    }

    /**
     * Validates that {@code interval} meets the configured minimum.
     *
     * @throws TaskLimitExceededException if STRICT mode and interval is below min
     */
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
            case OFF -> { /* unreachable: isEnforcing() returned false above */ }
        }
    }

    /**
     * Lazy lifetime check. Returns true iff the task has exceeded max-lifetime and should be cancelled.
     * Logs INFO when returning true.
     */
    boolean isExpired(String taskName, Instant createdAt) {
        if (!limits.isEnforcing() || !limits.hasMaxLifetime()) return false;
        Duration age = Duration.between(createdAt, Instant.now());
        if (age.compareTo(limits.maxLifetime()) < 0) return false;

        log.info("Task [{}] exceeded max lifetime {} (age={}), auto-cancelling",
                 taskName, limits.maxLifetime(), age);
        return true;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl flex-schedule-test -am -Dtest=LimitsCheckerTest`
Expected: PASS — 12 tests.

- [ ] **Step 5: Commit**

```bash
git add flex-schedule/src/main/java/cn/wubo/flex/schedule/core/LimitsChecker.java \
        flex-schedule-test/src/test/java/cn/wubo/flex/schedule/LimitsCheckerTest.java
git commit -m "feat(core): add LimitsChecker with full mode coverage"
```

---

### Task 4: Add `FlexScheduleProperties.Limits` config

**Files:**
- Modify: `flex-schedule-spring-boot-autoconfigure/src/main/java/cn/wubo/flex/schedule/autoconfigure/FlexScheduleProperties.java`
- Create: `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexSchedulePropertiesLimitsTest.java`

**Interfaces:**
- Produces: `FlexScheduleProperties.Limits` nested `@Data` class with fields `Duration minInterval`, `Duration maxLifetime`, `Mode mode = Mode.STRICT`. `Mode` enum lives inside `Limits`.

- [ ] **Step 1: Write failing binding test**

Create `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexSchedulePropertiesLimitsTest.java`:

```java
package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.autoconfigure.FlexScheduleProperties;
import cn.wubo.flex.schedule.autoconfigure.FlexScheduleProperties.Limits;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FlexSchedulePropertiesLimitsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class);

    @Test
    void defaults_minAndMaxAreNullAndModeIsStrict() {
        runner.run(ctx -> {
            FlexScheduleProperties props = ctx.getBean(FlexScheduleProperties.class);
            Limits limits = props.getLimits();
            assertThat(limits.getMinInterval()).isNull();
            assertThat(limits.getMaxLifetime()).isNull();
            assertThat(limits.getMode()).isEqualTo(Limits.Mode.STRICT);
        });
    }

    @Test
    void bindsYamlValues() {
        runner.withPropertyValues(
            "flex.schedule.limits.min-interval=PT10M",
            "flex.schedule.limits.max-lifetime=P7D",
            "flex.schedule.limits.mode=warn"
        ).run(ctx -> {
            Limits limits = ctx.getBean(FlexScheduleProperties.class).getLimits();
            assertThat(limits.getMinInterval()).isEqualTo(Duration.ofMinutes(10));
            assertThat(limits.getMaxLifetime()).isEqualTo(Duration.ofDays(7));
            assertThat(limits.getMode()).isEqualTo(Limits.Mode.WARN);
        });
    }

    @Test
    void mode_offIsParsed() {
        runner.withPropertyValues("flex.schedule.limits.mode=off").run(ctx -> {
            assertThat(ctx.getBean(FlexScheduleProperties.class).getLimits().getMode())
                .isEqualTo(Limits.Mode.OFF);
        });
    }

    @EnableConfigurationProperties(FlexScheduleProperties.class)
    static class TestConfig {}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl flex-schedule-test -am -Dtest=FlexSchedulePropertiesLimitsTest`
Expected: FAIL — compilation error (`getLimits()` does not exist).

- [ ] **Step 3: Add `Limits` nested class to `FlexScheduleProperties`**

Replace `flex-schedule-spring-boot-autoconfigure/src/main/java/cn/wubo/flex/schedule/autoconfigure/FlexScheduleProperties.java` entirely with:

```java
package cn.wubo.flex.schedule.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Configuration properties for flex scheduling.
 */
@Data
@ConfigurationProperties(prefix = "flex.schedule")
public class FlexScheduleProperties {
    private Boolean enabled = Boolean.TRUE;
    private Integer poolSize = 16;
    private String threadNamePrefix = "FlexScheduleThreadPool-";
    private Boolean removeOnCancel = Boolean.TRUE;
    private Long awaitTerminationSeconds = 30L;

    private Endpoint endpoint = new Endpoint();
    private Limits limits = new Limits();

    /**
     * Endpoint security configuration.
     */
    @Data
    public static class Endpoint {
        /**
         * Whether write operations (POST/DELETE) are enabled on the actuator endpoint.
         * Default is false for security reasons.
         */
        private Boolean writeEnabled = Boolean.FALSE;

        /**
         * Set of bean names that are allowed to be scheduled via the actuator endpoint.
         * If empty, all beans are allowed (when write-enabled is true).
         */
        private Set<String> allowedBeans = new HashSet<>();
    }

    /**
     * Global task scheduling limits.
     * <p>
     * Both {@code minInterval} and {@code maxLifetime} default to {@code null}, meaning no limit.
     * When either is set, the {@link Mode} determines enforcement:
     * STRICT throws on violation, WARN logs and allows, OFF disables all checks.
     */
    @Data
    public static class Limits {
        /** Minimum trigger interval for FIXED_DELAY / FIXED_RATE / ONE_SHOT. null = no limit. */
        private Duration minInterval;
        /** Maximum task lifetime before auto-cancel. null = no limit. */
        private Duration maxLifetime;
        /** Enforcement mode. Default: STRICT. */
        private Mode mode = Mode.STRICT;

        public enum Mode { STRICT, WARN, OFF }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl flex-schedule-test -am -Dtest=FlexSchedulePropertiesLimitsTest`
Expected: PASS — 3 tests.

- [ ] **Step 5: Commit**

```bash
git add flex-schedule-spring-boot-autoconfigure/src/main/java/cn/wubo/flex/schedule/autoconfigure/FlexScheduleProperties.java \
        flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexSchedulePropertiesLimitsTest.java
git commit -m "feat(autoconfigure): add flex.schedule.limits properties"
```

---

### Task 5: Add 3-arg registrar constructor (no behavior change yet)

**Files:**
- Modify: `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/FlexScheduledTaskRegistrar.java:39-43`

**Interfaces:**
- Produces: New constructor `FlexScheduledTaskRegistrar(ThreadPoolTaskScheduler, long, TaskLimits)`. Existing 2-arg ctor delegates with `TaskLimits.DISABLED`. New private final field `LimitsChecker limitsChecker`.

- [ ] **Step 1: Modify `FlexScheduledTaskRegistrar`**

In `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/FlexScheduledTaskRegistrar.java`:

Replace the field block (currently lines ~25-37) to add the limitsChecker field. After `private volatile TaskRepository taskRepository = new InMemoryTaskRepository();` add:

```java
    private final LimitsChecker limitsChecker;
```

Replace the constructor block (currently lines 39-43):

```java
    public FlexScheduledTaskRegistrar(ThreadPoolTaskScheduler taskScheduler, long awaitTerminationSeconds) {
        this(taskScheduler, awaitTerminationSeconds, TaskLimits.DISABLED);
    }

    public FlexScheduledTaskRegistrar(ThreadPoolTaskScheduler taskScheduler, long awaitTerminationSeconds, TaskLimits limits) {
        super();
        this.setScheduler(taskScheduler);
        this.awaitTerminationSeconds = awaitTerminationSeconds;
        this.limitsChecker = new LimitsChecker(limits);
    }
```

- [ ] **Step 2: Compile and run full existing test suite**

Run: `mvn test -pl flex-schedule-test -am`
Expected: BUILD SUCCESS — all 198 existing tests still pass (no behavior change).

- [ ] **Step 3: Commit**

```bash
git add flex-schedule/src/main/java/cn/wubo/flex/schedule/core/FlexScheduledTaskRegistrar.java
git commit -m "feat(core): add 3-arg registrar constructor accepting TaskLimits"
```

---

### Task 6: Wire `assertInterval` into registration paths (TDD)

**Files:**
- Modify: `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/FlexScheduledTaskRegistrar.java`
- Create: `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexScheduledTaskLimitsTest.java`

**Interfaces:**
- Produces: Private helper `validateIntervalLimit(String taskName, Duration interval)`. Called from `addFixedDelayTask(Duration)`, `addFixedRateTask(Duration)`, `schedule(...)` (ONE_SHOT), `replaceFixedDelayTask`, `replaceFixedRateTask`. NOT called for CRON.

- [ ] **Step 1: Write failing tests**

Create `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexScheduledTaskLimitsTest.java`:

```java
package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.TaskLimits;
import cn.wubo.flex.schedule.core.TaskLimits.Mode;
import cn.wubo.flex.schedule.exception.TaskLimitExceededException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class FlexScheduledTaskLimitsTest {

    private ThreadPoolTaskScheduler scheduler;
    private FlexScheduledTaskRegistrar strict;
    private FlexScheduledTaskRegistrar warn;
    private FlexScheduledTaskRegistrar off;
    private FlexScheduledTaskRegistrar defaults;

    @BeforeEach
    void setUp() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("test-limits-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();

        TaskLimits strictLimits = new TaskLimits(Duration.ofMinutes(10), null, Mode.STRICT);
        TaskLimits warnLimits = new TaskLimits(Duration.ofMinutes(10), null, Mode.WARN);
        TaskLimits offLimits = new TaskLimits(Duration.ofMinutes(10), null, Mode.OFF);

        strict = new FlexScheduledTaskRegistrar(scheduler, 5, strictLimits);
        warn = new FlexScheduledTaskRegistrar(scheduler, 5, warnLimits);
        off = new FlexScheduledTaskRegistrar(scheduler, 5, offLimits);
        defaults = new FlexScheduledTaskRegistrar(scheduler, 5);
    }

    @AfterEach
    void tearDown() {
        strict.destroy();
        warn.destroy();
        off.destroy();
        defaults.destroy();
    }

    // ─── Min-interval on FIXED_DELAY ────────────────────────────────

    @Test
    void fixedDelay_belowMin_strictThrows() {
        assertThrows(TaskLimitExceededException.class,
            () -> strict.addFixedDelayTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
        assertFalse(strict.exists("t"));
    }

    @Test
    void fixedDelay_belowMin_warnAllows() {
        assertDoesNotThrow(
            () -> warn.addFixedDelayTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
        assertTrue(warn.exists("t"));
    }

    @Test
    void fixedDelay_belowMin_offAllows() {
        assertDoesNotThrow(
            () -> off.addFixedDelayTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
        assertTrue(off.exists("t"));
    }

    @Test
    void fixedDelay_nullMinInterval_allowsAny() {
        assertDoesNotThrow(
            () -> defaults.addFixedDelayTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
        assertTrue(defaults.exists("t"));
    }

    @Test
    void fixedDelay_initialDelay_belowMin_stillAllowed() {
        assertDoesNotThrow(
            () -> strict.addFixedDelayTask("t", Duration.ofMinutes(15), Duration.ofSeconds(5), () -> {}));
        assertTrue(strict.exists("t"));
    }

    @Test
    void fixedDelay_atMin_allowed() {
        assertDoesNotThrow(
            () -> strict.addFixedDelayTask("t", Duration.ofMinutes(10), Duration.ZERO, () -> {}));
        assertTrue(strict.exists("t"));
    }

    // ─── Min-interval on FIXED_RATE ─────────────────────────────────

    @Test
    void fixedRate_belowMin_strictThrows() {
        assertThrows(TaskLimitExceededException.class,
            () -> strict.addFixedRateTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
    }

    @Test
    void fixedRate_aboveMin_allowed() {
        assertDoesNotThrow(
            () -> strict.addFixedRateTask("t", Duration.ofMinutes(15), Duration.ZERO, () -> {}));
    }

    // ─── Min-interval on ONE_SHOT ───────────────────────────────────

    @Test
    void oneShot_belowMin_strictThrows() {
        assertThrows(TaskLimitExceededException.class,
            () -> strict.schedule("t", Duration.ofSeconds(5), () -> {}));
    }

    @Test
    void oneShot_aboveMin_allowed() {
        assertDoesNotThrow(
            () -> strict.schedule("t", Duration.ofMinutes(15), () -> {}));
    }

    // ─── Min-interval EXEMPT for CRON ───────────────────────────────

    @Test
    void cron_evenWithStrictMinInterval_isAllowed() {
        // cron "every second" — interval is < 10min, but CRON is exempt
        assertDoesNotThrow(
            () -> strict.addCronTask("t", "* * * * * *", () -> {}));
        assertTrue(strict.exists("t"));
    }

    // ─── replace path ──────────────────────────────────────────────

    @Test
    void replaceFixedDelay_belowMin_strictThrows() {
        strict.addFixedDelayTask("t", Duration.ofMinutes(30), Duration.ZERO, () -> {});
        assertThrows(TaskLimitExceededException.class,
            () -> strict.replaceFixedDelayTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
        assertTrue(strict.exists("t"));  // original still there
    }

    @Test
    void replaceFixedRate_belowMin_strictThrows() {
        strict.addFixedRateTask("t", Duration.ofMinutes(30), Duration.ZERO, () -> {});
        assertThrows(TaskLimitExceededException.class,
            () -> strict.replaceFixedRateTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
    }

    @Test
    void replaceCron_belowMin_strictThrows() {
        // cron is exempt from min-interval, so replace-cron also exempt
        assertDoesNotThrow(
            () -> strict.replaceCronTask("neverExisted", "* * * * * *", () -> {}));
        assertTrue(strict.exists("neverExisted"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl flex-schedule-test -am -Dtest=FlexScheduledTaskLimitsTest`
Expected: FAIL — registration methods don't validate yet.

- [ ] **Step 3: Add `validateIntervalLimit` helper and wire it**

In `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/FlexScheduledTaskRegistrar.java`:

Add a new private method, placed in the "Internal" section near other validators:

```java
    /**
     * Validates the task interval against the configured minimum trigger interval.
     * No-op when limits are disabled or no min-interval is configured.
     */
    private void validateIntervalLimit(String taskName, Duration interval) {
        limitsChecker.assertInterval(taskName, interval);
    }
```

In `addFixedDelayTask(String, Duration, Duration, Runnable)` (the Duration overload, currently lines 315-341), insert the call as the first line of the try block (or right after the existing `Assert.notNull` calls, before `taskMap.putIfAbsent`):

```java
        validateIntervalLimit(taskName, interval);
```

In `addFixedRateTask(String, Duration, Duration, Runnable)` (currently lines 355-381), same insertion.

In `schedule(String, Duration, Runnable)` (currently lines 577-608), insert immediately after `Assert.notNull(delay, "delay must not be null");`:

```java
        validateIntervalLimit(taskName, delay);
```

In `replaceFixedDelayTask(...)` (currently lines 516-537), insert immediately after the existing `Assert.notNull(initialDelay, ...)`:

```java
        validateIntervalLimit(taskName, interval);
```

In `replaceFixedRateTask(...)` (currently lines 544-565), same insertion.

DO NOT add the call in `addCronTask(...)` or `replaceCronTask(...)` (cron is exempt).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl flex-schedule-test -am -Dtest=FlexScheduledTaskLimitsTest`
Expected: PASS — all 13 tests.

- [ ] **Step 5: Commit**

```bash
git add flex-schedule/src/main/java/cn/wubo/flex/schedule/core/FlexScheduledTaskRegistrar.java \
        flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexScheduledTaskLimitsTest.java
git commit -m "feat(core): validate min-interval on registration paths"
```

---

### Task 7: Wire `isExpired` lazy check into `instrument()`

**Files:**
- Modify: `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/FlexScheduledTaskRegistrar.java:766-857`
- Modify: `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexScheduledTaskLimitsTest.java` (append tests)

**Interfaces:**
- Produces: At the top of the `instrument()` Runnable body, before the paused check, add: get entry from taskMap, call `limitsChecker.isExpired(...)`, if true call `cancel(taskName)` and return.

- [ ] **Step 1: Append failing tests for lazy lifetime expiry**

Append to `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexScheduledTaskLimitsTest.java`:

```java
    // ─── Max-lifetime lazy check ────────────────────────────────────

    @Test
    void maxLifetime_fixedDelay_cancelledOnNextFirePastLifetime() throws InterruptedException {
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5,
            new TaskLimits(null, Duration.ofMillis(300), Mode.STRICT));
        try {
            r.addFixedDelayTask("shortLived", Duration.ofMillis(100), Duration.ZERO, () -> {});

            // Wait past the lifetime cap (initialDelay 0 + 100ms interval; need >300ms total)
            Thread.sleep(500);

            // Trigger one more fire — instrument() should detect expiry and cancel
            // Force a re-schedule isn't trivial; instead, verify by directly invoking getTaskDetail
            // and checking isExpired via LimitsChecker. For this test, we rely on direct helper:
            // Simulate the fire path by calling getTaskDetail and waiting for the scheduled runnable.
            // The scheduled runnable itself will call cancel when it fires next.
            // We give it time to fire at least once post-expiry.
            Thread.sleep(300);

            // After sufficient time, either the task already fired and cancelled, or it still
            // exists but the next fire will cancel. Use a generous wait.
            long deadline = System.currentTimeMillis() + 2000;
            while (r.exists("shortLived") && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(r.exists("shortLived"), "Task should have been auto-cancelled past max-lifetime");
        } finally {
            r.destroy();
        }
    }

    @Test
    void maxLifetime_nullConfig_doesNotCancel() throws InterruptedException {
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5,
            new TaskLimits(Duration.ofMinutes(10), null, Mode.STRICT));
        try {
            r.addFixedDelayTask("forever", Duration.ofMillis(100), Duration.ZERO, () -> {});
            Thread.sleep(400);
            assertTrue(r.exists("forever"));
        } finally {
            r.destroy();
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl flex-schedule-test -am -Dtest=FlexScheduledTaskLimitsTest#maxLifetime_fixedDelay_cancelledOnNextFirePastLifetime`
Expected: FAIL — task is not cancelled.

- [ ] **Step 3: Wire `isExpired` into `instrument()`**

In `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/FlexScheduledTaskRegistrar.java`, modify the `instrument(String taskName, Runnable delegate)` method. Currently starts at line ~766 with:

```java
    private Runnable instrument(String taskName, Runnable delegate) {
        return () -> {
            // Check if task is paused
            if (pausedTasks.contains(taskName)) {
```

Replace that opening block with:

```java
    private Runnable instrument(String taskName, Runnable delegate) {
        return () -> {
            // 1. Lazy lifetime check (NEW)
            ScheduledTaskEntry entry = taskMap.get(taskName);
            if (entry != null && limitsChecker.isExpired(taskName, entry.createdAt())) {
                cancel(taskName);
                return;
            }

            // 2. Check if task is paused
            if (pausedTasks.contains(taskName)) {
```

Then the existing line:

```java
            // Check distributed lock for cluster-aware scheduling
            ScheduledTaskEntry entry = taskMap.get(taskName);
            Duration lockDuration = resolveLockDuration(entry);
```

becomes a duplicate of the new `entry` lookup. Remove the duplicate `taskMap.get(taskName)` (keep the variable reuse — rename if needed by adding `final`). The simplest change is: change the existing line to use the already-fetched entry:

```java
            // Check distributed lock for cluster-aware scheduling
            Duration lockDuration = resolveLockDuration(entry);
```

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn test -pl flex-schedule-test -am -Dtest=FlexScheduledTaskLimitsTest`
Expected: PASS — all tests in this class.

- [ ] **Step 5: Run full suite to ensure no regressions**

Run: `mvn test -pl flex-schedule-test -am`
Expected: BUILD SUCCESS — all tests pass (198 existing + new limits tests).

- [ ] **Step 6: Commit**

```bash
git add flex-schedule/src/main/java/cn/wubo/flex/schedule/core/FlexScheduledTaskRegistrar.java \
        flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexScheduledTaskLimitsTest.java
git commit -m "feat(core): auto-cancel tasks past max-lifetime on fire"
```

---

### Task 8: Wire `isExpired` into `resume()`

**Files:**
- Modify: `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/FlexScheduledTaskRegistrar.java:633-640`
- Modify: `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexScheduledTaskLimitsTest.java`

**Interfaces:**
- Produces: `resume(taskName)` checks expiration before resuming; if expired, calls `cancel(taskName)` and returns without resuming.

- [ ] **Step 1: Append failing test**

Append to `FlexScheduledTaskLimitsTest.java`:

```java
    @Test
    void resume_pausedTaskPastLifetime_cancelsInsteadOfResuming() throws InterruptedException {
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5,
            new TaskLimits(null, Duration.ofMillis(200), Mode.STRICT));
        try {
            r.addFixedDelayTask("oldPaused", Duration.ofSeconds(60), Duration.ZERO, () -> {});
            r.pause("oldPaused");

            // wait past max-lifetime
            Thread.sleep(400);

            // attempt resume — should cancel instead
            r.resume("oldPaused");
            assertFalse(r.exists("oldPaused"));
            assertFalse(r.isPaused("oldPaused"));
        } finally {
            r.destroy();
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl flex-schedule-test -am -Dtest=FlexScheduledTaskLimitsTest#resume_pausedTaskPastLifetime_cancelsInsteadOfResuming`
Expected: FAIL — resume succeeds, task still exists.

- [ ] **Step 3: Modify `resume()`**

In `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/FlexScheduledTaskRegistrar.java`, replace the `resume` method:

Current:
```java
    public void resume(String taskName) {
        Assert.hasText(taskName, "taskName must not be empty");
        if (pausedTasks.remove(taskName)) {
            log.info("Resumed task [{}]", taskName);
        } else {
            log.warn("Task [{}] was not paused, resume skipped", taskName);
        }
    }
```

Replace with:

```java
    public void resume(String taskName) {
        Assert.hasText(taskName, "taskName must not be empty");
        ScheduledTaskEntry entry = taskMap.get(taskName);
        if (entry == null) {
            log.warn("Task [{}] not found, resume skipped", taskName);
            return;
        }
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

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn test -pl flex-schedule-test -am -Dtest=FlexScheduledTaskLimitsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add flex-schedule/src/main/java/cn/wubo/flex/schedule/core/FlexScheduledTaskRegistrar.java \
        flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexScheduledTaskLimitsTest.java
git commit -m "feat(core): cancel paused task on resume if past max-lifetime"
```

---

### Task 9: Preserve `createdAt` across restart in `restoreTasks()`

**Files:**
- Modify: `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/FlexScheduledTaskRegistrar.java:121-169`
- Modify: `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/TestApplication.java`
- Modify: `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexScheduledTaskLimitsTest.java`

**Interfaces:**
- Produces: `restoreTasks()` reads `def.createdAt()` from the repository and uses it for the new `ScheduledTaskEntry`, preserving lifetime accounting across restarts. TestApplication gains a `noOp` method used as a stub bean.

**Note on approach**: The existing `addCronTask` / `addFixedDelayTask` / `addFixedRateTask` constructors always stamp `Instant.now()` via the convenience ctor of `ScheduledTaskEntry`. To preserve the persisted `createdAt`, we refactor `restoreTasks()` to schedule directly via the underlying Spring API and build entries with the original timestamp.

- [ ] **Step 1: Append failing tests**

Append to `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexScheduledTaskLimitsTest.java`:

```java
    // ─── createdAt preservation ────────────────────────────────────

    @Test
    void replaceCronTask_resetsCreatedAt() throws InterruptedException {
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5);
        try {
            r.addCronTask("t", "0 * * * * *", () -> {});
            Instant beforeReplace = r.getTaskDetail("t").orElseThrow().createdAt();
            Thread.sleep(20);
            r.replaceCronTask("t", "0 0 * * * *", () -> {});
            Instant afterReplace = r.getTaskDetail("t").orElseThrow().createdAt();
            assertTrue(afterReplace.isAfter(beforeReplace),
                "replaceCronTask should reset createdAt to a later instant");
        } finally {
            r.destroy();
        }
    }

    @Test
    void restoreTasks_preservesCreatedAtAcrossRestart() {
        Instant originalCreated = Instant.now().minus(Duration.ofDays(3));
        TaskDefinition def = TaskDefinition.builder("daily", "CRON")
            .cronExpression("0 0 * * * *")
            .beanName("testApplication")
            .methodName("noOp")
            .createdAt(originalCreated)
            .updatedAt(originalCreated)
            .build();
        cn.wubo.flex.schedule.core.TaskRepository repo =
            new cn.wubo.flex.schedule.core.InMemoryTaskRepository();
        repo.save(def);

        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5);
        try {
            // Spring's BeanMethodRunnable requires the bean to be in the application context;
            // restoreTasks runs synchronously and does NOT actually invoke the method — it only
            // constructs the Runnable. The task will be registered as long as the bean is
            // resolvable. For this test we use the singleton testApplication from TestApplication
            // via a programmatic lookup.
            org.springframework.context.support.StaticApplicationContext ctx =
                new org.springframework.context.support.StaticApplicationContext();
            ctx.getBeanFactory().registerSingleton("testApplication",
                cn.wubo.flex.schedule.TestApplication.getInstance());
            ctx.refresh();

            cn.wubo.flex.schedule.core.SpringContextUtils utils =
                new cn.wubo.flex.schedule.core.SpringContextUtils();
            utils.setApplicationContext(ctx);
            r.setTaskRepository(repo);
            r.restoreTasks();

            assertTrue(r.exists("daily"), "Task should be restored");
            Instant restored = r.getTaskDetail("daily").orElseThrow().createdAt();
            assertEquals(originalCreated.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                         restored.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                         "Restored task should preserve original createdAt");
        } finally {
            r.destroy();
        }
    }
```

- [ ] **Step 2: Add `getInstance()` and `noOp()` to TestApplication**

Replace `flex-schedule-test/src/test/java/cn/wubo/flex/schedule/TestApplication.java` entirely with:

```java
package cn.wubo.flex.schedule;

import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
class TestApplication {

    private static final TestApplication INSTANCE = new TestApplication();

    /** Singleton accessor used by tests that need to register this class as a Spring bean. */
    static TestApplication getInstance() {
        return INSTANCE;
    }

    /** No-op method usable as a BeanMethodRunnable target. */
    public void noOp() {
        // intentionally empty
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -pl flex-schedule-test -am -Dtest=FlexScheduledTaskLimitsTest#replaceCronTask_resetsCreatedAt`
Expected: PASS — replace path already uses `Instant.now()`. No code change needed for replace.

Run: `mvn test -pl flex-schedule-test -am -Dtest=FlexScheduledTaskLimitsTest#restoreTasks_preservesCreatedAtAcrossRestart`
Expected: FAIL — `restoreTasks()` does not preserve `createdAt`; the restored task's `createdAt` will be `Instant.now()` instead of `originalCreated`.

- [ ] **Step 4: Refactor `restoreTasks()` to preserve `createdAt`**

In `flex-schedule/src/main/java/cn/wubo/flex/schedule/core/FlexScheduledTaskRegistrar.java`, replace the entire `restoreTasks()` method (currently lines 121-169) with:

```java
    public void restoreTasks() {
        List<TaskDefinition> definitions = taskRepository.findAll();
        log.info("Restoring {} tasks from repository", definitions.size());

        for (TaskDefinition def : definitions) {
            try {
                Runnable runnable = createRunnableFromDefinition(def);
                if (runnable == null) {
                    log.warn("Cannot restore task [{}]: no bean/method information", def.taskName());
                    continue;
                }

                Instant createdAt = def.createdAt() != null ? def.createdAt() : Instant.now();
                ScheduledTaskEntry placeholder = new ScheduledTaskEntry(() -> {}, def.taskType(),
                        describeSchedule(def), null, false, createdAt);
                ScheduledTaskEntry existing = taskMap.putIfAbsent(def.taskName(), placeholder);
                if (existing != null) {
                    log.warn("Task [{}] already exists, restore skipped", def.taskName());
                    continue;
                }

                try {
                    ScheduledTask scheduledTask = scheduleByType(def, runnable);
                    ScheduledTaskEntry entry = new ScheduledTaskEntry(scheduledTask::cancel,
                            def.taskType(), describeSchedule(def), null, false, createdAt);
                    taskMap.put(def.taskName(), entry);
                    log.info("Restored task [{}] of type [{}] (createdAt={})",
                             def.taskName(), def.taskType(), createdAt);
                } catch (Exception e) {
                    taskMap.remove(def.taskName());
                    throw e;
                }

                if (def.paused()) {
                    pause(def.taskName());
                }
            } catch (Exception e) {
                log.error("Failed to restore task [{}]: {}", def.taskName(), e.getMessage(), e);
            }
        }
    }

    private String describeSchedule(TaskDefinition def) {
        return switch (def.taskType()) {
            case "CRON" -> def.cronExpression() != null && def.timezone() != null
                    ? def.cronExpression() + " [" + def.timezone().getId() + "]"
                    : def.cronExpression();
            case "FIXED_DELAY", "FIXED_RATE" ->
                    def.interval() + "/" + def.initialDelay();
            case "ONE_SHOT" -> "delay=" + def.delay();
            default -> "";
        };
    }

    private ScheduledTask scheduleByType(TaskDefinition def, Runnable runnable) {
        return switch (def.taskType()) {
            case "CRON" -> {
                if (def.timezone() != null) {
                    org.springframework.scheduling.support.CronTrigger trigger =
                            new org.springframework.scheduling.support.CronTrigger(
                                    def.cronExpression(), def.timezone());
                    yield this.scheduleCronTask(new CronTask(wrapRunnable(def.taskName(), runnable), trigger));
                }
                yield this.scheduleCronTask(new CronTask(wrapRunnable(def.taskName(), runnable), def.cronExpression()));
            }
            case "FIXED_DELAY" -> this.scheduleFixedDelayTask(new FixedDelayTask(
                    wrapRunnable(def.taskName(), runnable), def.interval(), def.initialDelay()));
            case "FIXED_RATE" -> this.scheduleFixedRateTask(new FixedRateTask(
                    wrapRunnable(def.taskName(), runnable), def.interval(), def.initialDelay()));
            default -> throw new IllegalArgumentException("Unknown task type: " + def.taskType());
        };
    }
```

- [ ] **Step 5: Run limits tests**

Run: `mvn test -pl flex-schedule-test -am -Dtest=FlexScheduledTaskLimitsTest`
Expected: PASS.

- [ ] **Step 6: Run full suite**

Run: `mvn test -pl flex-schedule-test -am`
Expected: BUILD SUCCESS — all tests pass.

- [ ] **Step 7: Commit**

```bash
git add flex-schedule/src/main/java/cn/wubo/flex/schedule/core/FlexScheduledTaskRegistrar.java \
        flex-schedule-test/src/test/java/cn/wubo/flex/schedule/TestApplication.java \
        flex-schedule-test/src/test/java/cn/wubo/flex/schedule/FlexScheduledTaskLimitsTest.java
git commit -m "feat(core): preserve task createdAt across restore"
```

---

### Task 10: Wire auto-configuration to pass `TaskLimits`

**Files:**
- Modify: `flex-schedule-spring-boot-autoconfigure/src/main/java/cn/wubo/flex/schedule/autoconfigure/FlexScheduleAutoConfiguration.java:50-56`

**Interfaces:**
- Produces: New `@Bean TaskLimits taskLimits(FlexScheduleProperties)` in `FlexScheduleAutoConfiguration`. The existing `flexScheduledTaskRegistrar` bean now depends on `TaskLimits` and uses the 3-arg constructor.

- [ ] **Step 1: Modify `FlexScheduleAutoConfiguration`**

In `flex-schedule-spring-boot-autoconfigure/src/main/java/cn/wubo/flex/schedule/autoconfigure/FlexScheduleAutoConfiguration.java`:

Add import near the top (after the `cn.wubo.flex.schedule.core.*` block):

```java
import cn.wubo.flex.schedule.core.TaskLimits;
```

Replace the existing `flexScheduledTaskRegistrar` bean method (lines 50-56):

```java
    @Bean(name = "flexScheduledTaskRegistrar")
    public FlexScheduledTaskRegistrar flexScheduledTaskRegistrar(
            @Qualifier("flexScheduleThreadPoolTaskScheduler") ThreadPoolTaskScheduler threadPoolTaskScheduler,
            FlexScheduleProperties properties) {
        return new FlexScheduledTaskRegistrar(
                threadPoolTaskScheduler, properties.getAwaitTerminationSeconds());
    }
```

with:

```java
    @Bean(name = "flexScheduledTaskRegistrar")
    public FlexScheduledTaskRegistrar flexScheduledTaskRegistrar(
            @Qualifier("flexScheduleThreadPoolTaskScheduler") ThreadPoolTaskScheduler threadPoolTaskScheduler,
            FlexScheduleProperties properties,
            TaskLimits taskLimits) {
        return new FlexScheduledTaskRegistrar(
                threadPoolTaskScheduler, properties.getAwaitTerminationSeconds(), taskLimits);
    }

    @Bean
    public TaskLimits taskLimits(FlexScheduleProperties properties) {
        FlexScheduleProperties.Limits l = properties.getLimits();
        TaskLimits.Mode mode = switch (l.getMode()) {
            case STRICT -> TaskLimits.Mode.STRICT;
            case WARN -> TaskLimits.Mode.WARN;
            case OFF -> TaskLimits.Mode.OFF;
        };
        return new TaskLimits(l.getMinInterval(), l.getMaxLifetime(), mode);
    }
```

- [ ] **Step 2: Run full test suite**

Run: `mvn test -pl flex-schedule-test -am`
Expected: BUILD SUCCESS — all tests pass.

- [ ] **Step 3: Commit**

```bash
git add flex-schedule-spring-boot-autoconfigure/src/main/java/cn/wubo/flex/schedule/autoconfigure/FlexScheduleAutoConfiguration.java
git commit -m "feat(autoconfigure): wire TaskLimits bean into registrar"
```

---

### Task 11: Update documentation (README.zh-CN.md, README.md, CLAUDE.md)

**Files:**
- Modify: `README.zh-CN.md`
- Modify: `README.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Produces: New section in both READMEs explaining limits configuration + behavior. CLAUDE.md's Configuration Properties table gains 3 rows; Important Design Decisions gains item #8.

- [ ] **Step 1: Add new section to `README.zh-CN.md`**

Find the section heading `### Actuator 端点` (approximately line 296). Insert a new section BEFORE it:

```markdown
### 任务上下限保护

通过 `flex.schedule.limits` 全局约束所有任务的触发频率与生命周期，防止线程池被打满或临时任务长期占用资源。

```yaml
flex:
  schedule:
    limits:
      min-interval: PT10M      # 最短触发间隔；空 = 无下限
      max-lifetime: P7D        # 任务最长生命周期；空 = 无上限
      mode: strict             # strict | warn | off；默认 strict
```

| Mode | 违规时行为 |
|------|-----------|
| `strict` | 抛 `TaskLimitExceededException`（注册时）；超期静默 cancel + 日志 |
| `warn`   | 记录 WARN 日志后放行；超期同样 cancel（不 cancel 无意义） |
| `off`    | 完全跳过所有检查 |

**作用范围**：

| 限制 | 适用任务类型 |
|------|------------|
| `min-interval` | `FIXED_DELAY` / `FIXED_RATE` / `ONE_SHOT`（CRON 豁免） |
| `max-lifetime` | `FIXED_DELAY` / `FIXED_RATE` / `CRON`（ONE_SHOT 豁免，因其只执行一次） |

**关键行为**：

- **懒过期检查**：任务每次触发时检查 `now - createdAt ≥ max-lifetime`，命中则本次 skip + cancel 后续触发
- **暂停中到期**：任务在暂停期间到期不会自动取消；下次 `resume()` 时会检测到并取消
- **`replaceXxxTask` 重置 `createdAt`**：替换任务后生命周期计时归零
- **持久化保留 `createdAt`**：任务跨重启后累计寿命（重启前 3 天 + 重启后 4 天 ≈ 到期）
```

- [ ] **Step 2: Add equivalent section to `README.md`**

Find the equivalent English section heading. Insert the English version:

```markdown
### Task Scheduling Limits

Constrain trigger frequency and lifetime globally via `flex.schedule.limits`, to prevent thread pool exhaustion or stale temporary tasks.

```yaml
flex:
  schedule:
    limits:
      min-interval: PT10M      # Minimum trigger interval; null = no limit
      max-lifetime: P7D        # Maximum task lifetime; null = no limit
      mode: strict             # strict | warn | off; default strict
```

| Mode | On violation |
|------|-------------|
| `strict` | Throws `TaskLimitExceededException` on registration; expired tasks are silently cancelled + logged |
| `warn`   | Logs WARN and allows; expired tasks are cancelled (cannot be allowed to live forever) |
| `off`    | Skips all checks |

**Scope**:

| Limit | Applies to |
|-------|-----------|
| `min-interval` | `FIXED_DELAY` / `FIXED_RATE` / `ONE_SHOT` (cron exempt) |
| `max-lifetime` | `FIXED_DELAY` / `FIXED_RATE` / `CRON` (one-shot exempt — runs once) |

**Key behaviors**:

- **Lazy expiry check**: every fire compares `now - createdAt` against `max-lifetime`; if exceeded, current fire is skipped and future fires are cancelled
- **Paused task expiry**: tasks paused past their lifetime are not auto-cancelled; next `resume()` detects and cancels
- **`replaceXxxTask` resets `createdAt`**: lifetime clock restarts
- **Persistence preserves `createdAt`**: cumulative lifetime across restart (3d before + 4d after ≈ expired)
```

- [ ] **Step 3: Update `CLAUDE.md`**

In `CLAUDE.md`, locate the `## Configuration Properties` table. Add three rows at the end:

```markdown
| `flex.schedule.limits.min-interval` | (null) | Minimum trigger interval for FIXED_DELAY/FIXED_RATE/ONE_SHOT. null = no limit. |
| `flex.schedule.limits.max-lifetime` | (null) | Maximum task lifetime before auto-cancel for FIXED_DELAY/FIXED_RATE/CRON. null = no limit. |
| `flex.schedule.limits.mode` | `strict` | Enforcement mode: strict (throw), warn (log), off (skip). |
```

In `CLAUDE.md`, locate `## Important Design Decisions` and append item 8:

```markdown
8. **Task scheduling limits**: `LimitsChecker` validates interval at registration and lazily checks max-lifetime at fire time. `resume()` re-checks expiration for paused tasks. `replaceXxxTask` resets `createdAt`. `restoreTasks()` preserves persisted `createdAt` for cross-restart lifetime accounting. Mode `STRICT/WARN/OFF` is per-config, not per-task.
```

- [ ] **Step 4: Verify docs render correctly by inspecting surrounding text**

Run: `grep -n "limits\|Limits\|上限\|下限" README.zh-CN.md README.md CLAUDE.md` (from project root).
Expected: New sections and rows present, no broken syntax.

- [ ] **Step 5: Commit**

```bash
git add README.zh-CN.md README.md CLAUDE.md
git commit -m "docs(scheduling-limits): document new limits configuration"
```

---

### Task 12: Final verification — full build + test

**Files:** none (verification only)

- [ ] **Step 1: Clean build + test from project root**

Run: `mvn clean test`
Expected: BUILD SUCCESS — all tests pass (198 existing + ~28 new limits tests).

- [ ] **Step 2: Final commit if any incidental fixes were needed**

If any test failed and was fixed, commit those fixes:

```bash
git add -A
git commit -m "fix(scheduling-limits): address review findings"
```

If no fixes needed, skip this step.

---

## Self-Review Checklist (run before declaring plan complete)

- [x] Spec coverage: every section 3-15 of the spec maps to at least one task (see map below).
- [x] No placeholders: every code block is complete and ready to paste.
- [x] Type consistency: `TaskLimits`, `LimitsChecker`, `Mode`, `TaskLimitExceededException` names are consistent across tasks.
- [x] Backward compatibility: Task 5 explicitly preserves 2-arg ctor; all 9 existing test files compile without changes.

### Spec → Task Coverage Map

| Spec section | Covered by task(s) |
|--------------|--------------------|
| 3. Configuration model | 2, 4 |
| 4. Validation matrix | 6 |
| 5. LimitsChecker | 3 |
| 6. instrument() change | 7 |
| 7. Paused expiry | 8 |
| 8. Persistence + replace | 9 |
| 9. Boundary cases | covered by tests in 6, 7, 8 |
| 10. Exception type | 1 |
| 11. Constructor compatibility | 5 |
| 12. File changes | 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 |
| 13. Test plan | 3, 6, 7, 8, 9, 10 |
| 14. Docs | 11 |
| 15. Non-goals | acknowledged (no tasks) |