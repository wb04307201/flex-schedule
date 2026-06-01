package cn.wubo.flex.schedule.core;

import java.time.Duration;
import java.time.Instant;

/**
 * Immutable record of a single task execution.
 *
 * @param taskName  the task name
 * @param taskType  CRON, FIXED_DELAY, FIXED_RATE, or ONE_SHOT
 * @param startTime when the execution started
 * @param duration  how long the execution took
 * @param success   whether the execution succeeded
 * @param error     error message if failed (null if succeeded)
 */
public record ExecutionRecord(
        String taskName,
        String taskType,
        Instant startTime,
        Duration duration,
        boolean success,
        String error
) {
}
