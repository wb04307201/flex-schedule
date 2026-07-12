package cn.wubo.flex.schedule.exception;

/**
 * Thrown when a task registration violates configured interval limits
 * (only when limits.mode=STRICT).
 */
public class TaskLimitExceededException extends FlexScheduleException {

    public TaskLimitExceededException(String message) {
        super(message);
    }
}