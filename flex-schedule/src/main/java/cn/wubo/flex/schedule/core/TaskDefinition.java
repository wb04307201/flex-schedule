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
 * @param misfirePolicy     policy for handling missed executions of recurring tasks;
 *                          {@code null} (together with a {@code null} threshold) means
 *                          misfire handling is NOT configured — the task keeps the
 *                          pre-1.3.0 behavior. This is the shape of 1.2.x-persisted
 *                          payloads and must survive deserialization unchanged.
 * @param misfireThreshold  lateness beyond which a fire is considered a misfire;
 *                          must be positive when supplied. {@code null} means
 *                          unconfigured (see {@code misfirePolicy}).
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
     * Default misfire policy applied when a definition opts into misfire
     * handling without choosing a policy explicitly.
     */
    public static final MisfirePolicy DEFAULT_MISFIRE_POLICY = MisfirePolicy.FIRE_ONCE;

    /**
     * Default lateness threshold (30 seconds) applied when a definition opts
     * into misfire handling without choosing a threshold explicitly.
     */
    public static final Duration DEFAULT_MISFIRE_THRESHOLD = Duration.ofSeconds(30);

    /**
     * Compact constructor implementing the opt-in misfire contract:
     * <ul>
     *   <li>both misfire fields {@code null} — the shape of 1.2.x-persisted
     *       payloads (JSON without either field, deserialized with nulls) — keeps
     *       them {@code null}, meaning "misfire handling not configured". Such
     *       definitions load fine and are restored by
     *       {@code FlexScheduledTaskRegistrar.restoreTasks()} with pre-1.3.0
     *       behavior (no late-fire detection, no pause-gap catch-up).</li>
     *   <li>either field non-{@code null} — the definition opts in: a missing
     *       side falls back to {@link #DEFAULT_MISFIRE_POLICY} /
     *       {@link #DEFAULT_MISFIRE_THRESHOLD}, and an explicitly supplied
     *       threshold must be strictly positive.</li>
     * </ul>
     */
    public TaskDefinition {
        if (misfirePolicy != null || misfireThreshold != null) {
            if (misfirePolicy == null) {
                misfirePolicy = DEFAULT_MISFIRE_POLICY;
            }
            if (misfireThreshold == null) {
                misfireThreshold = DEFAULT_MISFIRE_THRESHOLD;
            }
            Assert.isTrue(misfireThreshold.compareTo(Duration.ZERO) > 0,
                    "misfireThreshold must be positive, was " + misfireThreshold);
        }
    }

    /**
     * Backward-compatible 15-argument constructor retained for callers compiled
     * against 1.2.x. Delegates with both misfire fields {@code null} — callers
     * from the pre-misfire era keep the pre-1.3.0 behavior (exempt), matching
     * what 1.2.x semantics promised.
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
                updatedAt, null, null);
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
            // misfirePolicy / misfireThreshold stay null (= misfire handling not
            // configured) unless the caller opts in explicitly.
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
