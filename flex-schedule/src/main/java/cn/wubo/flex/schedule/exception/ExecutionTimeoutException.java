package cn.wubo.flex.schedule.exception;

import java.time.Duration;

/**
 * Thrown when a task execution exceeds its configured timeout.
 */
public class ExecutionTimeoutException extends FlexScheduleException {

    private final String taskName;
    private final Duration timeout;

    public ExecutionTimeoutException(String taskName, Duration timeout) {
        super(String.format("Task [%s] exceeded timeout of %s", taskName, timeout));
        this.taskName = taskName;
        this.timeout = timeout;
    }

    public String getTaskName() {
        return taskName;
    }

    public Duration getTimeout() {
        return timeout;
    }
}
