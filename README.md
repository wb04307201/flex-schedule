# FlexSchedule（灵动调度）Spring Boot Starter

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3-brightgreen.svg)](https://spring.io/projects/spring-boot)

A lightweight flex scheduling thread pool for Spring Boot — add, remove, and replace scheduled tasks at runtime.

[English](README.md) | [中文](README.zh-CN.md)

---

## Features

- **Flex task management** — Add, cancel, replace, pause, and resume tasks at runtime
- **3 scheduling modes** — Cron, FixedDelay, FixedRate
- **Timezone support** — Cron tasks with custom timezone (`addCronTask(name, cron, ZoneId, runnable)`)
- **One-shot tasks** — Delayed single-execution with auto-removal
- **Retry on failure** — Fixed or exponential backoff with configurable max delay
- **Execution timeout** — Automatically interrupt tasks that exceed their time limit
- **Task dependency chains** — Sequential task execution with `TaskChain` builder
- **Execution history** — In-memory ring buffer of execution records per task
- **Cluster-aware scheduling** — `DistributedLock` interface for multi-node coordination (ShedLock/Redis/ZooKeeper)
- **Thread-safe** — `ConcurrentHashMap` with atomic operations
- **Auto-configuration** — Zero-config with starter dependency
- **Graceful shutdown** — Configurable wait for in-flight tasks
- **Lifecycle listeners** — Before / after / error callbacks
- **Micrometer metrics** — Execution counters, timers, gauges (optional)
- **Health indicator** — Reports scheduler health to `/actuator/health` (optional)
- **Actuator endpoint** — REST CRUD at `/actuator/flexschedule` with access control (optional)
- **Reflective scheduling** — `BeanMethodRunnable` with AOP proxy support

## Requirements

| Dependency | Version |
|------------|---------|
| JDK | 17+ |
| Spring Boot | 3.x |

## Installation

### Maven

```xml
<dependency>
    <groupId>io.github.wb04307201</groupId>
    <artifactId>flex-schedule-spring-boot-starter</artifactId>
    <version>${latest.version}</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.github.wb04307201:flex-schedule-spring-boot-starter:${latest.version}'
```

## Quick Start

```java
@Service
public class DemoService {

    @Autowired
    private FlexScheduledTaskService taskService;

    public void demo() {
        // Cron task
        taskService.add("cronJob", "0/5 * * * * ?", () -> doWork());

        // Fixed delay (seconds)
        taskService.addFixedDelayTask("delayJob", 10, 0, () -> doWork());

        // Fixed rate (Duration precision)
        taskService.addFixedRateTask("rateJob", Duration.ofSeconds(5), Duration.ZERO, () -> doWork());

        // One-shot delayed task (auto-removes after execution)
        taskService.schedule("notify", Duration.ofMinutes(5), () -> sendEmail());

        // Replace existing task or add new
        taskService.replaceCronTask("cronJob", "0/10 * * * * ?", () -> doNewWork());

        // Cancel
        taskService.cancel("cronJob");

        // Query
        taskService.exists("rateJob");           // true
        taskService.listTasks();                 // List<TaskInfo>
        taskService.getTaskDetail("rateJob");    // Optional<TaskDetail>
    }
}
```

## Configuration

```yaml
flex:
  schedule:
    enabled: true                       # Enable auto-configuration (default: true)
    pool-size: 16                       # Thread pool size
    thread-name-prefix: "FlexScheduleThreadPool-" # Thread name prefix
    remove-on-cancel: true              # Remove cancelled tasks from pool
    await-termination-seconds: 30       # Graceful shutdown timeout (seconds)
```

## API Reference

### Core Operations

| Method | Description |
|--------|-------------|
| `add(name, cron, runnable [, retryPolicy])` | Register a Cron task |
| `add(name, cron, ZoneId, runnable)` | Register a Cron task with timezone |
| `add(name, cron, timeout, runnable)` | Register a Cron task with execution timeout |
| `addFixedDelayTask(name, interval, initialDelay, runnable [, retryPolicy])` | Register a fixed-delay task |
| `addFixedRateTask(name, interval, initialDelay, runnable [, retryPolicy])` | Register a fixed-rate task |
| `schedule(name, delay, runnable)` | Schedule a one-shot delayed task |
| `cancel(name)` | Cancel a task |
| `pause(name)` | Pause a task (skip execution until resumed) |
| `resume(name)` | Resume a paused task |
| `isPaused(name)` | Check if a task is paused |
| `exists(name)` | Check if a task exists |
| `listTasks()` | List all tasks (`List<TaskInfo>`) |
| `getTaskDetail(name)` | Get task detail (`Optional<TaskDetail>`) |
| `replaceCronTask / replaceFixedDelayTask / replaceFixedRateTask(...)` | Replace or add a task |
| `addListener / removeListener(listener)` | Manage lifecycle listeners |
| `getExecutionHistory(name, limit)` | Get execution history for a task |
| `getAllExecutionHistory(limit)` | Get all execution history |
| `setDistributedLock(lock)` | Set cluster-aware distributed lock |

- `interval` / `initialDelay` / `delay` accept `long` (seconds) or `Duration` (any precision)
- `retryPolicy` is optional; see [Retry Policy](#retry-policy)

### TaskInfo & TaskDetail

```java
record TaskInfo(String taskName, String taskType, String schedule) {}
// taskType: CRON | FIXED_DELAY | FIXED_RATE | ONE_SHOT

record TaskDetail(String taskName, String taskType, String schedule,
                  boolean oneShot, RetryPolicy retryPolicy, Instant createdAt,
                  boolean paused) {}
```

## Advanced Usage

### Task Execution Listeners

```java
@Component
public class MyListener implements TaskExecutionListener {
    @Override public void beforeExecution(String name) { /* ... */ }
    @Override public void afterExecution(String name) { /* ... */ }
    @Override public void onError(String name, Throwable error) { /* ... */ }
}

// Register
taskService.addListener(myListener);
taskService.removeListener(myListener);
```

All methods have default no-op implementations. Listener exceptions are caught and logged.

### Retry Policy

```java
// Fixed: retry 3 times, 5s between attempts
RetryPolicy fixed = RetryPolicy.fixed(3, Duration.ofSeconds(5));
taskService.add("task", "0 * * * * ?", runnable, fixed);

// Exponential backoff: delay doubles each attempt (1s → 2s → 4s)
RetryPolicy exponential = RetryPolicy.exponential(3, Duration.ofSeconds(1));
taskService.addFixedDelayTask("task", Duration.ofMinutes(1), Duration.ZERO, runnable, exponential);
```

| Backoff | Delay for attempt N |
|---------|-------------------|
| `FIXED` | `delay` |
| `EXPONENTIAL` | `delay × 2^(N-1)` |

Each retry triggers the full listener lifecycle. If all retries fail, the exception propagates.

### Reflective Bean Method Scheduling

```java
// Invoke bean method by name
Runnable runnable = new BeanMethodRunnable("myService", "doWork");
taskService.add("task", "0 0/1 * * * ?", runnable);

// With parameters
Runnable withParams = new BeanMethodRunnable("myService", "process", List.of("hello", 42));
taskService.add("task", "0 0/5 * * * ?", withParams);
```

Supports automatic primitive-to-wrapper type matching (`Integer` → `int`). Also supports AOP-proxied beans (CGLIB/JDK dynamic proxies).

### Pause / Resume

```java
// Pause a running task (skips execution but keeps it registered)
taskService.pause("myTask");

// Check if paused
taskService.isPaused("myTask");  // true

// Resume
taskService.resume("myTask");
```

### Execution Timeout

```java
// Task will be interrupted if it takes longer than 30 seconds
taskService.add("longTask", "0 * * * * ?", Duration.ofSeconds(30), () -> {
    // If this takes > 30s, ExecutionTimeoutException is thrown
    doLongRunningWork();
});

// Also works with fixed-delay and fixed-rate
taskService.addFixedRateTask("rateTask", Duration.ofMinutes(1), Duration.ZERO,
    Duration.ofSeconds(45), () -> doWork());
```

### Task Dependency Chains

```java
// Execute tasks sequentially
TaskChain.create(registrar)
    .then("extract", () -> extractData())
    .then("transform", Duration.ofSeconds(30), () -> transformData())  // with timeout
    .then("load", () -> loadData())
    .execute();  // returns CompletableFuture<Void>

// Schedule a chain to run after a delay
TaskChain.create(registrar)
    .then("step1", () -> step1())
    .then("step2", () -> step2())
    .schedule("nightlyPipeline", Duration.ofHours(6));
```

If any step fails, the chain stops and the error propagates.

### Execution History

```java
// Enable in-memory execution history (100 records per task)
registrar.setExecutionHistory(new InMemoryExecutionHistory(100));

// Query history
List<ExecutionRecord> records = taskService.getExecutionHistory("myTask", 10);
// Each record contains: taskName, taskType, startTime, duration, success, error

// Get all history across tasks
List<ExecutionRecord> all = taskService.getAllExecutionHistory(50);

// Clear history
taskService.clearExecutionHistory("myTask");  // specific task
taskService.clearExecutionHistory(null);       // all tasks
```

### Cluster-Aware Scheduling (Distributed Lock)

In multi-instance deployments, use a `DistributedLock` to ensure only one node executes a task:

```java
// Implement the interface (or use a provided adapter)
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

// Set on the registrar
taskService.setDistributedLock(myDistributedLock);
```

The lock is acquired before each execution and released after (even on failure). If another node holds the lock, execution is skipped.

### Micrometer Metrics

Automatically active when `spring-boot-starter-actuator` is on the classpath:

| Metric | Type | Tags |
|--------|------|------|
| `flex.schedule.task.executions` | Counter | `taskName`, `taskType`, `result` |
| `flex.schedule.task.duration` | Timer | `taskName`, `taskType` |
| `flex.schedule.active.tasks` | Gauge | — |

Integrates with Prometheus, Grafana, and any Micrometer-compatible system. Zero overhead when Micrometer is absent.

### Actuator Endpoint

Active when `spring-boot-starter-actuator` + `spring-boot-starter-web` are on the classpath.

```yaml
management:
  endpoints:
    web:
      exposure:
        include: flexschedule
```

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/actuator/flexschedule` | List all tasks |
| `GET` | `/actuator/flexschedule/{name}` | Get task detail |
| `POST` | `/actuator/flexschedule` | Add a task |
| `DELETE` | `/actuator/flexschedule/{name}` | Cancel a task |

**POST body:**

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

For `FIXED_DELAY` / `FIXED_RATE`, use `intervalSeconds` (+ optional `initialDelaySeconds`) instead of `cron`.
Only `BeanMethodRunnable` style tasks are supported via REST.

#### Endpoint Security

**Write operations (POST/DELETE) are disabled by default** to prevent unauthorized task manipulation:

```yaml
flex:
  schedule:
    endpoint:
      write-enabled: true              # Enable POST/DELETE (default: false)
      allowed-beans:                   # Optional bean allowlist (empty = all beans)
        - cleanupService
        - reportService
```

| Setting | Default | Description |
|---------|---------|-------------|
| `write-enabled` | `false` | Must be `true` for POST/DELETE operations |
| `allowed-beans` | `[]` | Only these beans can be scheduled via REST. Empty = unrestricted |

For custom authorization logic, implement `EndpointAccessControl` and register it as a Spring bean.

> **Warning**: Enabling write operations without proper security (Spring Security, network restrictions) may expose your application to remote code execution risks via reflective bean method invocation.

## Exceptions

| Exception | When |
|-----------|------|
| `TaskAlreadyExistsException` | Adding a task with a duplicate name |
| `BeanMethodRunnableException` | Reflective bean method invocation fails |
| `IllegalArgumentException` | Parameter validation (null/empty) |

All extend `FlexScheduleException`.

## Modules

| Module | artifactId | Purpose |
|--------|------------|---------|
| `flex-schedule/` | `flex-schedule` | Core library |
| `flex-schedule-spring-boot-autoconfigure/` | `flex-schedule-spring-boot-autoconfigure` | Auto-config, metrics, endpoint |
| `flex-schedule-spring-boot-starter/` | `flex-schedule-spring-boot-starter` | Starter (dependencies only) |
| `flex-schedule-test/` | `flex-schedule-test` | Tests (198 cases) |

## Build

```bash
./mvnw clean install
```

## FAQ

**Task names must be unique?**
Yes. Use `replace*()` methods to atomically swap, or `cancel()` then `add()`.

**Time units?**
`long` parameters = seconds. `Duration` parameters = any precision.

**Cancel a non-existent task?**
Logs a WARN, no exception thrown.

**What happens on shutdown?**
All tasks cancelled → `shutdown()` → waits up to `await-termination-seconds` (default 30s) → `shutdownNow()` fallback.

**How to disable?**
Set `flex.schedule.enabled=false`.

## License

[Apache License 2.0](LICENSE)
