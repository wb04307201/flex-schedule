package cn.wubo.flex.schedule.core;

import java.time.Duration;
import java.time.ZoneId;

/**
 * Fluent builder for creating and registering scheduled tasks.
 * <p>
 * Example usage:
 * <pre>{@code
 * service.task("myTask")
 *     .cron("0 * * * * *")
 *     .timezone(ZoneId.of("UTC"))
 *     .timeout(Duration.ofSeconds(30))
 *     .retry(RetryPolicy.fixed(3, Duration.ofSeconds(1)))
 *     .register(() -> doWork());
 * }</pre>
 */
public class TaskBuilder {

    private final FlexScheduledTaskService service;
    private final String taskName;
    private String cron;
    private ZoneId timezone;
    private Duration timeout;
    private RetryPolicy retryPolicy;
    private Duration fixedDelay;
    private Duration fixedRate;
    private Duration initialDelay = Duration.ZERO;
    private Duration oneShotDelay;

    TaskBuilder(FlexScheduledTaskService service, String taskName) {
        this.service = service;
        this.taskName = taskName;
    }

    /**
     * Sets the cron expression for the task.
     */
    public TaskBuilder cron(String cron) {
        this.cron = cron;
        return this;
    }

    /**
     * Sets the timezone for cron evaluation.
     */
    public TaskBuilder timezone(ZoneId timezone) {
        this.timezone = timezone;
        return this;
    }

    /**
     * Sets the execution timeout.
     */
    public TaskBuilder timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * Sets the retry policy.
     */
    public TaskBuilder retry(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
        return this;
    }

    /**
     * Sets the task as a fixed-delay task.
     */
    public TaskBuilder fixedDelay(Duration interval) {
        this.fixedDelay = interval;
        return this;
    }

    /**
     * Sets the task as a fixed-delay task with initial delay.
     */
    public TaskBuilder fixedDelay(Duration interval, Duration initialDelay) {
        this.fixedDelay = interval;
        this.initialDelay = initialDelay;
        return this;
    }

    /**
     * Sets the task as a fixed-rate task.
     */
    public TaskBuilder fixedRate(Duration interval) {
        this.fixedRate = interval;
        return this;
    }

    /**
     * Sets the task as a fixed-rate task with initial delay.
     */
    public TaskBuilder fixedRate(Duration interval, Duration initialDelay) {
        this.fixedRate = interval;
        this.initialDelay = initialDelay;
        return this;
    }

    /**
     * Sets the task as a one-shot delayed task.
     */
    public TaskBuilder oneShot(Duration delay) {
        this.oneShotDelay = delay;
        return this;
    }

    /**
     * Registers the task with the configured settings.
     *
     * @param runnable the task implementation
     */
    public void register(Runnable runnable) {
        Runnable wrapped = runnable;

        // Apply timeout wrapper if configured
        if (timeout != null) {
            wrapped = new TimeoutRunnable(taskName, wrapped, timeout);
        }

        // Determine task type and register
        if (oneShotDelay != null) {
            service.schedule(taskName, oneShotDelay, wrapped);
        } else if (fixedDelay != null) {
            if (retryPolicy != null) {
                service.addFixedDelayTask(taskName, fixedDelay, initialDelay, wrapped, retryPolicy);
            } else {
                service.addFixedDelayTask(taskName, fixedDelay, initialDelay, wrapped);
            }
        } else if (fixedRate != null) {
            if (retryPolicy != null) {
                service.addFixedRateTask(taskName, fixedRate, initialDelay, wrapped, retryPolicy);
            } else {
                service.addFixedRateTask(taskName, fixedRate, initialDelay, wrapped);
            }
        } else if (cron != null) {
            if (timezone != null) {
                service.add(taskName, cron, timezone, wrapped);
            } else if (retryPolicy != null) {
                service.add(taskName, cron, wrapped, retryPolicy);
            } else {
                service.add(taskName, cron, wrapped);
            }
        } else {
            throw new IllegalStateException("No scheduling type configured. Use cron(), fixedDelay(), fixedRate(), or oneShot().");
        }
    }
}
