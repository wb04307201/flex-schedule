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
