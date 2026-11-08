package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.MetricsRecorder;
import cn.wubo.flex.schedule.core.MisfirePolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 5, scenario A16: the built-in {@link MetricsRecorder#NOOP} fallback must never
 * throw — it is the default recorder when Micrometer is absent, and the registrar fires
 * it on every execution path (success, failure, misfire).
 */
class MetricsRecorderTest {

    @Test
    void noop_recordExecution_doesNotThrow() {
        assertDoesNotThrow(() -> MetricsRecorder.NOOP.recordExecution(
                "task", "CRON", Duration.ofMillis(42), true));
        assertDoesNotThrow(() -> MetricsRecorder.NOOP.recordExecution(
                "task", "FIXED_RATE", Duration.ZERO, false));
        // Null-valued arguments must be tolerated by a no-op as well.
        assertDoesNotThrow(() -> MetricsRecorder.NOOP.recordExecution(null, null, null, false));
    }

    @Test
    void noop_setActiveTaskCountSupplier_doesNotThrow() {
        assertDoesNotThrow(() -> MetricsRecorder.NOOP.setActiveTaskCountSupplier(() -> 7));
        assertDoesNotThrow(() -> MetricsRecorder.NOOP.setActiveTaskCountSupplier(null));
    }

    @Test
    void noop_recordMisfire_defaultMethod_doesNotThrow() {
        assertDoesNotThrow(() -> MetricsRecorder.NOOP.recordMisfire("task", "CRON", MisfirePolicy.SKIP));
    }
}
