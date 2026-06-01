package cn.wubo.flex.schedule.exception;

/**
 * Base runtime exception for all flex schedule related errors.
 */
public class FlexScheduleException extends RuntimeException {

    public FlexScheduleException(String message) {
        super(message);
    }

    public FlexScheduleException(String message, Throwable cause) {
        super(message, cause);
    }
}
