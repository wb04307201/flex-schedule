package cn.wubo.flex.schedule.core;

import java.time.Instant;

/**
 * Detailed information about a scheduled task, including metadata not present in {@link TaskInfo}.
 *
 * @param taskName    unique task identifier
 * @param taskType    CRON, FIXED_DELAY, FIXED_RATE, or ONE_SHOT
 * @param schedule    the scheduling expression
 * @param oneShot     whether this is a one-shot task
 * @param retryPolicy the retry policy (null if none)
 * @param createdAt   when the task was registered
 * @param paused      whether the task is currently paused
 */
public record TaskDetail(
        String taskName,
        String taskType,
        String schedule,
        boolean oneShot,
        RetryPolicy retryPolicy,
        Instant createdAt,
        boolean paused
) {
}
