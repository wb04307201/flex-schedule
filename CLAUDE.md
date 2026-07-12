# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Multi-module Spring Boot Starter library providing flex scheduling thread pool management. Allows adding/removing/pausing scheduled tasks at runtime using cron, fixedDelay, or fixedRate expressions. Supports retry, execution timeout, task chains, execution history, cluster-aware scheduling, Micrometer metrics, and an Actuator endpoint.

- **Language**: Java 17+, Spring Boot 3.5.14
- **Build**: Maven with wrapper
- **Distribution**: JitPack (`io.github.wb04307201:flex-schedule-spring-boot-starter`)
- **Tests**: 198 tests across 18 test classes

## Build Commands

```bash
./mvnw clean install          # Full build + install
./mvnw clean test             # Compile + run all tests
./mvnw test -pl flex-schedule-test  # Run tests only (after install)
```

## Module Structure

| Module | artifactId | Purpose |
|--------|------------|---------|
| `flex-schedule` (parent) | `flex-schedule-parent` | Root POM, dependency management |
| `flex-schedule/` | `flex-schedule` | Core library: registrar, service, bean-method runnable, retry, timeout, chain, history, lock |
| `flex-schedule-spring-boot-autoconfigure/` | `flex-schedule-spring-boot-autoconfigure` | `@AutoConfiguration`, endpoint, health indicator, metrics wiring |
| `flex-schedule-spring-boot-starter/` | `flex-schedule-spring-boot-starter` | Thin starter (dependencies only) |
| `flex-schedule-test/` | `flex-schedule-test` | JUnit 5 unit + integration tests (198 tests) |

## Architecture

**Auto-configuration**: `FlexScheduleAutoConfiguration` uses `@AutoConfiguration` with inner `@Configuration` classes for endpoint, metrics, and health indicator. Activated by default. Disabled via `flex.schedule.enabled=false`.

**Core bean wiring**: `ThreadPoolTaskScheduler` → `FlexScheduledTaskRegistrar` → `FlexScheduledTaskService`. All beans prefixed `flexSchedule*`.

**Task execution pipeline** (in `instrument()` method):
1. Check paused → skip if paused
2. Acquire distributed lock → skip if lock denied
3. Fire `beforeExecution` listeners
4. Execute delegate (with optional retry/timeout)
5. Record metrics + execution history
6. Fire `afterExecution` or `onError` listeners
7. Release distributed lock (in `finally`)

**Key classes**:
- `FlexScheduledTaskRegistrar` — Core engine. Uses `ConcurrentHashMap` + `putIfAbsent` for atomic registration. Supports cron (with timezone), fixedDelay, fixedRate, one-shot, pause/resume, retry, timeout.
- `FlexScheduledTaskService` — Public API facade. Delegates to registrar.
- `BeanMethodRunnable` — Reflectively invokes bean methods by name. Supports AOP proxies via `AopUtils.getTargetClass()`. Caches resolved `Method`.
- `RetryPolicy` — Immutable retry config with FIXED/EXPONENTIAL backoff and maxDelay cap.
- `TaskChain` — Builder for sequential task execution with `CompletableFuture`.
- `ExecutionHistory` / `InMemoryExecutionHistory` — Bounded ring buffer of `ExecutionRecord` per task.
- `DistributedLock` — Interface for cluster-aware scheduling (NOOP default).
- `MetricsRecorder` / `MicrometerMetricsRecorder` — Micrometer integration (optional, `AtomicReference` for thread safety).
- `FlexScheduleHealthIndicator` — Reports scheduler health to `/actuator/health`.
- `FlexScheduleEndpoint` — `@Endpoint` for REST CRUD at `/actuator/flexschedule` with access control.
- `TaskExecutionListener` — Interface for before/after/onError lifecycle hooks.
- `TaskInfo` / `TaskDetail` — Immutable records for task metadata.

**Time units**: `addFixedDelayTask` and `addFixedRateTask` accept intervals in **seconds** (long overload) or **Duration** (Duration overload).

**Graceful shutdown**: `destroy()` cancels all tasks, clears state, then `shutdown()` + `awaitTermination()` on the executor. Does NOT call `super.destroy()` to avoid double-close.

## Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `flex.schedule.enabled` | `true` | Enable/disable auto-configuration |
| `flex.schedule.pool-size` | `16` | Thread pool size |
| `flex.schedule.thread-name-prefix` | `FlexScheduleThreadPool-` | Thread name prefix |
| `flex.schedule.remove-on-cancel` | `true` | Remove cancelled tasks from pool |
| `flex.schedule.await-termination-seconds` | `30` | Graceful shutdown timeout |
| `flex.schedule.endpoint.write-enabled` | `false` | Enable POST/DELETE on actuator endpoint |
| `flex.schedule.endpoint.allowed-beans` | `[]` | Bean allowlist for endpoint write operations |
| `flex.schedule.limits.min-interval` | (null) | Minimum trigger interval for FIXED_DELAY/FIXED_RATE/ONE_SHOT. null = no limit. |
| `flex.schedule.limits.max-lifetime` | (null) | Maximum task lifetime before auto-cancel for FIXED_DELAY/FIXED_RATE/CRON. null = no limit. |
| `flex.schedule.limits.mode` | `strict` | Enforcement mode: strict (throw), warn (log), off (skip). |

## Important Design Decisions

1. **MetricsRecorder wiring**: Uses `AtomicReference` + `FlexScheduleMetricsRegistrar` (ImplementingBean) to avoid circular `@ConditionalOnBean` dependency.
2. **Replace atomicity**: `replaceXxxTask` uses `taskMap.compute()` for atomic cancel+add.
3. **Registration ordering**: Placeholder entry is inserted via `putIfAbsent` before scheduling, preventing execution before registration.
4. **Endpoint security**: `@Endpoint` (not deprecated `@RestControllerEndpoint`) with `EndpointAccessControl` interface. Write operations disabled by default.
5. **Distributed lock**: Acquired per-execution in `instrument()`, released in `finally`. Lock duration defaults to task interval or 30s for cron.
6. **Execution history**: `InMemoryExecutionHistory` uses `ConcurrentLinkedDeque` per task with configurable max size (default 100).
7. **Timeout**: `TimeoutRunnable` wraps delegate in a separate single-thread executor with `Future.get(timeout)`.
8. **Task scheduling limits**: `LimitsChecker` validates interval at registration and lazily checks max-lifetime at fire time. `resume()` re-checks expiration for paused tasks. `replaceXxxTask` resets `createdAt`. `restoreTasks()` preserves persisted `createdAt` for cross-restart lifetime accounting. Mode `STRICT/WARN/OFF` is per-config, not per-task.
