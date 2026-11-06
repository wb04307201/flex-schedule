package cn.wubo.flex.schedule.core;

import org.springframework.util.Assert;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * Immutable definition of a scheduled task for persistence.
 * Contains all information needed to recreate a task after application restart.
 *
 * @param misfirePolicy     policy for handling missed executions of recurring tasks
 * @param misfireThreshold  lateness beyond which a fire is considered a misfire (must be positive)
 */
public record TaskDefinition(
        String taskName,
        String taskType,
        String cronExpression,
        ZoneId timezone,
        Duration interval,
        Duration initialDelay,
        Duration delay,
        RetryPolicy retryPolicy,
        Duration timeout,
        String beanName,
        String methodName,
        List<Object> methodParams,
        boolean paused,
        Instant createdAt,
        Instant updatedAt,
        MisfirePolicy misfirePolicy,
        Duration misfireThreshold
) {

    /**
     * Default misfire policy applied when none is configured: a late trigger
     * fires normally, and a pause-gap catch-up runs at most one execution.
     */
    public static final MisfirePolicy DEFAULT_MISFIRE_POLICY = MisfirePolicy.FIRE_ONCE;

    /**
     * Default lateness threshold (30 seconds) beyond which a trigger is
     * considered misfired.
     */
    public static final Duration DEFAULT_MISFIRE_THRESHOLD = Duration.ofSeconds(30);

    /**
     * Compact constructor normalizing the misfire fields: a {@code null} policy
     * falls back to {@link #DEFAULT_MISFIRE_POLICY} and a {@code null} threshold
     * falls back to {@link #DEFAULT_MISFIRE_THRESHOLD} — 1.2.x-persisted payloads
     * (e.g. JSON without either field, deserialized with nulls) must keep loading
     * instead of failing the constructor and being skipped by restoreTasks().
     * An explicitly supplied threshold must still be strictly positive.
     */
    public TaskDefinition {
        if (misfirePolicy == null) {
            misfirePolicy = DEFAULT_MISFIRE_POLICY;
        }
        if (misfireThreshold == null) {
            misfireThreshold = DEFAULT_MISFIRE_THRESHOLD;
        }
        Assert.isTrue(misfireThreshold.compareTo(Duration.ZERO) > 0,
                "misfireThreshold must be positive, was " + misfireThreshold);
    }

    /**
     * Backward-compatible 15-argument constructor retained for callers compiled
     * against 1.2.x. Delegates with the default misfire settings.
     */
    public TaskDefinition(
            String taskName,
            String taskType,
            String cronExpression,
            ZoneId timezone,
            Duration interval,
            Duration initialDelay,
            Duration delay,
            RetryPolicy retryPolicy,
            Duration timeout,
            String beanName,
            String methodName,
            List<Object> methodParams,
            boolean paused,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(taskName, taskType, cronExpression, timezone, interval, initialDelay, delay,
                retryPolicy, timeout, beanName, methodName, methodParams, paused, createdAt,
                updatedAt, DEFAULT_MISFIRE_POLICY, DEFAULT_MISFIRE_THRESHOLD);
    }

    /**
     * Creates a builder for TaskDefinition.
     */
    public static Builder builder(String taskName, String taskType) {
        return new Builder(taskName, taskType);
    }

    public static class Builder {
        private final String taskName;
        private final String taskType;
        private String cronExpression;
        private ZoneId timezone;
        private Duration interval;
        private Duration initialDelay;
        private Duration delay;
        private RetryPolicy retryPolicy;
        private Duration timeout;
        private String beanName;
        private String methodName;
        private List<Object> methodParams;
        private boolean paused;
        private Instant createdAt;
        private Instant updatedAt;
        private MisfirePolicy misfirePolicy;
        private Duration misfireThreshold;

        public Builder(String taskName, String taskType) {
            this.taskName = taskName;
            this.taskType = taskType;
            this.createdAt = Instant.now();
            this.updatedAt = Instant.now();
            this.misfirePolicy = DEFAULT_MISFIRE_POLICY;
            this.misfireThreshold = DEFAULT_MISFIRE_THRESHOLD;
        }

        public Builder cronExpression(String cronExpression) { this.cronExpression = cronExpression; return this; }
        public Builder timezone(ZoneId timezone) { this.timezone = timezone; return this; }
        public Builder interval(Duration interval) { this.interval = interval; return this; }
        public Builder initialDelay(Duration initialDelay) { this.initialDelay = initialDelay; return this; }
        public Builder delay(Duration delay) { this.delay = delay; return this; }
        public Builder retryPolicy(RetryPolicy retryPolicy) { this.retryPolicy = retryPolicy; return this; }
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }
        public Builder beanName(String beanName) { this.beanName = beanName; return this; }
        public Builder methodName(String methodName) { this.methodName = methodName; return this; }
        public Builder methodParams(List<Object> methodParams) { this.methodParams = methodParams; return this; }
        public Builder paused(boolean paused) { this.paused = paused; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public Builder misfirePolicy(MisfirePolicy misfirePolicy) {
            Assert.notNull(misfirePolicy, "misfirePolicy must not be null");
            this.misfirePolicy = misfirePolicy;
            return this;
        }

        public Builder misfireThreshold(Duration misfireThreshold) {
            Assert.notNull(misfireThreshold, "misfireThreshold must not be null");
            this.misfireThreshold = misfireThreshold;
            return this;
        }

        public TaskDefinition build() {
            return new TaskDefinition(taskName, taskType, cronExpression, timezone,
                    interval, initialDelay, delay, retryPolicy, timeout,
                    beanName, methodName, methodParams, paused, createdAt, updatedAt,
                    misfirePolicy, misfireThreshold);
        }
    }
}
