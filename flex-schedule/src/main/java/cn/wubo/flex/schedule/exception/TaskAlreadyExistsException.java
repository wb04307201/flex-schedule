package cn.wubo.flex.schedule.exception;

/**
 * Thrown when attempting to register a task with a name that is already in use.
 */
public class TaskAlreadyExistsException extends FlexScheduleException {

    public TaskAlreadyExistsException(String taskName) {
        super(String.format("Task [%s] already exists, add failed", taskName));
    }
}
