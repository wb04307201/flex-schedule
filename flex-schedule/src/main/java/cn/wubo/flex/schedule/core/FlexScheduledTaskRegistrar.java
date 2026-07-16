package cn.wubo.flex.schedule.core;

import cn.wubo.flex.schedule.exception.TaskAlreadyExistsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.*;
import org.springframework.util.Assert;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.support.CronExpression;

@Slf4j
public class FlexScheduledTaskRegistrar extends ScheduledTaskRegistrar {

    private final Map<String, ScheduledTaskEntry> taskMap = new ConcurrentHashMap<>(16);
    private final Set<String> pausedTasks = ConcurrentHashMap.newKeySet();
    private final List<TaskExecutionListener> listeners = new CopyOnWriteArrayList<>();
    private final long awaitTerminationSeconds;
    private final AtomicReference<MetricsRecorder> metricsRecorder = new AtomicReference<>(MetricsRecorder.NOOP);
    private volatile ExecutionHistory executionHistory = ExecutionHistory.NOOP;
    private volatile DistributedLock distributedLock = DistributedLock.NOOP;
    private volatile TaskRepository taskRepository = new InMemoryTaskRepository();
    private final LimitsChecker limitsChecker;
    private volatile ExecutorService asyncListenerExecutor = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "async-listener");
        t.setDaemon(true);
        return t;
    });

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
     * Sets the executor for async listeners.
     * Defaults to a cached thread pool.
     *
     * @param executor the executor to use for async listener invocation
     */
    public void setAsyncListenerExecutor(ExecutorService executor) {
        this.asyncListenerExecutor = executor != null ? executor : java.util.concurrent.Executors.newCachedThreadPool();
    }

    /**
     * Restores tasks from the repository on startup.
     * This method should be called after the registrar is fully initialized.
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
                    ScheduledTask scheduledTask = scheduleByType(def, runnable);
                    ScheduledTaskEntry entry = new ScheduledTaskEntry(scheduledTask::cancel,
                            def.taskType(), describeSchedule(def), def.retryPolicy(), false, createdAt);
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
        Runnable wrapped = def.retryPolicy() != null
                ? wrapRunnable(def.taskName(), runnable, def.retryPolicy())
                : wrapRunnable(def.taskName(), runnable);
        return switch (def.taskType()) {
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
            CronTask cronTask = new CronTask(wrapRunnable(taskName, runnable), cron);
            ScheduledTask scheduledTask = this.scheduleCronTask(cronTask);
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
            CronTask cronTask = new CronTask(wrapRunnable(taskName, runnable), trigger);
            ScheduledTask scheduledTask = this.scheduleCronTask(cronTask);
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
        Assert.notNull(initialDelay, "initialDelay must not be null");
        validateIntervalLimit(taskName, interval);

        // Reserve the name first with a placeholder to prevent execution before registration
        ScheduledTaskEntry placeholder = new ScheduledTaskEntry(() -> {}, "FIXED_DELAY",
                interval + "/" + initialDelay);
        ScheduledTaskEntry existing = taskMap.putIfAbsent(taskName, placeholder);
        if (existing != null) {
            throw new TaskAlreadyExistsException(taskName);
        }

        try {
            FixedDelayTask fixedDelayTask = new FixedDelayTask(
                    wrapRunnable(taskName, runnable), interval, initialDelay);
            ScheduledTask scheduledTask = this.scheduleFixedDelayTask(fixedDelayTask);
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
        Assert.notNull(initialDelay, "initialDelay must not be null");
        validateIntervalLimit(taskName, interval);

        // Reserve the name first with a placeholder to prevent execution before registration
        ScheduledTaskEntry placeholder = new ScheduledTaskEntry(() -> {}, "FIXED_RATE",
                interval + "/" + initialDelay);
        ScheduledTaskEntry existing = taskMap.putIfAbsent(taskName, placeholder);
        if (existing != null) {
            throw new TaskAlreadyExistsException(taskName);
        }

        try {
            FixedRateTask fixedRateTask = new FixedRateTask(
                    wrapRunnable(taskName, runnable), interval, initialDelay);
            ScheduledTask scheduledTask = this.scheduleFixedRateTask(fixedRateTask);
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
            CronTask cronTask = new CronTask(wrapRunnable(taskName, runnable, retryPolicy), cron);
            ScheduledTask scheduledTask = this.scheduleCronTask(cronTask);
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
     * Adds a fixed-delay task with a retry policy.
     */
    public void addFixedDelayTask(String taskName, Duration interval, Duration initialDelay,
                                  Runnable runnable, RetryPolicy retryPolicy) {
        validateTaskParams(taskName, runnable);
        Assert.notNull(interval, "interval must not be null");
        Assert.notNull(initialDelay, "initialDelay must not be null");
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
            FixedDelayTask fixedDelayTask = new FixedDelayTask(
                    wrapRunnable(taskName, runnable, retryPolicy), interval, initialDelay);
            ScheduledTask scheduledTask = this.scheduleFixedDelayTask(fixedDelayTask);
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
        Assert.notNull(initialDelay, "initialDelay must not be null");
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
            FixedRateTask fixedRateTask = new FixedRateTask(
                    wrapRunnable(taskName, runnable, retryPolicy), interval, initialDelay);
            ScheduledTask scheduledTask = this.scheduleFixedRateTask(fixedRateTask);
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

        final boolean[] replaced = {false};
        taskMap.compute(taskName, (key, existing) -> {
            replaced[0] = (existing != null);
            // Cancel existing task if present
            if (existing != null) {
                existing.cancelAction().run();
            }

            // Schedule new task
            CronTask cronTask = new CronTask(wrapRunnable(taskName, runnable), cron);
            ScheduledTask scheduledTask = this.scheduleCronTask(cronTask);
            log.info("{} cron task [{}] with expression [{}]",
                    replaced[0] ? "Replaced" : "Added", taskName, cron);
            return new ScheduledTaskEntry(scheduledTask::cancel, "CRON", cron);
        });
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
        Assert.notNull(initialDelay, "initialDelay must not be null");
        validateIntervalLimit(taskName, interval);

        final boolean[] existed = {false};
        taskMap.compute(taskName, (key, existing) -> {
            existed[0] = (existing != null);
            if (existing != null) {
                existing.cancelAction().run();
            }

            FixedDelayTask fixedDelayTask = new FixedDelayTask(
                    wrapRunnable(taskName, runnable), interval, initialDelay);
            ScheduledTask scheduledTask = this.scheduleFixedDelayTask(fixedDelayTask);
            log.info("{} fixed-delay task [{}] interval={} initialDelay={}",
                    existed[0] ? "Replaced" : "Added", taskName, interval, initialDelay);
            return new ScheduledTaskEntry(scheduledTask::cancel, "FIXED_DELAY",
                    interval + "/" + initialDelay);
        });
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
        Assert.notNull(initialDelay, "initialDelay must not be null");
        validateIntervalLimit(taskName, interval);

        final boolean[] existed = {false};
        taskMap.compute(taskName, (key, existing) -> {
            existed[0] = (existing != null);
            if (existing != null) {
                existing.cancelAction().run();
            }

            FixedRateTask fixedRateTask = new FixedRateTask(
                    wrapRunnable(taskName, runnable), interval, initialDelay);
            ScheduledTask scheduledTask = this.scheduleFixedRateTask(fixedRateTask);
            log.info("{} fixed-rate task [{}] interval={} initialDelay={}",
                    existed[0] ? "Replaced" : "Added", taskName, interval, initialDelay);
            return new ScheduledTaskEntry(scheduledTask::cancel, "FIXED_RATE",
                    interval + "/" + initialDelay);
        });
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
        log.info("Paused task [{}]", taskName);
    }

    /**
     * Resumes a paused task, allowing it to execute again.
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
            log.info("Resumed task [{}]", taskName);
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
     * No-op if the task is not registered or {@code createdAt} is {@code null}.
     * Does not reschedule or otherwise modify the trigger; only the metadata
     * stamped on the {@link ScheduledTaskEntry} changes.
     * </p>
     *
     * @param taskName  the task name
     * @param createdAt the instant the task should be considered as created
     */
    public void setCreatedAt(String taskName, Instant createdAt) {
        if (createdAt == null) {
            return;
        }
        ScheduledTaskEntry entry = taskMap.get(taskName);
        if (entry == null) {
            log.warn("Cannot setCreatedAt for task [{}]: not registered", taskName);
            return;
        }
        taskMap.put(taskName, new ScheduledTaskEntry(
                entry.cancelAction(),
                entry.taskType(),
                entry.schedule(),
                entry.retryPolicy(),
                entry.oneShot(),
                createdAt));
        log.info("Updated createdAt of task [{}] to {}", taskName, createdAt);
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

        // Note: We intentionally do NOT call super.destroy() here because:
        // 1. We've already manually shut down the executor above
        // 2. super.destroy() would attempt to shut down the scheduler again
        // 3. We've cleared our task maps, so there's nothing left for the parent to clean up
        log.info("Flex scheduled task registrar destroyed");
    }

    // ─── Internal ────────────────────────────────────────────────────

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
                    log.info("Cancelled task [{}]", taskName);
                }
                return;
            }

            // 2. Check if task is paused
            if (pausedTasks.contains(taskName)) {
                log.debug("Task [{}] is paused, skipping execution", taskName);
                return;
            }

            // Check distributed lock for cluster-aware scheduling
            Duration lockDuration = resolveLockDuration(entry);
            if (!distributedLock.tryLock(taskName, lockDuration)) {
                log.debug("Task [{}] could not acquire distributed lock, skipping execution", taskName);
                return;
            }

            Instant startTime = Instant.now();
            long startNanos = System.nanoTime();
            String taskType = resolveTaskType(taskName);

            // Before execution
            for (TaskExecutionListener listener : listeners) {
                if (listener.isAsync()) {
                    asyncListenerExecutor.submit(() -> {
                        try {
                            listener.beforeExecution(taskName);
                        } catch (Exception e) {
                            log.error("Async listener beforeExecution error for task [{}]: {}", taskName, e.getMessage(), e);
                        }
                    });
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
                        asyncListenerExecutor.submit(() -> {
                            try {
                                listener.afterExecution(taskName);
                            } catch (Exception e) {
                                log.error("Async listener afterExecution error for task [{}]: {}", taskName, e.getMessage(), e);
                            }
                        });
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
                        asyncListenerExecutor.submit(() -> {
                            try {
                                listener.onError(taskName, error);
                            } catch (Exception ex) {
                                log.error("Async listener onError error for task [{}]: {}", taskName, ex.getMessage(), ex);
                            }
                        });
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
                distributedLock.unlock(taskName);
            }
        };
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
                log.info("One-shot task [{}] completed and removed", taskName);
            }
        };
    }

    private String resolveTaskType(String taskName) {
        ScheduledTaskEntry entry = taskMap.get(taskName);
        return entry != null ? entry.taskType() : "UNKNOWN";
    }

    /**
     * Wraps a runnable with instrumentation (listeners + metrics).
     */
    private Runnable wrapRunnable(String taskName, Runnable delegate) {
        return instrument(taskName, delegate);
    }

    /**
     * Wraps a runnable with instrumentation and retry logic.
     */
    private Runnable wrapRunnable(String taskName, Runnable delegate, RetryPolicy retryPolicy) {
        return withRetry(instrument(taskName, delegate), retryPolicy, taskName);
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
            Instant createdAt
    ) {
        ScheduledTaskEntry(Runnable cancelAction, String taskType, String schedule) {
            this(cancelAction, taskType, schedule, null, false, Instant.now());
        }
    }
}
