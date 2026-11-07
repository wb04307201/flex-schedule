package cn.wubo.flex.schedule.core;

import cn.wubo.flex.schedule.exception.TaskAlreadyExistsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.*;
import org.springframework.util.Assert;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.support.CronExpression;

@Slf4j
public class FlexScheduledTaskRegistrar extends ScheduledTaskRegistrar {

    /**
     * Hard cap on the number of catch-up executions replayed on resume for
     * {@link MisfirePolicy#FIRE_IMMEDIATELY}. Deliberately a registrar constant,
     * not exposed as configuration.
     */
    static final int MAX_CATCH_UP = 10;

    private final Map<String, ScheduledTaskEntry> taskMap = new ConcurrentHashMap<>(16);
    private final Set<String> pausedTasks = ConcurrentHashMap.newKeySet();
    private final List<TaskExecutionListener> listeners = new CopyOnWriteArrayList<>();
    /** Per-task misfire configuration (policy + threshold). Absent = misfire handling disabled for the task. */
    private final Map<String, MisfireConfig> misfireConfigs = new ConcurrentHashMap<>();
    /**
     * Next expected fire time per recurring task, used for runtime late-fire detection.
     * <p>
     * Expected-fire model (chosen for determinism):
     * <ul>
     *   <li>FIXED_DELAY: completion-based cadence — after each fire (executed, misfire-skipped,
     *       or lock-denied), expected = completion + interval, matching Spring's fixed-delay
     *       contract so delegate runtime never counts as lateness.</li>
     *   <li>FIXED_RATE: grid-based — expected advances by interval until strictly after the actual start.</li>
     *   <li>CRON: completion-based — Spring's CronTrigger computes the next fire from the
     *       COMPLETION time, so the baseline is rebased to the first cron boundary strictly
     *       after each fire's completion; delegate runtime that crosses a boundary is never
     *       counted as lateness.</li>
     * </ul>
     * The first fire after registration, {@link #setMisfireConfig}, or {@link #resume}
     * only seeds the baseline and is never itself judged (the tracker holds no entry).
     * Consequences, all deliberate and deterministic: paused fires do not advance the
     * tracker (the pause gap is settled by resume() catch-up instead); and the restart
     * gap of restored, non-paused tasks is NOT compensated.
     */
    private final Map<String, Instant> nextExpectedFire = new ConcurrentHashMap<>();
    /** Outermost wrapped (instrumented, retry-aware) runnable per task, replayed serially for resume catch-up. */
    private final Map<String, Runnable> instrumentedRunnables = new ConcurrentHashMap<>();
    /**
     * Tasks with a fire currently executing on this node. A regular fire and a resume
     * catch-up burst (or two late-queued fires) can land on different scheduler threads
     * at the same moment; the instrumented runnable skips execution when the task is
     * already in flight, preserving the single-node non-overlap guarantee.
     */
    private final Set<String> executingTasks = ConcurrentHashMap.newKeySet();
    private final long awaitTerminationSeconds;
    private final AtomicReference<MetricsRecorder> metricsRecorder = new AtomicReference<>(MetricsRecorder.NOOP);
    private volatile ExecutionHistory executionHistory = ExecutionHistory.NOOP;
    private volatile DistributedLock distributedLock = DistributedLock.NOOP;
    private volatile TaskRepository taskRepository = new InMemoryTaskRepository();
    private final LimitsChecker limitsChecker;
    private final AtomicBoolean ownsAsyncListenerExecutor = new AtomicBoolean(true);
    private volatile ExecutorService asyncListenerExecutor = createInternalAsyncListenerExecutor();

