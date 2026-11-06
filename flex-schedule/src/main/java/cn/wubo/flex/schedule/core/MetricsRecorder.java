package cn.wubo.flex.schedule.core;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Internal interface for recording task execution metrics.
 * A {@link #NOOP} instance is used when no metrics implementation is available.
 */
public interface MetricsRecorder {

    /**
     * Records a single task execution.
     *
     * @param taskName the task name
     * @param taskType CRON, FIXED_DELAY, FIXED_RATE, or ONE_SHOT
     * @param duration execution duration
     * @param success  whether the execution succeeded
     */
    void recordExecution(String taskName, String taskType, Duration duration, boolean success);

    /**
     * Registers a supplier for the current active task count (used for gauge metrics).
     */
    void setActiveTaskCountSupplier(Supplier<Integer> supplier);

    /**
     * Records a misfire event: a recurring task's fire was late beyond its
     * configured misfire threshold. Depending on the task's {@link MisfirePolicy}
     * the execution was either skipped ({@link MisfirePolicy#SKIP}) or still
     * carried out ({@link MisfirePolicy#FIRE_ONCE}, {@link MisfirePolicy#FIRE_IMMEDIATELY}).
     * <p>
     * Default no-op so existing implementations remain binary-compatible.
     *
     * @param taskName the task name
     * @param taskType CRON, FIXED_DELAY, or FIXED_RATE
     * @param policy   the misfire policy in effect for the task
     */
    default void recordMisfire(String taskName, String taskType, MisfirePolicy policy) {
        // no-op by default (binary-compatible extension)
    }

    /** No-op implementation used when Micrometer is not on the classpath. */
    MetricsRecorder NOOP = new MetricsRecorder() {
        @Override
        public void recordExecution(String taskName, String taskType, Duration duration, boolean success) {
            // no-op
        }

        @Override
        public void setActiveTaskCountSupplier(Supplier<Integer> supplier) {
            // no-op
        }
    };
}
