package cn.wubo.flex.schedule.exception;

/**
 * Thrown when reflective invocation of a bean method fails.
 */
public class BeanMethodRunnableException extends FlexScheduleException {

    public BeanMethodRunnableException(String message, Throwable cause) {
        super(message, cause);
    }
}
