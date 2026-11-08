package cn.wubo.flex.schedule.core;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Public API interface for flex task scheduling.
 * <p>
 * Consumers should inject this interface to manage scheduled tasks at runtime.
 * The default implementation is {@link DefaultFlexScheduledTaskService}.
 * </p>
 */
public interface FlexScheduledTaskService {

    // ─── Fluent Builder ───────────────────────────────────────────────

    /**
     * Creates a fluent builder for configuring and registering a task.
     * <p>
     * Example:
     * <pre>{@code
     * service.task("myTask")
     *     .cron("0 * * * * *")
     *     .timeout(Duration.ofSeconds(30))
     *     .retry(RetryPolicy.fixed(3, Duration.ofSeconds(1)))
     *     .register(() -> doWork());
     * }</pre>
     */
    TaskBuilder task(String taskName);

    // ─── Cron Tasks ──────────────────────────────────────────────────

    void add(String taskName, String cron, Runnable runnable);

    void add(String taskName, String cron, ZoneId zoneId, Runnable runnable);

    void add(String taskName, String cron, Duration timeout, Runnable runnable);

    void add(String taskName, String cron, Runnable runnable, RetryPolicy retryPolicy);

    void add(String taskName, String cron, ZoneId zoneId, RetryPolicy retryPolicy, Runnable runnable);

    // ─── Fixed Delay Tasks ────────────────────────────────────────────

    void addFixedDelayTask(String taskName, long interval, long initialDelay, Runnable runnable);

    void addFixedDelayTask(String taskName, Duration interval, Duration initialDelay, Runnable runnable);

    void addFixedDelayTask(String taskName, Duration interval, Duration initialDelay,
                           Duration timeout, Runnable runnable);

    void addFixedDelayTask(String taskName, Duration interval, Duration initialDelay,
                           Runnable runnable, RetryPolicy retryPolicy);

    // ─── Fixed Rate Tasks ─────────────────────────────────────────────

    void addFixedRateTask(String taskName, long interval, long initialDelay, Runnable runnable);

    void addFixedRateTask(String taskName, Duration interval, Duration initialDelay, Runnable runnable);

    void addFixedRateTask(String taskName, Duration interval, Duration initialDelay,
                          Duration timeout, Runnable runnable);

    void addFixedRateTask(String taskName, Duration interval, Duration initialDelay,
                          Runnable runnable, RetryPolicy retryPolicy);

    // ─── One-Shot Tasks ───────────────────────────────────────────────

    void schedule(String taskName, Duration delay, Runnable runnable);

    // ─── Replace / AddOrUpdate ────────────────────────────────────────

    boolean replaceCronTask(String taskName, String cron, Runnable runnable);

    boolean replaceFixedDelayTask(String taskName, Duration interval, Duration initialDelay, Runnable runnable);

    boolean replaceFixedRateTask(String taskName, Duration interval, Duration initialDelay, Runnable runnable);

    // ─── Task Lifecycle ───────────────────────────────────────────────

    void cancel(String taskName);

    void pause(String taskName);

    void resume(String taskName);

    boolean isPaused(String taskName);

    /**
     * Overrides the {@code createdAt} of an already-registered task. Used by
     * persistence-aware consumers (typically a startup restore listener) to
     * preserve a task's logical age across application restarts so that the
     * {@code max-lifetime} ceiling continues to apply.
     * <p>
     * No-op if the task is not registered or {@code createdAt} is {@code null}.
     * </p>
     *
     * @param taskName  the task name
     * @param createdAt the instant the task should be considered as created
     */
    void setCreatedAt(String taskName, Instant createdAt);

    /**
     * Configures the misfire policy and lateness threshold of an already-registered
     * recurring task (CRON, FIXED_DELAY, FIXED_RATE).
     * <p>
     * This is the runtime wiring behind {@link TaskBuilder#misfirePolicy(MisfirePolicy)}
     * and {@link TaskBuilder#misfireThreshold(Duration)}: {@code TaskBuilder.register()}
     * applies the configured values through this method right after dispatching the task
     * into the scheduler (the same post-registration pattern as {@link #setCreatedAt}).
     * ONE_SHOT tasks accept the configuration but ignore it at runtime.
     * </p>
     * <p>
     * Tasks that never receive a misfire configuration keep the pre-1.3.0 behavior:
     * no late-fire detection and no pause-gap catch-up.
     * </p>
     * <p>
     * Default implementation is a no-op so third-party implementations of this interface
     * remain source-compatible; {@link DefaultFlexScheduledTaskService} overrides it and
     * delegates to the registrar.
     * </p>
     *
     * @param taskName  the task name; must not be empty
     * @param policy    the misfire policy; must not be null
     * @param threshold lateness beyond which a fire is a misfire; must be positive
     */
    default void setMisfireConfig(String taskName, MisfirePolicy policy, Duration threshold) {
        // no-op by default; DefaultFlexScheduledTaskService wires this into the registrar
    }

    // ─── Query ────────────────────────────────────────────────────────

    boolean exists(String taskName);

    List<TaskInfo> listTasks();

    Optional<TaskDetail> getTaskDetail(String taskName);

    // ─── Listeners ────────────────────────────────────────────────────

    void addListener(TaskExecutionListener listener);

    void removeListener(TaskExecutionListener listener);

    // ─── Execution History ────────────────────────────────────────────

    void setExecutionHistory(ExecutionHistory executionHistory);

    List<ExecutionRecord> getExecutionHistory(String taskName, int limit);

    List<ExecutionRecord> getAllExecutionHistory(int limit);

    void clearExecutionHistory(String taskName);

    // ─── Statistics ───────────────────────────────────────────────────

    /**
     * Returns execution statistics for a specific task.
     *
     * @param taskName the task name
     * @return task statistics, or empty if the task has no execution history
     */
    Optional<TaskStatistics> getTaskStatistics(String taskName);

    /**
     * Returns execution statistics for all tasks.
     *
     * @return list of task statistics
     */
    List<TaskStatistics> getAllTaskStatistics();

    // ─── Distributed Lock ─────────────────────────────────────────────

    void setDistributedLock(DistributedLock distributedLock);
}
