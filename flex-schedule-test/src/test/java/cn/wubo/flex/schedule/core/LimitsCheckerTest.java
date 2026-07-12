package cn.wubo.flex.schedule.core;

import cn.wubo.flex.schedule.core.LimitsChecker;
import cn.wubo.flex.schedule.core.TaskLimits;
import cn.wubo.flex.schedule.core.TaskLimits.Mode;
import cn.wubo.flex.schedule.exception.TaskLimitExceededException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LimitsCheckerTest {

    // ─── assertInterval ────────────────────────────────────────────

    @Test
    void assertInterval_disabled_neverThrows() {
        LimitsChecker checker = new LimitsChecker(TaskLimits.DISABLED);
        assertDoesNotThrow(() -> checker.assertInterval("t", Duration.ofMillis(1)));
    }

    @Test
    void assertInterval_nullMinInterval_neverThrows() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(null, null, Mode.STRICT));
        assertDoesNotThrow(() -> checker.assertInterval("t", Duration.ofMillis(1)));
    }

    @Test
    void assertInterval_aboveMin_passes() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(Duration.ofMinutes(10), null, Mode.STRICT));
        assertDoesNotThrow(() -> checker.assertInterval("t", Duration.ofMinutes(15)));
    }

    @Test
    void assertInterval_equalToMin_passes() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(Duration.ofMinutes(10), null, Mode.STRICT));
        assertDoesNotThrow(() -> checker.assertInterval("t", Duration.ofMinutes(10)));
    }

    @Test
    void assertInterval_belowMin_strictThrows() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(Duration.ofMinutes(10), null, Mode.STRICT));
        TaskLimitExceededException ex = assertThrows(TaskLimitExceededException.class,
            () -> checker.assertInterval("myJob", Duration.ofSeconds(5)));
        assertTrue(ex.getMessage().contains("myJob"));
        assertTrue(ex.getMessage().contains("below minimum"));
    }

    @Test
    void assertInterval_belowMin_warnDoesNotThrow() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(Duration.ofMinutes(10), null, Mode.WARN));
        assertDoesNotThrow(() -> checker.assertInterval("t", Duration.ofSeconds(5)));
    }

    @Test
    void assertInterval_belowMin_offDoesNotThrow() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(Duration.ofMinutes(10), null, Mode.OFF));
        assertDoesNotThrow(() -> checker.assertInterval("t", Duration.ofSeconds(5)));
    }

    // ─── isExpired ─────────────────────────────────────────────────

    @Test
    void isExpired_disabled_returnsFalse() {
        LimitsChecker checker = new LimitsChecker(TaskLimits.DISABLED);
        assertFalse(checker.isExpired("t", Instant.now().minus(Duration.ofDays(365))));
    }

    @Test
    void isExpired_nullMaxLifetime_returnsFalse() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(null, null, Mode.STRICT));
        assertFalse(checker.isExpired("t", Instant.now().minus(Duration.ofDays(365))));
    }

    @Test
    void isExpired_youngTask_returnsFalse() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(null, Duration.ofDays(7), Mode.STRICT));
        assertFalse(checker.isExpired("t", Instant.now().minus(Duration.ofDays(1))));
    }

    @Test
    void isExpired_oldTask_returnsTrue() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(null, Duration.ofDays(7), Mode.STRICT));
        assertTrue(checker.isExpired("t", Instant.now().minus(Duration.ofDays(8))));
    }

    @Test
    void isExpired_exactlyAtLifetime_returnsTrue() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(null, Duration.ofSeconds(1), Mode.STRICT));
        assertTrue(checker.isExpired("t", Instant.now().minus(Duration.ofMillis(1100))));
    }

    // ─── WARN mode symmetry with assertInterval ─────────────────────

    @Test
    void isExpired_warnMode_returnsFalse_allowsTask() {
        // WARN mode should "log and allow", matching assertInterval's WARN semantics.
        LimitsChecker checker = new LimitsChecker(new TaskLimits(null, Duration.ofDays(7), Mode.WARN));
        assertFalse(checker.isExpired("t", Instant.now().minus(Duration.ofDays(8))));
    }

    @Test
    void isExpired_warnMode_youngTask_returnsFalse() {
        LimitsChecker checker = new LimitsChecker(new TaskLimits(null, Duration.ofDays(7), Mode.WARN));
        assertFalse(checker.isExpired("t", Instant.now().minus(Duration.ofDays(1))));
    }
}