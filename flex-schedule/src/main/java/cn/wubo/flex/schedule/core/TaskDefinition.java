package cn.wubo.flex.schedule.core;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * Immutable definition of a scheduled task for persistence.
 * Contains all information needed to recreate a task after application restart.
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
        Instant updatedAt
) {
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

        public Builder(String taskName, String taskType) {
            this.taskName = taskName;
            this.taskType = taskType;
            this.createdAt = Instant.now();
            this.updatedAt = Instant.now();
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

        public TaskDefinition build() {
            return new TaskDefinition(taskName, taskType, cronExpression, timezone,
                    interval, initialDelay, delay, retryPolicy, timeout,
                    beanName, methodName, methodParams, paused, createdAt, updatedAt);
        }
    }
}
