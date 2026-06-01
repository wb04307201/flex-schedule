package cn.wubo.flex.schedule.core;

/**
 * Listener interface for task execution lifecycle events.
 * <p>
 * By default, listeners are invoked synchronously on the scheduler thread.
 * Implement {@link #isAsync()} to return {@code true} for non-blocking execution.
 * </p>
 */
public interface TaskExecutionListener {

    /**
     * Called before the task's Runnable executes.
     */
    default void beforeExecution(String taskName) {
    }

    /**
     * Called after the task's Runnable completes successfully.
     */
    default void afterExecution(String taskName) {
    }

    /**
     * Called when the task's Runnable throws an exception.
     */
    default void onError(String taskName, Throwable error) {
    }

    /**
     * Returns whether this listener should be invoked asynchronously.
     * <p>
     * Async listeners do not block the scheduler thread, making them suitable
     * for slow operations like audit logging or external notifications.
     * </p>
     *
     * @return {@code true} if the listener should be invoked asynchronously
     */
    default boolean isAsync() {
        return false;
    }
}
