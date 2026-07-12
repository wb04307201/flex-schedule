package cn.wubo.flex.schedule.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStatisticsTest {

    private static ExecutionRecord rec(String task, Instant start, Duration d, boolean ok) {
        return new ExecutionRecord(task, "FIXED_DELAY", start, d, ok, ok ? null : "boom");
    }

    @Test
    void fromRecords_nullOrEmpty_yieldsZeros() {
        TaskStatistics fromNull = TaskStatistics.fromRecords("t", null);
        TaskStatistics fromEmpty = TaskStatistics.fromRecords("t", Collections.emptyList());

        assertThat(fromNull.totalExecutions()).isZero();
        assertThat(fromNull.consecutiveFailures()).isZero();
        assertThat(fromNull.lastExecutionTime()).isEmpty();
        assertThat(fromNull.lastSuccessTime()).isEmpty();
        assertThat(fromNull.lastFailureTime()).isEmpty();

        assertThat(fromEmpty.totalExecutions()).isZero();
    }

    @Test
    void fromRecords_allSuccess_reportsFullRateAndZeroConsecutiveFailures() {
        Instant now = Instant.now();
        // Records are expected in descending startTime order (most recent first)
        List<ExecutionRecord> recs = new ArrayList<>();
        recs.add(rec("t", now, Duration.ofMillis(100), true));
        recs.add(rec("t", now.minusSeconds(1), Duration.ofMillis(200), true));
        recs.add(rec("t", now.minusSeconds(2), Duration.ofMillis(300), true));

        TaskStatistics stats = TaskStatistics.fromRecords("t", recs);

        assertThat(stats.totalExecutions()).isEqualTo(3);
        assertThat(stats.successCount()).isEqualTo(3);
        assertThat(stats.failureCount()).isZero();
        assertThat(stats.successRate()).isEqualTo(100.0);
        assertThat(stats.consecutiveFailures()).isZero();
        assertThat(stats.lastSuccessTime()).contains(now);
        assertThat(stats.lastFailureTime()).isEmpty();
        assertThat(stats.minDuration()).isEqualTo(Duration.ofMillis(100));
        assertThat(stats.maxDuration()).isEqualTo(Duration.ofMillis(300));
        assertThat(stats.avgDuration()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void fromRecords_allFailure_countsEveryOneAsConsecutive() {
        Instant now = Instant.now();
        List<ExecutionRecord> recs = new ArrayList<>();
        recs.add(rec("t", now, Duration.ofMillis(10), false));
        recs.add(rec("t", now.minusSeconds(1), Duration.ofMillis(20), false));
        recs.add(rec("t", now.minusSeconds(2), Duration.ofMillis(30), false));

        TaskStatistics stats = TaskStatistics.fromRecords("t", recs);

        assertThat(stats.failureCount()).isEqualTo(3);
        assertThat(stats.successRate()).isEqualTo(0.0);
        assertThat(stats.consecutiveFailures()).isEqualTo(3);
        assertThat(stats.lastFailureTime()).contains(now);
        assertThat(stats.lastSuccessTime()).isEmpty();
    }

    @Test
    void fromRecords_mixedRecentFailures_countsOnlyConsecutiveFromHead() {
        // Order: most recent first. Newest 2 are failures, then a success, then another failure.
        // Consecutive count from the head must stop at the first success.
        Instant now = Instant.now();
        List<ExecutionRecord> recs = new ArrayList<>();
        recs.add(rec("t", now, Duration.ofMillis(10), false));           // newest
        recs.add(rec("t", now.minusSeconds(1), Duration.ofMillis(10), false));
        recs.add(rec("t", now.minusSeconds(2), Duration.ofMillis(10), true));
        recs.add(rec("t", now.minusSeconds(3), Duration.ofMillis(10), false));  // older failure

        TaskStatistics stats = TaskStatistics.fromRecords("t", recs);

        assertThat(stats.consecutiveFailures()).isEqualTo(2); // stops at the success
        assertThat(stats.failureCount()).isEqualTo(3);
        assertThat(stats.successCount()).isEqualTo(1);
    }

    @Test
    void fromRecords_singleRecord_basicShapeIsCorrect() {
        Instant now = Instant.now();
        TaskStatistics stats = TaskStatistics.fromRecords("solo",
            List.of(rec("solo", now, Duration.ofMillis(50), true)));

        assertThat(stats.taskName()).isEqualTo("solo");
        assertThat(stats.totalExecutions()).isEqualTo(1);
        assertThat(stats.successRate()).isEqualTo(100.0);
        assertThat(stats.avgDuration()).isEqualTo(Duration.ofMillis(50));
        assertThat(stats.minDuration()).isEqualTo(Duration.ofMillis(50));
        assertThat(stats.maxDuration()).isEqualTo(Duration.ofMillis(50));
        assertThat(stats.lastExecutionTime()).contains(now);
    }
}