package cn.wubo.flex.schedule.core;

import cn.wubo.flex.schedule.exception.ExecutionTimeoutException;

import java.time.Duration;
import java.util.concurrent.*;

/**
 * A Runnable wrapper that enforces a maximum execution timeout.
 * If the delegate does not complete within the timeout, an {@link ExecutionTimeoutException} is thrown.
 * Uses a shared thread pool to avoid creating a new executor per task.
 */
public class TimeoutRunnable implements Runnable {

    private final String taskName;
    private final Runnable delegate;
    private final Duration timeout;
    private static final long TERMINATION_WAIT_SECONDS = 5;

    public TimeoutRunnable(String taskName, Runnable delegate, Duration timeout) {
        this.taskName = taskName;
        this.delegate = delegate;
        this.timeout = timeout;
    }

    @Override
    public void run() {
        ExecutorService executor = TimeoutExecutorManager.getExecutor();
        Future<?> future = executor.submit(delegate);
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ExecutionTimeoutException(taskName, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new ExecutionTimeoutException(taskName, timeout);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
        // Note: We don't shutdown the executor here - it's shared and managed by TimeoutExecutorManager
    }
}
