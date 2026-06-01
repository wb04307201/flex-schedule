package cn.wubo.flex.schedule.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Summary statistics for a task's execution history.
 *
 * @param taskName              the task name
 * @param totalExecutions       total number of executions
 * @param successCount          number of successful executions
 * @param failureCount          number of failed executions
 * @param successRate           success rate as a percentage (0-100)
 * @param avgDuration           average execution duration
 * @param minDuration           minimum execution duration
 * @param maxDuration           maximum execution duration
 * @param lastExecutionTime     time of the last execution
 * @param lastSuccessTime       time of the last successful execution
 * @param lastFailureTime       time of the last failed execution
 * @param consecutiveFailures   number of consecutive failures (resets on success)
 */
public record TaskStatistics(
        String taskName,
        long totalExecutions,
        long successCount,
        long failureCount,
        double successRate,
        Duration avgDuration,
        Duration minDuration,
        Duration maxDuration,
        Optional<Instant> lastExecutionTime,
        Optional<Instant> lastSuccessTime,
        Optional<Instant> lastFailureTime,
        long consecutiveFailures
) {
    /**
     * Computes statistics from a list of execution records.
     */
    public static TaskStatistics fromRecords(String taskName, List<ExecutionRecord> records) {
        if (records == null || records.isEmpty()) {
            return new TaskStatistics(taskName, 0, 0, 0, 0.0,
                    Duration.ZERO, Duration.ZERO, Duration.ZERO,
                    Optional.empty(), Optional.empty(), Optional.empty(), 0);
        }

        long total = records.size();
        long success = records.stream().filter(ExecutionRecord::success).count();
        long failure = total - success;
        double successRate = total > 0 ? (success * 100.0 / total) : 0.0;

        Duration totalDuration = records.stream()
                .map(ExecutionRecord::duration)
                .reduce(Duration.ZERO, Duration::plus);
        Duration avgDuration = total > 0 ? totalDuration.dividedBy(total) : Duration.ZERO;

        Duration minDuration = records.stream()
                .map(ExecutionRecord::duration)
                .min(Duration::compareTo)
                .orElse(Duration.ZERO);

        Duration maxDuration = records.stream()
                .map(ExecutionRecord::duration)
                .max(Duration::compareTo)
                .orElse(Duration.ZERO);

        // Records are assumed to be sorted by startTime descending (most recent first)
        Optional<Instant> lastExecutionTime = records.stream()
                .findFirst()
                .map(ExecutionRecord::startTime);

        Optional<Instant> lastSuccessTime = records.stream()
                .filter(ExecutionRecord::success)
                .findFirst()
                .map(ExecutionRecord::startTime);

        Optional<Instant> lastFailureTime = records.stream()
                .filter(r -> !r.success())
                .findFirst()
                .map(ExecutionRecord::startTime);

        // Count consecutive failures from the most recent
        long consecutiveFailures = 0;
        for (ExecutionRecord record : records) {
            if (!record.success()) {
                consecutiveFailures++;
            } else {
                break;
            }
        }

        return new TaskStatistics(taskName, total, success, failure, successRate,
                avgDuration, minDuration, maxDuration,
                lastExecutionTime, lastSuccessTime, lastFailureTime,
                consecutiveFailures);
    }
}
