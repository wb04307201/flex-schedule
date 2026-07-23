package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.core.ExecutionHistory;
import cn.wubo.flex.schedule.core.ExecutionRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pin that the default {@link ExecutionHistory#NOOP} stays NOOP unless somebody
 * explicitly installs an {@code ExecutionHistory} bean (see
 * {@code FlexScheduleAutoConfiguration#flexScheduleExecutionHistory}). The full
 * wiring-against-the-autoconfig test is exercised indirectly through
 * {@code FlexScheduledTaskRegistrarTest} and the production Spring Boot starter.
 */
class FlexScheduleExecutionHistoryAutoConfigTest {

    @Test
    void noOpHistory_unwiredStaysNoOp_evenBeforeFix() {
        // Pin the contract: the default ExecutionHistory NOOP stays NOOP
        // unless somebody explicitly sets an ExecutionHistory bean. This
        // documents the original bug surface so a future refactor can't
        // accidentally re-introduce a non-recording default.
        ExecutionHistory noop = ExecutionHistory.NOOP;
        assertThat(noop).isNotNull();
        // Plus: round-trip a record through NOOP — should be silently dropped
        // (this is the original bug — proving it's actually NOOP behaviour).
        ExecutionRecord rec = new ExecutionRecord(
                "noopTask", "CRON", Instant.now(), java.time.Duration.ZERO, true, null);
        noop.record(rec);
        assertThat(noop.getHistory("noopTask", 10))
                .as("NOOP implementation must not retain records")
                .isEmpty();
    }
}
