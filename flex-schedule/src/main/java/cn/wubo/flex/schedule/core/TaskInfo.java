package cn.wubo.flex.schedule.core;

/**
 * Immutable snapshot of a scheduled task's metadata.
 *
 * @param taskName  unique task identifier
 * @param taskType  CRON, FIXED_DELAY, or FIXED_RATE
 * @param schedule  the cron expression, or interval/delay description
 */
public record TaskInfo(String taskName, String taskType, String schedule) {
}