    private static ExecutorService createInternalAsyncListenerExecutor() {
        return java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "async-listener");
            t.setDaemon(true);
            return t;
        });
    }

    public FlexScheduledTaskRegistrar(ThreadPoolTaskScheduler taskScheduler, long awaitTerminationSeconds) {
        this(taskScheduler, awaitTerminationSeconds, TaskLimits.DISABLED);
    }

    public FlexScheduledTaskRegistrar(ThreadPoolTaskScheduler taskScheduler, long awaitTerminationSeconds, TaskLimits limits) {
        super();
        this.setScheduler(taskScheduler);
        this.awaitTerminationSeconds = awaitTerminationSeconds;
        this.limitsChecker = new LimitsChecker(limits);
    }

    /**
     * Sets the metrics recorder. Defaults to {@link MetricsRecorder#NOOP}.
     * Thread-safe via AtomicReference.
     */
    public void setMetricsRecorder(MetricsRecorder metricsRecorder) {
        MetricsRecorder recorder = metricsRecorder != null ? metricsRecorder : MetricsRecorder.NOOP;
        recorder.setActiveTaskCountSupplier(taskMap::size);
        this.metricsRecorder.set(recorder);
    }

    private MetricsRecorder getMetricsRecorder() {
        return metricsRecorder.get();
    }

    /**
     * Sets the execution history store. Defaults to {@link ExecutionHistory#NOOP}.
     */
    public void setExecutionHistory(ExecutionHistory executionHistory) {
        this.executionHistory = executionHistory != null ? executionHistory : ExecutionHistory.NOOP;
    }

    /**
     * Returns the execution history store.
     */
    public ExecutionHistory getExecutionHistory() {
        return executionHistory;
    }

    /**
     * Sets the distributed lock for cluster-aware scheduling.
     * Defaults to {@link DistributedLock#NOOP} (no cluster coordination).
     *
     * @param distributedLock the distributed lock implementation
     */
    public void setDistributedLock(DistributedLock distributedLock) {
        this.distributedLock = distributedLock != null ? distributedLock : DistributedLock.NOOP;
    }

    /**
     * Returns the distributed lock.
     */
    public DistributedLock getDistributedLock() {
        return distributedLock;
    }

    /**
     * Sets the task repository for persistence.
     * Defaults to {@link InMemoryTaskRepository}.
     *
     * @param taskRepository the task repository implementation
     */
    public void setTaskRepository(TaskRepository taskRepository) {
        this.taskRepository = taskRepository != null ? taskRepository : new InMemoryTaskRepository();
    }

    /**
     * Returns the task repository.
     */
    public TaskRepository getTaskRepository() {
        return taskRepository;
    }

    /**
     * Test-only accessor: returns the currently installed async-listener executor.
     * Package-private to keep it off the public API surface; used by internal tests
     * to inspect lifecycle behavior.
     */
    ExecutorService getAsyncListenerExecutorForTest() {
        return asyncListenerExecutor;
    }

    /**
     * Sets the executor for async listeners.
     * Defaults to an internally-owned cached thread pool with daemon threads.
     * A non-null executor is owned by the caller and is not shut down by this registrar.
     *
     * @param executor the executor to use for async listener invocation; null restores
     *                 an internally-owned daemon pool
     */
    public synchronized void setAsyncListenerExecutor(ExecutorService executor) {
        ExecutorService incoming = executor != null ? executor : createInternalAsyncListenerExecutor();
        // Preserve ownership when the caller re-passes the same internally-owned
        // pool (e.g. via getAsyncListenerExecutorForTest()). Without this, the
        // registrar would flip ownership to false and never shut the pool down
        // on destroy() — leaking its threads.
        boolean incomingOwned = executor == null || executor == this.asyncListenerExecutor;
        ExecutorService previous = this.asyncListenerExecutor;
        boolean previousOwned = ownsAsyncListenerExecutor.get();

        this.asyncListenerExecutor = incoming;
        this.ownsAsyncListenerExecutor.set(incomingOwned);

        if (previousOwned && previous != incoming) {
            previous.shutdown();
        }
    }

    /**
     * Restores tasks from the repository on startup.
     * This method should be called after the registrar is fully initialized.
     * <p>
     * Misfire wiring (1.3.0): each definition's {@code misfirePolicy()}/{@code misfireThreshold()}
     * are applied to the restored task. Definitions with {@code paused() == true} are restored
     * in the paused state with {@code pausedAt} anchored at the definition's {@code updatedAt()},
     * so a later {@link #resume(String)} settles the pause gap that spans the restart.
     * </p>
     * <p>
     * <b>Limitation:</b> the restart gap of restored tasks that are NOT paused is never
     * compensated — no catch-up executions are replayed for fires missed while the
     * application was down, and late-fire detection starts fresh from the first fire
     * after restore.
     * </p>
     */
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
                    // A persisted-paused definition must not get a firing window: mark the
                    // task paused BEFORE scheduling so instrument() skips even an immediate
                    // first trigger (fixes the race where a restored paused task executed
                    // once between scheduleByType and the post-scheduling pause()).
                    if (def.paused()) {
                        pausedTasks.add(def.taskName());
                    }

                    ScheduledTask scheduledTask = scheduleByType(def, runnable);
                    ScheduledTaskEntry entry = new ScheduledTaskEntry(scheduledTask::cancel,
                            def.taskType(), describeSchedule(def), def.retryPolicy(), false, createdAt);
                    taskMap.put(def.taskName(), entry);
                    // Misfire settings persisted on the definition reach the runtime here.
                    // The expected-fire tracker is NOT seeded on restore: the first fire
                    // after restart seeds a fresh baseline, so the restart gap of non-paused
                    // restored tasks is never judged as a misfire and never compensated.
                    if (def.misfirePolicy() != null && def.misfireThreshold() != null
                            && !"ONE_SHOT".equals(def.taskType())) {
                        misfireConfigs.put(def.taskName(),
                                new MisfireConfig(def.misfirePolicy(), def.misfireThreshold()));
                    }
                    log.info("Restored task [{}] of type [{}] (createdAt={})",
                             def.taskName(), def.taskType(), createdAt);
                } catch (Exception e) {
                    taskMap.remove(def.taskName());
                    pausedTasks.remove(def.taskName());
                    clearTaskTrackingState(def.taskName());
                    throw e;
                }

                if (def.paused()) {
                    // Idempotent for set membership; records the entry-level pausedAt field.
                    pause(def.taskName());
                    // The pause started when the definition was last updated (typically
                    // before the restart), so a later resume() settles the gap from there.
                    Instant persistedPausedAt = def.updatedAt() != null ? def.updatedAt() : Instant.now();
                    taskMap.computeIfPresent(def.taskName(), (key, current) ->
                            withPausedAt(current, persistedPausedAt));
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
        Runnable wrapped = def.retryPolicy() != null
                ? wrapRunnable(def.taskName(), runnable, def.retryPolicy())
                : wrapRunnable(def.taskName(), runnable);
        ScheduledTask scheduledTask = switch (def.taskType()) {
            case "CRON" -> {
                if (def.timezone() != null) {
                    org.springframework.scheduling.support.CronTrigger trigger =
                            new org.springframework.scheduling.support.CronTrigger(
                                    def.cronExpression(), def.timezone());
                    yield this.scheduleCronTask(new CronTask(wrapped, trigger));
                }
                yield this.scheduleCronTask(new CronTask(wrapped, def.cronExpression()));
            }
            case "FIXED_DELAY" -> {
                limitsChecker.assertInterval(def.taskName(), def.interval());
                yield this.scheduleFixedDelayTask(new FixedDelayTask(
                        wrapped, def.interval(), def.initialDelay()));
            }
            case "FIXED_RATE" -> {
                limitsChecker.assertInterval(def.taskName(), def.interval());
                yield this.scheduleFixedRateTask(new FixedRateTask(
                        wrapped, def.interval(), def.initialDelay()));
            }
            default -> throw new IllegalArgumentException("Unknown task type: " + def.taskType());
        };
        publishInstrumented(def.taskName(), wrapped);
        return scheduledTask;
    }

    /**
     * Creates a Runnable from a TaskDefinition using BeanMethodRunnable.
     */
    private Runnable createRunnableFromDefinition(TaskDefinition def) {
        if (def.beanName() == null || def.methodName() == null) {
            return null;
        }

        if (def.methodParams() != null && !def.methodParams().isEmpty()) {
            return new BeanMethodRunnable(def.beanName(), def.methodName(), def.methodParams());
        } else {
            return new BeanMethodRunnable(def.beanName(), def.methodName());
        }
    }

    // ─── Listener Management ─────────────────────────────────────────

    public void addListener(TaskExecutionListener listener) {
        Assert.notNull(listener, "listener must not be null");
        listeners.add(listener);
    }

    public void removeListener(TaskExecutionListener listener) {
        listeners.remove(listener);
    }

    // ─── Cron Tasks ──────────────────────────────────────────────────

    public void addCronTask(String taskName, String cron, Runnable runnable) {
        validateTaskParams(taskName, runnable);
        validateCronExpression(cron);

        // Reserve the name first with a placeholder to prevent execution before registration
        ScheduledTaskEntry placeholder = new ScheduledTaskEntry(() -> {}, "CRON", cron);
        ScheduledTaskEntry existing = taskMap.putIfAbsent(taskName, placeholder);
        if (existing != null) {
            throw new TaskAlreadyExistsException(taskName);
        }

        try {
            Runnable wrapped = wrapRunnable(taskName, runnable);
            CronTask cronTask = new CronTask(wrapped, cron);
            ScheduledTask scheduledTask = this.scheduleCronTask(cronTask);
            publishInstrumented(taskName, wrapped);
            ScheduledTaskEntry entry = new ScheduledTaskEntry(scheduledTask::cancel, "CRON", cron);
            taskMap.put(taskName, entry);
            log.info("Added cron task [{}] with expression [{}]", taskName, cron);
        } catch (Exception e) {
            // Rollback on failure
            taskMap.remove(taskName);
            throw e;
        }
    }

    /**
     * Adds a cron task with a specific timezone.
     *
     * @param taskName unique task identifier
     * @param cron     cron expression
     * @param zoneId   timezone for cron evaluation (e.g., "Asia/Shanghai", "America/New_York")
     * @param runnable task implementation
     */
    public void addCronTask(String taskName, String cron, ZoneId zoneId, Runnable runnable) {
        validateTaskParams(taskName, runnable);
        validateCronExpression(cron);
        Assert.notNull(zoneId, "zoneId must not be null");

        String scheduleDesc = cron + " [" + zoneId.getId() + "]";

        // Reserve the name first with a placeholder
        ScheduledTaskEntry placeholder = new ScheduledTaskEntry(() -> {}, "CRON", scheduleDesc);
        ScheduledTaskEntry existing = taskMap.putIfAbsent(taskName, placeholder);
        if (existing != null) {
            throw new TaskAlreadyExistsException(taskName);
        }

        try {
            org.springframework.scheduling.support.CronTrigger trigger =
                    new org.springframework.scheduling.support.CronTrigger(cron, zoneId);
            Runnable wrapped = wrapRunnable(taskName, runnable);
            CronTask cronTask = new CronTask(wrapped, trigger);
            ScheduledTask scheduledTask = this.scheduleCronTask(cronTask);
            publishInstrumented(taskName, wrapped);
            ScheduledTaskEntry entry = new ScheduledTaskEntry(scheduledTask::cancel, "CRON", scheduleDesc);
            taskMap.put(taskName, entry);
            log.info("Added cron task [{}] with expression [{}] in timezone [{}]", taskName, cron, zoneId);
        } catch (Exception e) {
            // Rollback on failure
            taskMap.remove(taskName);
            throw e;
        }
    }

    // ─── Timeout-Aware Overloads ─────────────────────────────────────

    /**
     * Adds a Cron task with an execution timeout.
     * If the task does not complete within the timeout, it will be interrupted.
     *
     * @param taskName unique task identifier
     * @param cron     Cron expression
     * @param timeout  maximum execution time
     * @param runnable task implementation
     */
    public void addCronTask(String taskName, String cron, Duration timeout, Runnable runnable) {
        addCronTask(taskName, cron, new TimeoutRunnable(taskName, runnable, timeout));
    }

    /**
     * Adds a fixed-delay task with an execution timeout.
     *
     * @param taskName     unique task identifier
     * @param interval     delay between executions
     * @param initialDelay initial delay before first execution
     * @param timeout      maximum execution time
     * @param runnable     task implementation
     */
    public void addFixedDelayTask(String taskName, Duration interval, Duration initialDelay,
                                  Duration timeout, Runnable runnable) {
        addFixedDelayTask(taskName, interval, initialDelay, new TimeoutRunnable(taskName, runnable, timeout));
    }

    /**
     * Adds a fixed-rate task with an execution timeout.
     *
     * @param taskName     unique task identifier
     * @param interval     period between execution starts
     * @param initialDelay initial delay before first execution
     * @param timeout      maximum execution time
     * @param runnable     task implementation
     */
    public void addFixedRateTask(String taskName, Duration interval, Duration initialDelay,
                                 Duration timeout, Runnable runnable) {
        addFixedRateTask(taskName, interval, initialDelay, new TimeoutRunnable(taskName, runnable, timeout));
    }

    // ─── Fixed Delay Tasks ───────────────────────────────────────────

    /**
     * Adds a fixed-delay task with interval and initial delay in seconds.
     */
    public void addFixedDelayTask(String taskName, long interval, long initialDelay, Runnable runnable) {
        addFixedDelayTask(taskName, Duration.ofSeconds(interval), Duration.ofSeconds(initialDelay), runnable);
    }

    /**
     * Adds a fixed-delay task with {@link Duration} parameters for sub-second precision.
     */
    public void addFixedDelayTask(String taskName, Duration interval, Duration initialDelay, Runnable runnable) {
        validateTaskParams(taskName, runnable);
        Assert.notNull(interval, "interval must not be null");
        Assert.isTrue(interval.compareTo(Duration.ZERO) > 0, "interval must be positive, was " + interval);
        Assert.notNull(initialDelay, "initialDelay must not be null");
        Assert.isTrue(!initialDelay.isNegative(), "initialDelay must not be negative, was " + initialDelay);
        validateIntervalLimit(taskName, interval);

        // Reserve the name first with a placeholder to prevent execution before registration
        ScheduledTaskEntry placeholder = new ScheduledTaskEntry(() -> {}, "FIXED_DELAY",
                interval + "/" + initialDelay);
        ScheduledTaskEntry existing = taskMap.putIfAbsent(taskName, placeholder);
        if (existing != null) {
            throw new TaskAlreadyExistsException(taskName);
        }

        try {
            Runnable wrapped = wrapRunnable(taskName, runnable);
            FixedDelayTask fixedDelayTask = new FixedDelayTask(
                    wrapped, interval, initialDelay);
            ScheduledTask scheduledTask = this.scheduleFixedDelayTask(fixedDelayTask);
            publishInstrumented(taskName, wrapped);
            ScheduledTaskEntry entry = new ScheduledTaskEntry(scheduledTask::cancel, "FIXED_DELAY",
                    interval + "/" + initialDelay);
            taskMap.put(taskName, entry);
            log.info("Added fixed-delay task [{}] interval={} initialDelay={}", taskName, interval, initialDelay);
        } catch (Exception e) {
            // Rollback on failure
            taskMap.remove(taskName);
            throw e;
        }
    }

    // ─── Fixed Rate Tasks ────────────────────────────────────────────

    /**
     * Adds a fixed-rate task with interval and initial delay in seconds.
     */
    public void addFixedRateTask(String taskName, long interval, long initialDelay, Runnable runnable) {
        addFixedRateTask(taskName, Duration.ofSeconds(interval), Duration.ofSeconds(initialDelay), runnable);
    }

    /**
     * Adds a fixed-rate task with {@link Duration} parameters for sub-second precision.
     */
    public void addFixedRateTask(String taskName, Duration interval, Duration initialDelay, Runnable runnable) {
        validateTaskParams(taskName, runnable);
        Assert.notNull(interval, "interval must not be null");
        Assert.isTrue(interval.compareTo(Duration.ZERO) > 0, "interval must be positive, was " + interval);
        Assert.notNull(initialDelay, "initialDelay must not be null");
        Assert.isTrue(!initialDelay.isNegative(), "initialDelay must not be negative, was " + initialDelay);
        validateIntervalLimit(taskName, interval);

        // Reserve the name first with a placeholder to prevent execution before registration
        ScheduledTaskEntry placeholder = new ScheduledTaskEntry(() -> {}, "FIXED_RATE",
                interval + "/" + initialDelay);
        ScheduledTaskEntry existing = taskMap.putIfAbsent(taskName, placeholder);
        if (existing != null) {
            throw new TaskAlreadyExistsException(taskName);
        }

        try {
            Runnable wrapped = wrapRunnable(taskName, runnable);
            FixedRateTask fixedRateTask = new FixedRateTask(
                    wrapped, interval, initialDelay);
            ScheduledTask scheduledTask = this.scheduleFixedRateTask(fixedRateTask);
            publishInstrumented(taskName, wrapped);
            ScheduledTaskEntry entry = new ScheduledTaskEntry(scheduledTask::cancel, "FIXED_RATE",
                    interval + "/" + initialDelay);
            taskMap.put(taskName, entry);
            log.info("Added fixed-rate task [{}] interval={} initialDelay={}", taskName, interval, initialDelay);
        } catch (Exception e) {
            // Rollback on failure
            taskMap.remove(taskName);
            throw e;
        }
    }

    // ─── Retry-Aware Overloads ─────────────────────────────────────────

    /**
     * Adds a cron task with a retry policy. On execution failure, the task
     * will be retried according to the policy before the error propagates.
     */
    public void addCronTask(String taskName, String cron, Runnable runnable, RetryPolicy retryPolicy) {
        validateTaskParams(taskName, runnable);
        validateCronExpression(cron);
        Assert.notNull(retryPolicy, "retryPolicy must not be null");

        // Reserve the name first with a placeholder to prevent execution before registration
        ScheduledTaskEntry placeholder = new ScheduledTaskEntry(() -> {}, "CRON", cron,
                retryPolicy, false, Instant.now());
        ScheduledTaskEntry existing = taskMap.putIfAbsent(taskName, placeholder);
        if (existing != null) {
            throw new TaskAlreadyExistsException(taskName);
        }

        try {
            Runnable wrapped = wrapRunnable(taskName, runnable, retryPolicy);
            CronTask cronTask = new CronTask(wrapped, cron);
            ScheduledTask scheduledTask = this.scheduleCronTask(cronTask);
            publishInstrumented(taskName, wrapped);
            ScheduledTaskEntry entry = new ScheduledTaskEntry(scheduledTask::cancel, "CRON", cron,
                    retryPolicy, false, Instant.now());
            taskMap.put(taskName, entry);
            log.info("Added cron task [{}] with expression [{}] and retry policy {}", taskName, cron, retryPolicy);
        } catch (Exception e) {
            // Rollback on failure
            taskMap.remove(taskName);
            throw e;
        }
    }

    /**
     * Adds a cron task with both a timezone and a retry policy. On execution failure,
     * the task will be retried according to the policy before the error propagates.
     *
     * @param taskName    unique task identifier
     * @param cron        cron expression
     * @param zoneId      timezone for cron evaluation (must not be null)
     * @param retryPolicy retry policy (must not be null)
     * @param runnable    task implementation
     */
    public void addCronTask(String taskName, String cron, ZoneId zoneId, RetryPolicy retryPolicy, Runnable runnable) {
        validateTaskParams(taskName, runnable);
        validateCronExpression(cron);
        Assert.notNull(zoneId, "zoneId must not be null");
        Assert.notNull(retryPolicy, "retryPolicy must not be null");

        String scheduleDesc = cron + " [" + zoneId.getId() + "]";
        Instant createdAt = Instant.now();

        // Reserve the name first with a placeholder to prevent execution before registration
        ScheduledTaskEntry placeholder = new ScheduledTaskEntry(() -> {}, "CRON", scheduleDesc,
                retryPolicy, false, createdAt);
        ScheduledTaskEntry existing = taskMap.putIfAbsent(taskName, placeholder);
        if (existing != null) {
            throw new TaskAlreadyExistsException(taskName);
        }

        try {
            org.springframework.scheduling.support.CronTrigger trigger =
                    new org.springframework.scheduling.support.CronTrigger(cron, zoneId);
            Runnable wrapped = wrapRunnable(taskName, runnable, retryPolicy);
            CronTask cronTask = new CronTask(wrapped, trigger);
            ScheduledTask scheduledTask = this.scheduleCronTask(cronTask);
            publishInstrumented(taskName, wrapped);
            ScheduledTaskEntry entry = new ScheduledTaskEntry(scheduledTask::cancel, "CRON", scheduleDesc,
                    retryPolicy, false, createdAt);
            taskMap.put(taskName, entry);
            log.info("Added cron task [{}] with expression [{}] in timezone [{}] and retry policy {}",
                    taskName, cron, zoneId, retryPolicy);
        } catch (Exception e) {
            // Rollback on failure
            taskMap.remove(taskName);
            throw e;
        }
    }

    /**
     * Adds a fixed-delay task with a retry policy.
     */
    public void addFixedDelayTask(String taskName, Duration interval, Duration initialDelay,
                                  Runnable runnable, RetryPolicy retryPolicy) {
        validateTaskParams(taskName, runnable);
        Assert.notNull(interval, "interval must not be null");
        Assert.isTrue(interval.compareTo(Duration.ZERO) > 0, "interval must be positive, was " + interval);
        Assert.notNull(initialDelay, "initialDelay must not be null");
        Assert.isTrue(!initialDelay.isNegative(), "initialDelay must not be negative, was " + initialDelay);
        Assert.notNull(retryPolicy, "retryPolicy must not be null");
        validateIntervalLimit(taskName, interval);

        // Reserve the name first with a placeholder to prevent execution before registration
        ScheduledTaskEntry placeholder = new ScheduledTaskEntry(() -> {}, "FIXED_DELAY",
                interval + "/" + initialDelay, retryPolicy, false, Instant.now());
        ScheduledTaskEntry existing = taskMap.putIfAbsent(taskName, placeholder);
        if (existing != null) {
            throw new TaskAlreadyExistsException(taskName);
        }

        try {
            Runnable wrapped = wrapRunnable(taskName, runnable, retryPolicy);
            FixedDelayTask fixedDelayTask = new FixedDelayTask(
                    wrapped, interval, initialDelay);
            ScheduledTask scheduledTask = this.scheduleFixedDelayTask(fixedDelayTask);
            publishInstrumented(taskName, wrapped);
            ScheduledTaskEntry entry = new ScheduledTaskEntry(scheduledTask::cancel, "FIXED_DELAY",
                    interval + "/" + initialDelay, retryPolicy, false, Instant.now());
            taskMap.put(taskName, entry);
            log.info("Added fixed-delay task [{}] with retry policy {}", taskName, retryPolicy);
        } catch (Exception e) {
            // Rollback on failure
            taskMap.remove(taskName);
            throw e;
        }
    }

    /**
     * Adds a fixed-rate task with a retry policy.
     */
    public void addFixedRateTask(String taskName, Duration interval, Duration initialDelay,
                                 Runnable runnable, RetryPolicy retryPolicy) {
        validateTaskParams(taskName, runnable);
        Assert.notNull(interval, "interval must not be null");
        Assert.isTrue(interval.compareTo(Duration.ZERO) > 0, "interval must be positive, was " + interval);
        Assert.notNull(initialDelay, "initialDelay must not be null");
        Assert.isTrue(!initialDelay.isNegative(), "initialDelay must not be negative, was " + initialDelay);
        Assert.notNull(retryPolicy, "retryPolicy must not be null");
        validateIntervalLimit(taskName, interval);

        // Reserve the name first with a placeholder to prevent execution before registration
        ScheduledTaskEntry placeholder = new ScheduledTaskEntry(() -> {}, "FIXED_RATE",
                interval + "/" + initialDelay, retryPolicy, false, Instant.now());
        ScheduledTaskEntry existing = taskMap.putIfAbsent(taskName, placeholder);
        if (existing != null) {
            throw new TaskAlreadyExistsException(taskName);
        }

        try {
            Runnable wrapped = wrapRunnable(taskName, runnable, retryPolicy);
            FixedRateTask fixedRateTask = new FixedRateTask(
                    wrapped, interval, initialDelay);
            ScheduledTask scheduledTask = this.scheduleFixedRateTask(fixedRateTask);
            publishInstrumented(taskName, wrapped);
            ScheduledTaskEntry entry = new ScheduledTaskEntry(scheduledTask::cancel, "FIXED_RATE",
                    interval + "/" + initialDelay, retryPolicy, false, Instant.now());
            taskMap.put(taskName, entry);
            log.info("Added fixed-rate task [{}] with retry policy {}", taskName, retryPolicy);
        } catch (Exception e) {
            // Rollback on failure
            taskMap.remove(taskName);
            throw e;
        }
    }

    // ─── Replace / AddOrUpdate ────────────────────────────────────────

    /**
     * Atomically replaces an existing cron task or adds a new one.
     *
     * @return {@code true} if an existing task was replaced, {@code false} if newly added
     */
    public boolean replaceCronTask(String taskName, String cron, Runnable runnable) {
        validateTaskParams(taskName, runnable);
        validateCronExpression(cron);

        pausedTasks.remove(taskName);
        // Schedule the new task BEFORE cancelling the old one. If scheduling
        // fails, the old entry remains in taskMap with its ScheduledTask still
        // active — never an un-fireable zombie referencing a cancelled future.
        // wrapRunnable is pure; the catch-up replay map is only repointed AFTER
        // the compute swap succeeded, so a failed replace keeps replaying the
        // OLD (still live) runnable.
        Runnable wrapped = wrapRunnable(taskName, runnable);
        CronTask cronTask = new CronTask(wrapped, cron);
        ScheduledTask scheduledTask = this.scheduleCronTask(cronTask);
        final boolean[] replaced = {false};
        try {
            taskMap.compute(taskName, (key, existing) -> {
                replaced[0] = (existing != null);
                if (existing != null) {
                    existing.cancelAction().run();
                }
                log.info("{} cron task [{}] with expression [{}]",
                        replaced[0] ? "Replaced" : "Added", taskName, cron);
                // Fresh schedule: reset expected-fire tracking so the first fire
                // after replace re-seeds the baseline instead of being judged
                // against the replaced cadence.
                nextExpectedFire.remove(taskName);
                return new ScheduledTaskEntry(scheduledTask::cancel, "CRON", cron);
            });
            publishInstrumented(taskName, wrapped);
        } catch (RuntimeException ex) {
            // If the compute lambda threw after the new task was already
            // scheduled, the new task would otherwise leak (taskMap doesn't
            // reference it, so destroy() can't cancel it). Hard-cancel it.
            try {
                scheduledTask.cancel();
            } catch (Exception cancelEx) {
                log.warn("Failed to cancel leaked schedule for [{}]: {}", taskName, cancelEx.getMessage());
            }
            throw ex;
        }
        return replaced[0];
    }

    /**
     * Atomically replaces an existing fixed-delay task or adds a new one.
     *
     * @return {@code true} if an existing task was replaced, {@code false} if newly added
     */
    public boolean replaceFixedDelayTask(String taskName, Duration interval, Duration initialDelay, Runnable runnable) {
        validateTaskParams(taskName, runnable);
        Assert.notNull(interval, "interval must not be null");
        Assert.isTrue(interval.compareTo(Duration.ZERO) > 0, "interval must be positive, was " + interval);
        Assert.notNull(initialDelay, "initialDelay must not be null");
        Assert.isTrue(!initialDelay.isNegative(), "initialDelay must not be negative, was " + initialDelay);
        validateIntervalLimit(taskName, interval);

        pausedTasks.remove(taskName);
        // Schedule the new task BEFORE cancelling the old one so a scheduling
        // failure cannot leave a zombie entry pointing at a cancelled future.
        // wrapRunnable is pure; publishInstrumented runs only after the swap.
        Runnable wrapped = wrapRunnable(taskName, runnable);
        FixedDelayTask fixedDelayTask = new FixedDelayTask(
                wrapped, interval, initialDelay);
        ScheduledTask scheduledTask = this.scheduleFixedDelayTask(fixedDelayTask);
        final boolean[] existed = {false};
        try {
            taskMap.compute(taskName, (key, existing) -> {
                existed[0] = (existing != null);
                if (existing != null) {
                    existing.cancelAction().run();
                }
                log.info("{} fixed-delay task [{}] interval={} initialDelay={}",
                        existed[0] ? "Replaced" : "Added", taskName, interval, initialDelay);
                // Fresh schedule: reset expected-fire tracking (see replaceCronTask).
                nextExpectedFire.remove(taskName);
                return new ScheduledTaskEntry(scheduledTask::cancel, "FIXED_DELAY",
                        interval + "/" + initialDelay);
            });
            publishInstrumented(taskName, wrapped);
        } catch (RuntimeException ex) {
            try {
                scheduledTask.cancel();
            } catch (Exception cancelEx) {
                log.warn("Failed to cancel leaked schedule for [{}]: {}", taskName, cancelEx.getMessage());
            }
            throw ex;
        }
        return existed[0];
    }

    /**
     * Atomically replaces an existing fixed-rate task or adds a new one.
     *
     * @return {@code true} if an existing task was replaced, {@code false} if newly added
     */
    public boolean replaceFixedRateTask(String taskName, Duration interval, Duration initialDelay, Runnable runnable) {
        validateTaskParams(taskName, runnable);
        Assert.notNull(interval, "interval must not be null");
        Assert.isTrue(interval.compareTo(Duration.ZERO) > 0, "interval must be positive, was " + interval);
        Assert.notNull(initialDelay, "initialDelay must not be null");
        Assert.isTrue(!initialDelay.isNegative(), "initialDelay must not be negative, was " + initialDelay);
        validateIntervalLimit(taskName, interval);

        pausedTasks.remove(taskName);
        // Schedule the new task BEFORE cancelling the old one so a scheduling
        // failure cannot leave a zombie entry pointing at a cancelled future.
        // wrapRunnable is pure; publishInstrumented runs only after the swap.
        Runnable wrapped = wrapRunnable(taskName, runnable);
        FixedRateTask fixedRateTask = new FixedRateTask(
                wrapped, interval, initialDelay);
        ScheduledTask scheduledTask = this.scheduleFixedRateTask(fixedRateTask);
        final boolean[] existed = {false};
        try {
            taskMap.compute(taskName, (key, existing) -> {
                existed[0] = (existing != null);
                if (existing != null) {
                    existing.cancelAction().run();
                }
                log.info("{} fixed-rate task [{}] interval={} initialDelay={}",
                        existed[0] ? "Replaced" : "Added", taskName, interval, initialDelay);
                // Fresh schedule: reset expected-fire tracking (see replaceCronTask).
                nextExpectedFire.remove(taskName);
                return new ScheduledTaskEntry(scheduledTask::cancel, "FIXED_RATE",
                        interval + "/" + initialDelay);
            });
            publishInstrumented(taskName, wrapped);
        } catch (RuntimeException ex) {
            try {
                scheduledTask.cancel();
            } catch (Exception cancelEx) {
                log.warn("Failed to cancel leaked schedule for [{}]: {}", taskName, cancelEx.getMessage());
            }
            throw ex;
        }
        return existed[0];
    }

    // ─── One-Shot Delayed Tasks ───────────────────────────────────────

    /**
     * Schedules a one-shot task that executes once after the given delay,
     * then auto-removes itself from the task map.
     *
     * @param taskName unique task name
     * @param delay    delay before execution
     * @param runnable the task to execute
     */
    public void schedule(String taskName, Duration delay, Runnable runnable) {
        validateTaskParams(taskName, runnable);
        Assert.notNull(delay, "delay must not be null");
        validateIntervalLimit(taskName, delay);

        // Reserve the name first with a placeholder to prevent execution before registration
        ScheduledTaskEntry placeholder = new ScheduledTaskEntry(() -> {}, "ONE_SHOT",
                "delay=" + delay, null, true, Instant.now());
        ScheduledTaskEntry existing = taskMap.putIfAbsent(taskName, placeholder);
        if (existing != null) {
            throw new TaskAlreadyExistsException(taskName);
        }

        try {
            Runnable wrapped = wrapOneShotRunnable(taskName, runnable);

            ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) this.getScheduler();
            ScheduledFuture<?> future = scheduler.schedule(wrapped, Instant.now().plus(delay));

            Runnable cancelAction = () -> {
                future.cancel(false);
                taskMap.remove(taskName);
            };
            ScheduledTaskEntry entry = new ScheduledTaskEntry(cancelAction, "ONE_SHOT",
                    "delay=" + delay, null, true, Instant.now());
            taskMap.put(taskName, entry);
            log.info("Scheduled one-shot task [{}] with delay [{}]", taskName, delay);
        } catch (Exception e) {
            // Rollback on failure
            taskMap.remove(taskName);
            throw e;
        }
    }

    // ─── Pause / Resume ───────────────────────────────────────────────

    /**
     * Pauses a task, preventing it from executing until resumed.
     * The task remains registered but will skip execution.
     * <p>
     * Records {@code pausedAt} on the entry so a later {@link #resume(String)} can
     * settle the pause gap with catch-up executions per the task's {@link MisfirePolicy}.
     *
     * @param taskName task name to pause
     */
    public void pause(String taskName) {
        Assert.hasText(taskName, "taskName must not be empty");
        if (!taskMap.containsKey(taskName)) {
            log.warn("Task [{}] not found, pause skipped", taskName);
            return;
        }
        pausedTasks.add(taskName);
        Instant pausedAt = Instant.now();
        taskMap.computeIfPresent(taskName, (key, current) -> withPausedAt(current, pausedAt));
        log.info("Paused task [{}]", taskName);
    }

    /**
     * Resumes a paused task, allowing it to execute again.
     * <p>
     * Misfire settlement: before the existing max-lifetime expiration check runs, the
     * pause gap ({@code pausedAt → now}) is settled according to the task's
     * {@link MisfirePolicy}:
     * <ul>
     *   <li>{@link MisfirePolicy#SKIP} — no catch-up executions;</li>
     *   <li>{@link MisfirePolicy#FIRE_ONCE} — one catch-up execution if the pause
     *       gap actually missed at least one firing, none otherwise;</li>
     *   <li>{@link MisfirePolicy#FIRE_IMMEDIATELY} — {@code min(missed, MAX_CATCH_UP)}
     *       catch-up executions, where {@code missed} is the number of firings that
     *       elapsed during the pause (gap/interval for fixed tasks, cron iteration
     *       for cron tasks).</li>
     * </ul>
     * Catch-up executions run serially on the scheduler thread through the full
     * instrumented pipeline (lock, listeners, history, metrics). If the task has
     * exceeded its max lifetime it is cancelled with NO catch-up. Re-pausing during
     * a catch-up burst aborts the remaining executions. One-shot tasks never catch up.
     *
     * @param taskName task name to resume
     */
    public void resume(String taskName) {
        Assert.hasText(taskName, "taskName must not be empty");
        ScheduledTaskEntry entry = taskMap.get(taskName);
        if (entry == null) {
            log.warn("Task [{}] not found, resume skipped", taskName);
            return;
        }
        // Settle the pause-gap catch-up plan BEFORE the expiration check so an
        // expired task is cancelled without any catch-up executions.
        int catchUpCount = computeCatchUpCount(taskName, entry);
        if (limitsChecker.isExpired(taskName, entry.createdAt())) {
            // Atomic check-and-remove: only cancel if this entry is still the one in the map.
            // A concurrent replaceXxxTask could have swapped it for a fresh entry; preserve that.
            if (cancelIfCurrent(taskName, entry)) {
                log.info("Task [{}] exceeded max lifetime during pause, cancelled instead of resumed", taskName);
            } else {
                log.debug("Task [{}] entry was replaced before resume could cancel; fresh entry preserved", taskName);
            }
            return;
        }
        if (pausedTasks.remove(taskName)) {
            // Clear pausedAt and capture the live entry identity for catch-up guarding.
            final ScheduledTaskEntry[] cleared = new ScheduledTaskEntry[1];
            taskMap.computeIfPresent(taskName, (key, current) -> {
                cleared[0] = withPausedAt(current, null);
                return cleared[0];
            });
            ScheduledTaskEntry live = cleared[0] != null ? cleared[0] : entry;
            if (!live.oneShot()) {
                // The pause gap has just been settled by catch-up: clear the tracker so
                // the first fire after resume re-seeds the baseline unjudged.
                resetExpectedFireOnResume(taskName);
            }
            if (catchUpCount > 0) {
                log.info("Resumed task [{}] with {} catch-up execution(s) per misfire policy {}",
                        taskName, catchUpCount, misfireConfigOrDefault(taskName).policy());
                scheduleCatchUp(taskName, live, catchUpCount);
            } else {
                log.info("Resumed task [{}]", taskName);
            }
        } else {
            log.warn("Task [{}] was not paused, resume skipped", taskName);
        }
    }

    /**
     * Checks whether a task is currently paused.
     *
     * @param taskName task name
     * @return {@code true} if the task is paused
     */
    public boolean isPaused(String taskName) {
        return pausedTasks.contains(taskName);
    }

    // ─── Cancel ──────────────────────────────────────────────────────

    public void cancel(String taskName) {
        Assert.hasText(taskName, "taskName must not be empty");
        ScheduledTaskEntry entry = taskMap.remove(taskName);
        if (entry != null) {
            entry.cancelAction().run();
            pausedTasks.remove(taskName);
            clearTaskTrackingState(taskName);
            log.info("Cancelled task [{}]", taskName);
        } else {
            log.warn("Task [{}] not found, cancel skipped", taskName);
        }
    }

    /**
     * Atomic check-and-remove: cancels the task only if the entry currently in the map
     * is still the supplied {@code expectedEntry}. Returns true if the task was cancelled.
     * Used by {@link #resume(String)} so a concurrent {@code replaceXxxTask} swapping
     * in a fresh entry is not accidentally cancelled by stale cancellation logic.
     */
    public boolean cancelIfCurrent(String taskName, ScheduledTaskEntry expectedEntry) {
        if (taskMap.remove(taskName, expectedEntry)) {
            expectedEntry.cancelAction().run();
            pausedTasks.remove(taskName);
            clearTaskTrackingState(taskName);
            log.info("Cancelled task [{}]", taskName);
            return true;
        }
        return false;
    }

    /**
     * Overrides the {@code createdAt} of an already-registered task. Used by
     * persistence-aware consumers (typically a startup restore listener) to
     * preserve a task's logical age across application restarts so the
     * {@code max-lifetime} ceiling still applies.
     * <p>
     * Uses {@link java.util.concurrent.ConcurrentHashMap#computeIfPresent(Object,
     * java.util.function.BiFunction) computeIfPresent} so a concurrent
     * {@link #cancel(String)} cannot resurrect a cancelled entry by racing
     * with a stale read. The contract is intentionally tolerant:
     * <ul>
     *   <li>If the task is not registered, this method is a no-op and logs at
     *       WARN level.</li>
     *   <li>If {@code createdAt} is {@code null}, this method is a silent no-op
     *       (the existing entry's {@code createdAt} is left untouched).</li>
     * </ul>
     *
     * @param taskName  the task name; empty/null is treated as "not found"
     * @param createdAt the instant the task should be considered as created; null is a silent no-op
     */
    public void setCreatedAt(String taskName, Instant createdAt) {
        if (taskName == null || taskName.isEmpty()) {
            log.warn("setCreatedAt called with empty taskName, skipped");
            return;
        }
        if (createdAt == null) {
            log.debug("setCreatedAt called with null createdAt for task [{}], skipped", taskName);
            return;
        }
        final boolean[] updated = {false};
        taskMap.computeIfPresent(taskName, (key, current) -> {
            updated[0] = true;
            // Preserve every other field of the entry; only the createdAt changes.
            return new ScheduledTaskEntry(
                    current.cancelAction(),
                    current.taskType(),
                    current.schedule(),
                    current.retryPolicy(),
                    current.oneShot(),
                    createdAt,
                    current.pausedAt());
        });
        if (!updated[0]) {
            log.warn("Cannot setCreatedAt for task [{}]: not registered", taskName);
        }
    }

    /**
     * Configures the misfire policy and lateness threshold for an already-registered
     * recurring task (CRON, FIXED_DELAY, FIXED_RATE). Mirrors the post-registration
     * wiring pattern of {@link #setCreatedAt(String, Instant)} and is the mechanism
     * {@link TaskBuilder#register(Runnable)} uses to make its fluent
     * {@code misfirePolicy}/{@code misfireThreshold} values reach the runtime.
     * <p>
     * Tasks without an explicit configuration are exempt from misfire detection and
     * pause-gap catch-up entirely (1.2.x behavior). ONE_SHOT tasks accept the call
     * but ignore the configuration at runtime. Calling this method (re-)seeds the
     * expected-fire baseline, so the first fire judged for lateness is the next one
     * after this call.
     * <p>
     * No-op with a WARN log if the task is not registered.
     *
     * @param taskName  the task name; must not be empty
     * @param policy    the misfire policy; must not be null
     * @param threshold lateness beyond which a fire is a misfire; must be positive
     */
    public void setMisfireConfig(String taskName, MisfirePolicy policy, Duration threshold) {
        Assert.hasText(taskName, "taskName must not be empty");
        Assert.notNull(policy, "misfirePolicy must not be null");
        Assert.notNull(threshold, "misfireThreshold must not be null");
        Assert.isTrue(threshold.compareTo(Duration.ZERO) > 0,
                "misfireThreshold must be positive, was " + threshold);
        ScheduledTaskEntry entry = taskMap.get(taskName);
        if (entry == null) {
            log.warn("Cannot set misfire config for task [{}]: not registered", taskName);
            return;
        }
        misfireConfigs.put(taskName, new MisfireConfig(policy, threshold));
        if (!entry.oneShot()) {
            // Clear the tracker: the next fire seeds a fresh baseline unjudged.
            nextExpectedFire.remove(taskName);
        }
    }

    // ─── Query API ───────────────────────────────────────────────────

    public boolean exists(String taskName) {
        return taskMap.containsKey(taskName);
    }

    public List<TaskInfo> listTasks() {
        List<TaskInfo> result = new ArrayList<>();
        for (Map.Entry<String, ScheduledTaskEntry> e : taskMap.entrySet()) {
            ScheduledTaskEntry entry = e.getValue();
            result.add(new TaskInfo(e.getKey(), entry.taskType(), entry.schedule()));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns detailed information about a specific task.
     *
     * @param taskName the task name
     * @return an {@link Optional} containing the task detail, or empty if not found
     */
    public Optional<TaskDetail> getTaskDetail(String taskName) {
        ScheduledTaskEntry entry = taskMap.get(taskName);
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(new TaskDetail(
                taskName, entry.taskType(), entry.schedule(),
                entry.oneShot(), entry.retryPolicy(), entry.createdAt(),
                pausedTasks.contains(taskName)));
    }

    // ─── Lifecycle ───────────────────────────────────────────────────

    @Override
    public void destroy() {
        log.info("Shutting down flex scheduled tasks, {} tasks remaining", taskMap.size());

        // Cancel all tasks first
        taskMap.values().forEach(entry -> {
            try {
                entry.cancelAction().run();
            } catch (Exception e) {
                log.warn("Error cancelling task: {}", e.getMessage());
            }
        });
        taskMap.clear();
        pausedTasks.clear();
        nextExpectedFire.clear();
        misfireConfigs.clear();
        instrumentedRunnables.clear();
        executingTasks.clear();

        // Shutdown the scheduler executor
        ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) this.getScheduler();
        if (scheduler != null) {
            var executor = scheduler.getScheduledThreadPoolExecutor();
            if (!executor.isShutdown()) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(awaitTerminationSeconds, TimeUnit.SECONDS)) {
                        log.warn("Scheduler did not terminate within {}s, forcing shutdown", awaitTerminationSeconds);
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Graceful shutdown interrupted", e);
                    executor.shutdownNow();
                }
            }
        }

        // Shutdown the shared timeout executor
        TimeoutExecutorManager.shutdown();

        shutdownOwnedAsyncListenerExecutor();

        // Note: We intentionally do NOT call super.destroy() here because:
        // 1. We've already manually shut down the executor above
        // 2. super.destroy() would attempt to shut down the scheduler again
        // 3. We've cleared our task maps, so there's nothing left for the parent to clean up
        log.info("Flex scheduled task registrar destroyed");
    }

    // ─── Internal ────────────────────────────────────────────────────

    /**
     * Shuts down the async-listener executor if (and only if) the registrar owns it.
     * External (caller-supplied) executors are not touched. The destroy path and
     * the setAsyncListenerExecutor replacement path converge here.
     */
    private synchronized void shutdownOwnedAsyncListenerExecutor() {
        if (!ownsAsyncListenerExecutor.compareAndSet(true, false)) {
            return;
        }
        ExecutorService local = this.asyncListenerExecutor;
        if (local == null || local.isShutdown()) {
            return;
        }
        local.shutdown();
        try {
            if (!local.awaitTermination(awaitTerminationSeconds, TimeUnit.SECONDS)) {
                log.warn("Async listener executor did not terminate within {}s, forcing shutdown",
                        awaitTerminationSeconds);
                local.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Graceful async listener shutdown interrupted", e);
            local.shutdownNow();
        }
    }

    private void validateTaskParams(String taskName, Runnable runnable) {
        Assert.hasText(taskName, "taskName must not be empty");
        Assert.notNull(runnable, "runnable must not be null");
    }

    /**
     * Validates a cron expression. Throws IllegalArgumentException if invalid.
     */
    private void validateCronExpression(String cron) {
        Assert.hasText(cron, "cron expression must not be empty");
        if (!CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("Invalid cron expression: " + cron);
        }
    }

    /**
     * Validates the task interval against the configured minimum trigger interval.
     * No-op when limits are disabled or no min-interval is configured.
     */
    private void validateIntervalLimit(String taskName, Duration interval) {
        limitsChecker.assertInterval(taskName, interval);
    }

    /**
     * Base instrumentation: adds listener lifecycle, metrics recording, execution history,
     * and distributed lock checking for cluster-aware scheduling.
     * Checks if task is paused before executing.
     */
    private Runnable instrument(String taskName, Runnable delegate) {
        return () -> {
            // 1. Lazy lifetime check
            ScheduledTaskEntry entry = taskMap.get(taskName);
            if (entry != null && limitsChecker.isExpired(taskName, entry.createdAt())) {
                // Atomic check-and-remove: only cancel if our entry reference is still the
                // one in the map. ConcurrentHashMap.remove(K, V) returns false if the
                // current value differs (e.g., compute() replaced via replaceXxxTask).
                if (taskMap.remove(taskName, entry)) {
                    entry.cancelAction().run();
                    pausedTasks.remove(taskName);
                    clearTaskTrackingState(taskName);
                    log.info("Cancelled task [{}]", taskName);
                }
                return;
            }

            // 2. Check if task is paused
            if (pausedTasks.contains(taskName)) {
                log.debug("Task [{}] is paused, skipping execution", taskName);
                return;
            }

            // 2a. Single-node overlap guard: Spring never fires the same periodic task
            // concurrently with itself, but a resume catch-up burst runs on its own
            // scheduler submission and CAN land on top of a regular fire. The task set
            // makes the two paths mutually exclusive by skipping the later arrival —
            // a skipped fire completes immediately and the cadence reschedules from it.
            if (!executingTasks.add(taskName)) {
                log.debug("Task [{}] is already executing on this node, skipping overlapping fire", taskName);
                return;
            }
            try {
                // 2b. Misfire detection (must precede lock acquisition so a skipped fire
                // never takes the distributed lock). SKIP returns without executing;
                // FIRE_ONCE/FIRE_IMMEDIATELY fall through and execute as normal.
                if (handleMisfire(taskName, Instant.now())) {
                    // The skipped fire "completes" now: rebase the completion-based
                    // baseline (FIXED_DELAY / CRON) so the next fire is judged against
                    // the cadence Spring actually schedules from this completion.
                    rebaseExpectedFireOnCompletion(taskName);
                    return;
                }

                // Check distributed lock for cluster-aware scheduling
                Duration lockDuration = resolveLockDuration(entry);
                if (!distributedLock.tryLock(taskName, lockDuration)) {
                    log.debug("Task [{}] could not acquire distributed lock, skipping execution", taskName);
                    // Lock-denied fires also "complete" immediately (see above).
                    rebaseExpectedFireOnCompletion(taskName);
                    return;
                }

                Instant startTime = Instant.now();
                long startNanos = System.nanoTime();
                String taskType = resolveTaskType(taskName);

                // Before execution
                for (TaskExecutionListener listener : listeners) {
                    if (listener.isAsync()) {
                        submitAsync(() -> {
                            try {
                                listener.beforeExecution(taskName);
                            } catch (Exception e) {
                                log.error("Async listener beforeExecution error for task [{}]: {}", taskName, e.getMessage(), e);
                            }
                        }, taskName, "beforeExecution");
                    } else {
                        try {
                            listener.beforeExecution(taskName);
                        } catch (Exception e) {
                            log.error("Listener beforeExecution error for task [{}]: {}", taskName, e.getMessage(), e);
                        }
                    }
                }

                try {
                    delegate.run();

                    // Success
                    Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
                    getMetricsRecorder().recordExecution(taskName, taskType, duration, true);
                    executionHistory.record(new ExecutionRecord(taskName, taskType, startTime, duration, true, null));
                    for (TaskExecutionListener listener : listeners) {
                        if (listener.isAsync()) {
                            submitAsync(() -> {
                                try {
                                    listener.afterExecution(taskName);
                                } catch (Exception e) {
                                    log.error("Async listener afterExecution error for task [{}]: {}", taskName, e.getMessage(), e);
                                }
                            }, taskName, "afterExecution");
                        } else {
                            try {
                                listener.afterExecution(taskName);
                            } catch (Exception e) {
                                log.error("Listener afterExecution error for task [{}]: {}", taskName, e.getMessage(), e);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Failure
                    Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
                    getMetricsRecorder().recordExecution(taskName, taskType, duration, false);
                    executionHistory.record(new ExecutionRecord(taskName, taskType, startTime, duration, false, e.getMessage()));
                    final Exception error = e;
                    for (TaskExecutionListener listener : listeners) {
                        if (listener.isAsync()) {
                            submitAsync(() -> {
                                try {
                                    listener.onError(taskName, error);
                                } catch (Exception ex) {
                                    log.error("Async listener onError error for task [{}]: {}", taskName, ex.getMessage(), ex);
                                }
                            }, taskName, "onError");
                        } else {
                            try {
                                listener.onError(taskName, e);
                            } catch (Exception ex) {
                                log.error("Listener onError error for task [{}]: {}", taskName, ex.getMessage(), ex);
                            }
                        }
                    }
                    throw e;
                } finally {
                    // Completion-based baseline (FIXED_DELAY / CRON): expected = next cadence
                    // point after the completion instant, matching how Spring schedules the
                    // next fire so delegate runtime never counts as lateness. Covers success
                    // AND exception exits.
                    rebaseExpectedFireOnCompletion(taskName);
                    distributedLock.unlock(taskName);
                }
            } finally {
                executingTasks.remove(taskName);
            }
        };
    }

    /**
     * Submit a task to the async-listener executor, gracefully absorbing
     * {@link java.util.concurrent.RejectedExecutionException} so a shutdown
     * or replacement of the listener executor does not abort a successful
     * task run or convert a recorded success into a spurious failure.
     */
    private void submitAsync(Runnable task, String taskName, String phase) {
        try {
            asyncListenerExecutor.submit(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("Async listener {} for task [{}] rejected (executor likely shut down): {}",
                    phase, taskName, e.getMessage());
        }
    }

    /**
     * Resolves the lock duration for a task. Uses the task's interval as the lock duration
     * to prevent overlapping executions across cluster nodes.
     */
    private Duration resolveLockDuration(ScheduledTaskEntry entry) {
        if (entry == null) {
            return Duration.ofSeconds(30);
        }
        String schedule = entry.schedule();
        // For fixed-delay/rate tasks, parse the interval from the schedule string
        if (schedule != null && schedule.contains("/")) {
            try {
                String intervalPart = schedule.substring(0, schedule.indexOf('/'));
                if (intervalPart.startsWith("PT")) {
                    return Duration.parse(intervalPart);
                }
            } catch (Exception ignored) {
                // Fall through to default
            }
        }
        // Default lock duration for cron and one-shot tasks
        return Duration.ofSeconds(30);
    }

    /**
     * Adds retry logic around an instrumented runnable.
     * Uses non-blocking rescheduling to avoid blocking scheduler threads.
     */
    private Runnable withRetry(Runnable delegate, RetryPolicy retryPolicy, String taskName) {
        return () -> {
            AtomicInteger attempt = new AtomicInteger(0);
            executeWithRetry(delegate, retryPolicy, taskName, attempt);
        };
    }

    /**
     * Executes the delegate with retry logic using non-blocking rescheduling.
     */
    private void executeWithRetry(Runnable delegate, RetryPolicy retryPolicy, String taskName, AtomicInteger attempt) {
        attempt.incrementAndGet();
        try {
            delegate.run();
            // success
        } catch (Exception e) {
            if (attempt.get() <= retryPolicy.maxAttempts()) {
                Duration delay = retryPolicy.computeDelay(attempt.get());
                log.warn("Task [{}] failed (attempt {}), scheduling retry in {}", taskName, attempt.get(), delay);

                // Schedule retry as a one-shot delayed task (non-blocking)
                ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) this.getScheduler();
                if (scheduler != null) {
                    scheduler.schedule(() -> executeWithRetry(delegate, retryPolicy, taskName, attempt),
                            Instant.now().plus(delay));
                } else {
                    // Fallback to blocking if scheduler is not available
                    log.warn("Scheduler not available, falling back to blocking retry for task [{}]", taskName);
                    try {
                        Thread.sleep(delay.toMillis());
                        executeWithRetry(delegate, retryPolicy, taskName, attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            } else {
                throw e;
            }
        }
    }

    /**
     * Adds auto-removal after execution for one-shot tasks.
     */
    private Runnable asOneShot(String taskName, Runnable delegate) {
        return () -> {
            try {
                delegate.run();
            } finally {
                taskMap.remove(taskName);
                clearTaskTrackingState(taskName);
                log.info("One-shot task [{}] completed and removed", taskName);
            }
        };
    }

    private String resolveTaskType(String taskName) {
        ScheduledTaskEntry entry = taskMap.get(taskName);
        return entry != null ? entry.taskType() : "UNKNOWN";
    }

    // ─── Misfire Handling ────────────────────────────────────────────

    /**
     * Runtime late-fire detection, invoked at the front of the instrument() pipeline
     * (after the paused check, before lock acquisition). Returns {@code true} when the
     * fire must be skipped (policy {@link MisfirePolicy#SKIP} and lateness strictly
     * greater than the configured threshold); returns {@code false} when execution
     * should proceed (on time, below-or-equal threshold, unconfigured task, one-shot
     * task, or policies FIRE_ONCE/FIRE_IMMEDIATELY which log + record but still run).
     * <p>
     * Also maintains the {@link #nextExpectedFire} tracker: the expected time is
     * advanced for the next cadence step on every judged fire, and the first judged
     * fire of a task only seeds the baseline (never flagged).
     *
     * @param taskName    the task name
     * @param actualStart the instant the fire actually started
     * @return {@code true} to skip this execution
     */
    private boolean handleMisfire(String taskName, Instant actualStart) {
        MisfireConfig config = misfireConfigs.get(taskName);
        if (config == null) {
            return false; // misfire handling not configured for this task (1.2.x behavior)
        }
        ScheduledTaskEntry entry = taskMap.get(taskName);
        if (entry == null || entry.oneShot()) {
            return false; // ONE_SHOT tasks ignore the misfire policy entirely
        }

        Instant expected = nextExpectedFire.get(taskName);
        advanceExpectedFire(taskName, entry, actualStart);
        if (expected == null) {
            return false; // first judged fire: baseline seeded, nothing to compare against
        }

        Duration lateness = Duration.between(expected, actualStart);
        if (lateness.compareTo(config.threshold()) <= 0) {
            return false; // strictly greater than threshold counts as a misfire
        }

        String taskType = entry.taskType();
        log.warn("Task [{}] fire is late by {} (expected {}, threshold {}) — misfire policy {}",
                taskName, lateness, expected, config.threshold(), config.policy());
        getMetricsRecorder().recordMisfire(taskName, taskType, config.policy());
        if (config.policy() == MisfirePolicy.SKIP) {
            executionHistory.record(new ExecutionRecord(taskName, taskType, actualStart,
                    Duration.ZERO, false,
                    "MISFIRE_SKIPPED: fire was late by " + lateness
                            + " (expected " + expected + ", threshold " + config.threshold() + ")"));
            return true;
        }
        return false; // FIRE_ONCE / FIRE_IMMEDIATELY: late fire still executes
    }

    /**
     * Advances the expected-fire tracker at fire start: FIXED_RATE grid-based
     * (advance by interval until strictly after {@code actualStart}), CRON next
     * boundary after {@code actualStart}. FIXED_DELAY is completion-based and only
     * seeded here when no baseline exists (see {@link #rebaseFixedDelayOnCompletion}).
     */
    private void advanceExpectedFire(String taskName, ScheduledTaskEntry entry, Instant actualStart) {
        switch (entry.taskType()) {
            case "FIXED_DELAY" -> {
                // Completion-based cadence (matches Spring's fixed-delay contract: the
                // next fire is scheduled at completion + interval). The baseline is
                // rebased from the completion instant on every fire exit path
                // (rebaseFixedDelayOnCompletion), so delegate runtime never counts as
                // lateness. Here we only seed when no baseline exists yet; the seed is
                // superseded by this fire's completion rebase.
                if (!nextExpectedFire.containsKey(taskName)) {
                    Duration interval = parseScheduleInterval(entry.schedule());
                    if (interval != null) {
                        nextExpectedFire.put(taskName, actualStart.plus(interval));
                    }
                }
            }
            case "FIXED_RATE" -> {
                Duration interval = parseScheduleInterval(entry.schedule());
                if (interval != null && !interval.isZero()) {
                    Instant next = nextExpectedFire.get(taskName);
                    if (next == null) {
                        next = actualStart;
                    }
                    if (!next.isAfter(actualStart)) {
                        // Arithmetic advance (O(1)): first grid point strictly after
                        // actualStart. An iteration loop here would run gap/interval
                        // times and could stall a scheduler thread for tiny intervals
                        // after a long thread-pool outage.
                        try {
                            long intervalNanos = interval.toNanos();
                            long behindNanos = Duration.between(next, actualStart).toNanos();
                            long steps = behindNanos / intervalNanos + 1;
                            next = next.plusNanos(Math.multiplyExact(steps, intervalNanos));
                        } catch (ArithmeticException overflow) {
                            // Absurd interval/gap magnitudes: re-seed from the actual start.
                            next = actualStart.plus(interval);
                        }
                    }
                    nextExpectedFire.put(taskName, next);
                }
            }
            case "CRON" -> {
                // Completion-based cadence (matches Spring's CronTrigger contract: the
                // next fire is computed from the completion instant). The baseline is
                // rebased on every fire exit path (rebaseExpectedFireOnCompletion), so a
                // delegate that runs past a cron boundary never counts as lateness.
                // Here we only seed when no baseline exists yet; the seed is superseded
                // by this fire's completion rebase.
                if (!nextExpectedFire.containsKey(taskName)) {
                    CronExpression cron = parseScheduleCron(entry.schedule());
                    if (cron != null) {
                        ZonedDateTime next = cron.next(actualStart.atZone(parseScheduleZone(entry.schedule())));
                        if (next != null) {
                            nextExpectedFire.put(taskName, next.toInstant());
                        }
                    }
                }
            }
            default -> { /* ONE_SHOT and unknown types are not tracked */ }
        }
    }

    /**
     * Rebases the expected-fire baseline from the completion instant for
     * completion-cadence task types, matching how Spring schedules the next fire:
     * <ul>
     *   <li>FIXED_DELAY — expected = completion + interval (Spring's fixed-delay
     *       contract: the next fire is scheduled after the previous completes);</li>
     *   <li>CRON — expected = first cron boundary strictly after completion
     *       (Spring's CronTrigger computes nextExecutionTime from the completion
     *       instant, so a delegate running past a boundary is on schedule, not late).</li>
     * </ul>
     * Called on every post-detection exit path of a fire — executed (delegate finally),
     * misfire-skipped, and lock-denied (the latter two "complete" immediately) — so
     * an on-schedule fire whose delegate runs longer than the misfire threshold is
     * never judged as late. No-op for unconfigured tasks; FIXED_RATE is grid-based
     * and advances at fire start instead (see {@link #advanceExpectedFire}).
     */
    private void rebaseExpectedFireOnCompletion(String taskName) {
        if (!misfireConfigs.containsKey(taskName)) {
            return;
        }
        ScheduledTaskEntry entry = taskMap.get(taskName);
        if (entry == null) {
            return;
        }
        Instant completion = Instant.now();
        switch (entry.taskType()) {
            case "FIXED_DELAY" -> {
                Duration interval = parseScheduleInterval(entry.schedule());
                if (interval != null) {
                    nextExpectedFire.put(taskName, completion.plus(interval));
                }
            }
            case "CRON" -> {
                CronExpression cron = parseScheduleCron(entry.schedule());
                if (cron != null) {
                    ZonedDateTime next = cron.next(completion.atZone(parseScheduleZone(entry.schedule())));
                    if (next != null) {
                        nextExpectedFire.put(taskName, next.toInstant());
                    }
                }
            }
            default -> { /* FIXED_RATE keeps its grid baseline; ONE_SHOT is not tracked */ }
        }
    }

    /**
     * Re-seeds the expected-fire baseline on resume: the pause gap has already been
     * settled by catch-up, so the tracker is cleared and the next fire seeds a fresh
     * baseline unjudged.
     */
    private void resetExpectedFireOnResume(String taskName) {
        nextExpectedFire.remove(taskName);
    }

    /**
     * Computes how many catch-up executions the pause gap ({@code pausedAt → now})
     * requires under the task's {@link MisfirePolicy}: SKIP → 0, FIRE_ONCE → 1 only
     * if the gap actually missed at least one firing ("fire once IF missed"),
     * FIRE_IMMEDIATELY → {@code min(missed, MAX_CATCH_UP)}. Returns 0 for one-shot
     * tasks, tasks without misfire configuration, tasks that are not paused, and
     * tasks without a recorded {@code pausedAt} (e.g. non-paused restored tasks —
     * the restart gap is NOT compensated).
     */
    private int computeCatchUpCount(String taskName, ScheduledTaskEntry entry) {
        MisfireConfig config = misfireConfigs.get(taskName);
        if (config == null || entry.oneShot() || entry.pausedAt() == null) {
            return 0;
        }
        if (!pausedTasks.contains(taskName)) {
            return 0;
        }
        if (config.policy() == MisfirePolicy.SKIP) {
            return 0;
        }
        Duration gap = Duration.between(entry.pausedAt(), Instant.now());
        if (gap.isNegative() || gap.isZero()) {
            return 0;
        }
        long missed = countMissedFirings(entry, gap);
        if (config.policy() == MisfirePolicy.FIRE_ONCE) {
            // A pause shorter than one cadence step missed nothing: no spurious
            // immediate execution (which would be an unrequested side effect).
            return missed > 0 ? 1 : 0;
        }
        return (int) Math.min(missed, MAX_CATCH_UP);
    }

    /**
     * Counts firings elapsed during {@code gap}: fixed tasks via long division
     * (saturated just above {@link #MAX_CATCH_UP} so huge gaps cannot overflow and
     * sub-millisecond intervals cannot divide by zero), cron by bounded iteration.
     */
    private long countMissedFirings(ScheduledTaskEntry entry, Duration gap) {
        return switch (entry.taskType()) {
            case "FIXED_DELAY", "FIXED_RATE" -> {
                Duration interval = parseScheduleInterval(entry.schedule());
                if (interval == null || interval.isZero() || interval.isNegative()) {
                    yield 0;
                }
                long missed;
                try {
                    // Duration.dividedBy is exact long division at nanosecond scale:
                    // no division-by-zero for sub-millisecond intervals, no int cast.
                    missed = gap.dividedBy(interval);
                } catch (ArithmeticException overflow) {
                    missed = MAX_CATCH_UP + 1L; // absurd magnitudes; the caller caps anyway
                }
                // Exact totals beyond the cap are irrelevant (the caller applies
                // Math.min with MAX_CATCH_UP); saturating here keeps the value int-safe.
                yield Math.min(missed, MAX_CATCH_UP + 1L);
            }
            case "CRON" -> {
                CronExpression cron = parseScheduleCron(entry.schedule());
                if (cron == null) {
                    yield 0;
                }
                ZoneId zone = parseScheduleZone(entry.schedule());
                Instant end = Instant.now();
                Instant cursor = entry.pausedAt();
                int count = 0;
                // Cap iteration slightly above MAX_CATCH_UP; exact totals beyond the cap are irrelevant.
                while (count <= MAX_CATCH_UP) {
                    ZonedDateTime nextZoned = cron.next(cursor.atZone(zone));
                    if (nextZoned == null) {
                        break;
                    }
                    Instant next = nextZoned.toInstant();
                    if (next.isAfter(end)) {
                        break;
                    }
                    count++;
                    cursor = next;
                }
                yield count;
            }
            default -> 0;
        };
    }

    /**
     * Runs {@code count} catch-up executions serially on the scheduler thread through
     * the full instrumented pipeline (lock, listeners, history, metrics). The burst
     * aborts when the task is re-paused, cancelled, or replaced mid-flight.
     */
    private void scheduleCatchUp(String taskName, ScheduledTaskEntry identity, int count) {
        Runnable wrapped = instrumentedRunnables.get(taskName);
        ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) this.getScheduler();
        if (wrapped == null || scheduler == null) {
            log.debug("Catch-up for task [{}] skipped: no instrumented runnable or scheduler", taskName);
            return;
        }
        try {
            scheduler.execute(() -> {
                for (int i = 0; i < count; i++) {
                    if (pausedTasks.contains(taskName)) {
                        log.info("Catch-up for task [{}] aborted after {} of {} execution(s): task re-paused",
                                taskName, i, count);
                        return;
                    }
                    if (taskMap.get(taskName) != identity) {
                        log.info("Catch-up for task [{}] aborted after {} of {} execution(s): task cancelled or replaced",
                                taskName, i, count);
                        return;
                    }
                    try {
                        wrapped.run();
                    } catch (Exception e) {
                        log.warn("Catch-up execution {} of {} for task [{}] failed: {}",
                                i + 1, count, taskName, e.getMessage());
                        return;
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("Catch-up for task [{}] rejected (scheduler likely shut down): {}", taskName, e.getMessage());
        }
    }

    private MisfireConfig misfireConfigOrDefault(String taskName) {
        MisfireConfig config = misfireConfigs.get(taskName);
        return config != null ? config
                : new MisfireConfig(TaskDefinition.DEFAULT_MISFIRE_POLICY, TaskDefinition.DEFAULT_MISFIRE_THRESHOLD);
    }

    /** Parses the interval (part before '/') from a fixed-task schedule string like "PT0.1S/PT0S". */
    private static Duration parseScheduleInterval(String schedule) {
        if (schedule == null) {
            return null;
        }
        int slash = schedule.indexOf('/');
        if (slash < 0) {
            return null;
        }
        try {
            return Duration.parse(schedule.substring(0, slash));
        } catch (Exception e) {
            return null;
        }
    }

    /** Extracts the cron expression from a cron schedule string ("expr" or "expr [zone]"). */
    private static String parseScheduleCronString(String schedule) {
        if (schedule == null) {
            return null;
        }
        int bracket = schedule.lastIndexOf(" [");
        String cron = (bracket > 0 && schedule.endsWith("]")) ? schedule.substring(0, bracket) : schedule;
        return CronExpression.isValidExpression(cron) ? cron : null;
    }

    private static CronExpression parseScheduleCron(String schedule) {
        String cron = parseScheduleCronString(schedule);
        return cron != null ? CronExpression.parse(cron) : null;
    }

    private static ZoneId parseScheduleZone(String schedule) {
        if (schedule != null) {
            int bracket = schedule.lastIndexOf(" [");
            if (bracket > 0 && schedule.endsWith("]")) {
                try {
                    return ZoneId.of(schedule.substring(bracket + 2, schedule.length() - 1));
                } catch (Exception ignored) {
                    // fall through to system default
                }
            }
        }
        return ZoneId.systemDefault();
    }

    /** Removes all misfire/tracking state for a task (cancel, expiry, one-shot completion, destroy). */
    private void clearTaskTrackingState(String taskName) {
        nextExpectedFire.remove(taskName);
        misfireConfigs.remove(taskName);
        instrumentedRunnables.remove(taskName);
    }

    private static ScheduledTaskEntry withPausedAt(ScheduledTaskEntry entry, Instant pausedAt) {
        return new ScheduledTaskEntry(entry.cancelAction(), entry.taskType(), entry.schedule(),
                entry.retryPolicy(), entry.oneShot(), entry.createdAt(), pausedAt);
    }

    /**
     * Wraps a runnable with instrumentation (listeners + metrics).
     * <p>
     * PURE: does not touch {@link #instrumentedRunnables} — call sites publish the
     * result via {@link #publishInstrumented} only AFTER scheduling succeeded, so a
     * failed add/replace can never leave the catch-up replay map pointing at a
     * runnable that was rejected (or leak an entry for a task that never existed).
     */
    private Runnable wrapRunnable(String taskName, Runnable delegate) {
        return instrument(taskName, delegate);
    }

    /**
     * Wraps a runnable with instrumentation and retry logic.
     * <p>
     * PURE: see {@link #wrapRunnable(String, Runnable)}.
     */
    private Runnable wrapRunnable(String taskName, Runnable delegate, RetryPolicy retryPolicy) {
        return withRetry(instrument(taskName, delegate), retryPolicy, taskName);
    }

    /**
     * Publishes the outermost wrapped runnable for resume catch-up replay.
     * Called only after the corresponding schedule/entry swap succeeded.
     */
    private void publishInstrumented(String taskName, Runnable wrapped) {
        instrumentedRunnables.put(taskName, wrapped);
    }

    /**
     * Wraps a runnable with instrumentation and auto-removal for one-shot tasks.
     */
    private Runnable wrapOneShotRunnable(String taskName, Runnable delegate) {
        return asOneShot(taskName, instrument(taskName, delegate));
    }

    // ─── Entry Record ────────────────────────────────────────────────

    public record ScheduledTaskEntry(
            Runnable cancelAction,
            String taskType,
            String schedule,
            RetryPolicy retryPolicy,
            boolean oneShot,
            Instant createdAt,
            Instant pausedAt
    ) {
        /** Backward-compatible constructor: pausedAt defaults to null. */
        public ScheduledTaskEntry(Runnable cancelAction, String taskType, String schedule,
                                  RetryPolicy retryPolicy, boolean oneShot, Instant createdAt) {
            this(cancelAction, taskType, schedule, retryPolicy, oneShot, createdAt, null);
        }

        ScheduledTaskEntry(Runnable cancelAction, String taskType, String schedule) {
            this(cancelAction, taskType, schedule, null, false, Instant.now(), null);
        }
    }

    /** Internal immutable misfire configuration for a task. */
    record MisfireConfig(MisfirePolicy policy, Duration threshold) {
    }
}
