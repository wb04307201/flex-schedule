# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Multi-module Spring Boot Starter library providing flex scheduling thread pool management. Allows adding/removing/pausing scheduled tasks at runtime using cron, fixedDelay, or fixedRate expressions. Supports retry, execution timeout, task chains, execution history, cluster-aware scheduling, Micrometer metrics, and an Actuator endpoint.

- **Language**: Java 25+ (build requires a JDK 25 `JAVA_HOME`), Spring Boot 4.1.1
- **Build**: Maven (no wrapper — `mvnw` was removed; use system `mvn`)
- **Distribution**: JitPack (`io.github.wb04307201:flex-schedule-spring-boot-starter`)
- **Tests**: Run `mvn clean test` to see current counts; this number grows as coverage is added.

## Build Commands

```bash
mvn clean install -Dgpg.skip=true   # Full build + install (GPG signing is bound to
                                    # the verify phase and needs a local key; skip it
                                    # for development builds — CI imports the key)
mvn clean test                      # Compile + run all tests
mvn test -pl flex-schedule-test     # Run tests only (after install)
```

Real-Redis integration tests are opt-in via environment:
`FLEX_RUN_REDIS_TESTS=true` (+ optional `FLEX_REDIS_HOST`/`FLEX_REDIS_PORT`/`FLEX_REDIS_PASSWORD`);
without a reachable Redis they are skipped, keeping the build green.

## Module Structure

| Module | artifactId | Purpose |
|--------|------------|---------|
| `flex-schedule` (parent) | `flex-schedule-parent` | Root POM, dependency management |
| `flex-schedule/` | `flex-schedule` | Core library: registrar, service, bean-method runnable, retry, timeout, chain, history, lock |
| `flex-schedule-spring-boot-autoconfigure/` | `flex-schedule-spring-boot-autoconfigure` | `@AutoConfiguration`, endpoint, health indicator, metrics wiring |
| `flex-schedule-spring-boot-starter/` | `flex-schedule-spring-boot-starter` | Thin starter (dependencies only) |
| `flex-schedule-test/` | `flex-schedule-test` | JUnit 5 unit + integration tests |
| `flex-schedule-redis/` | `flex-schedule-redis` | Redis-backed `DistributedLock` (single-node SETNX) |

## Architecture

**Auto-configuration**: `FlexScheduleAutoConfiguration` is declared `@AutoConfiguration` and exposes the core beans (thread pool, registrar, service, default `ExecutionHistory`, `TaskLimits`, Spring context utils). The optional pieces — endpoint, Micrometer wiring, health indicator, and Redis distributed lock — are split into **sibling top-level `@AutoConfiguration` classes** (`FlexScheduleEndpointAutoConfiguration`, `FlexScheduleMetricsAutoConfiguration`, `FlexScheduleHealthAutoConfiguration`, `FlexScheduleRedisLockAutoConfiguration`). Spring Boot 4 no longer auto-discovers nested `@Configuration` classes inside an outer `@AutoConfiguration`, so each fragment must be its own top-level class registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Activated by default; disabled via `flex.schedule.enabled=false`.

**Core bean wiring**: `ThreadPoolTaskScheduler` → `FlexScheduledTaskRegistrar` → `FlexScheduledTaskService`. All beans prefixed `flexSchedule*`.

**Task execution pipeline** (in `instrument()` method):
1. Lazy max-lifetime check → cancel if expired
2. Check paused → skip if paused
3. Single-node overlap guard (`executingTasks`) → skip if the same task is already executing here (catch-up vs regular fire)
4. Misfire detection (`handleMisfire`) → SKIP policy skips a late fire; FIRE_ONCE/FIRE_IMMEDIATELY log + record and continue
5. Acquire distributed lock → skip if lock denied
6. Fire `beforeExecution` listeners
7. Execute delegate (with optional retry/timeout)
8. Record metrics + execution history
9. Fire `afterExecution` or `onError` listeners
10. Rebase completion-based expected-fire baseline + release distributed lock (in `finally`)

**Key classes**:
- `FlexScheduledTaskRegistrar` — Core engine. Uses `ConcurrentHashMap` + `putIfAbsent` for atomic registration. Supports cron (with timezone), fixedDelay, fixedRate, one-shot, pause/resume, retry, timeout, misfire detection + pause-gap catch-up.
- `FlexScheduledTaskService` — Public API facade. Delegates to registrar.
- `BeanMethodRunnable` — Reflectively invokes bean methods by name. Supports AOP proxies via `AopUtils.getTargetClass()`. Caches resolved `Method`.
- `RetryPolicy` — Immutable retry config with FIXED/EXPONENTIAL backoff and maxDelay cap.
- `MisfirePolicy` — SKIP / FIRE_ONCE / FIRE_IMMEDIATELY semantics for late fires and resume catch-up (opt-in per task; see design decision 9).
- `TaskChain` — Builder for sequential task execution with `CompletableFuture`.
- `ExecutionHistory` / `InMemoryExecutionHistory` — Bounded ring buffer of `ExecutionRecord` per task.
- `DistributedLock` — Interface for cluster-aware scheduling (NOOP default).
- `MetricsRecorder` / `MicrometerMetricsRecorder` — Micrometer integration (optional, `AtomicReference` for thread safety).
- `FlexScheduleHealthIndicator` — Reports scheduler health to `/actuator/health`.
- `FlexScheduleEndpoint` — `@Endpoint` for REST CRUD at `/actuator/flexschedule` with access control.
- `TaskExecutionListener` — Interface for before/after/onError lifecycle hooks.
- `TaskInfo` / `TaskDetail` — Immutable records for task metadata (`TaskDetail` also exposes the misfire config, null when unconfigured).

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
9. **Misfire handling is opt-in**: `null` misfire fields mean "unconfigured" end to end — `TaskDefinition` keeps both-null (1.2.x payloads) as null (its compact constructor only fills a *partially* configured pair from `DEFAULT_MISFIRE_POLICY`/`DEFAULT_MISFIRE_THRESHOLD`), `TaskBuilder.register()` wires `setMisfireConfig` only when a setter was called, and `restoreTasks()` gates on non-null. Unconfigured tasks keep pre-1.3.0 behavior (no late detection, no catch-up). The expected-fire baseline matches Spring's real scheduling: completion-based for FIXED_DELAY and CRON (`rebaseExpectedFireOnCompletion`), grid-based arithmetic advance for FIXED_RATE. `FIRE_ONCE` catches up only when the pause gap actually missed a firing; catch-up bursts are mutually exclusive with regular fires via the `executingTasks` guard. `instrumentedRunnables` (catch-up replay map) is published only AFTER a schedule/swap succeeds — `wrapRunnable` is pure.
