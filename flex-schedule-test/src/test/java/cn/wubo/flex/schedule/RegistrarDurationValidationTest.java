package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 5, scenario A13: the {@link Duration} overloads of addFixedDelayTask /
 * addFixedRateTask must enforce exactly the same validation as the seconds-based
 * overloads — non-positive interval and negative initialDelay are rejected with
 * {@link IllegalArgumentException} (Spring {@code Assert} semantics), and a rejected
 * registration must leave no task behind.
 */
class RegistrarDurationValidationTest {

    private ThreadPoolTaskScheduler scheduler;
    private FlexScheduledTaskRegistrar registrar;

    @BeforeEach
    void setUp() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("test-duration-validation-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        registrar = new FlexScheduledTaskRegistrar(scheduler, 5);
    }

    @AfterEach
    void tearDown() {
        registrar.destroy();
    }

    // ─── FIXED_DELAY Duration overload ────────────────────────────────

    @Test
    void addFixedDelayTask_zeroInterval_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addFixedDelayTask("zeroInterval", Duration.ZERO, Duration.ZERO, () -> { }));
        assertFalse(registrar.exists("zeroInterval"));
    }

    @Test
    void addFixedDelayTask_negativeInterval_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addFixedDelayTask("negInterval", Duration.ofSeconds(-5), Duration.ZERO, () -> { }));
        assertFalse(registrar.exists("negInterval"));
    }

    @Test
    void addFixedDelayTask_negativeInitialDelay_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addFixedDelayTask("negDelay", Duration.ofSeconds(10), Duration.ofMillis(-1), () -> { }));
        assertFalse(registrar.exists("negDelay"));
    }

    @Test
    void addFixedDelayTask_nullInterval_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addFixedDelayTask("nullInterval", null, Duration.ZERO, () -> { }));
    }

    @Test
    void addFixedDelayTask_nullInitialDelay_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addFixedDelayTask("nullDelay", Duration.ofSeconds(10), null, () -> { }));
    }

    // ─── FIXED_RATE Duration overload ─────────────────────────────────

    @Test
    void addFixedRateTask_zeroInterval_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addFixedRateTask("zeroInterval", Duration.ZERO, Duration.ZERO, () -> { }));
        assertFalse(registrar.exists("zeroInterval"));
    }

    @Test
    void addFixedRateTask_negativeInterval_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addFixedRateTask("negInterval", Duration.ofSeconds(-5), Duration.ZERO, () -> { }));
        assertFalse(registrar.exists("negInterval"));
    }

    @Test
    void addFixedRateTask_negativeInitialDelay_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addFixedRateTask("negDelay", Duration.ofSeconds(10), Duration.ofMillis(-1), () -> { }));
        assertFalse(registrar.exists("negDelay"));
    }

    @Test
    void addFixedRateTask_nullInterval_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addFixedRateTask("nullInterval", null, Duration.ZERO, () -> { }));
    }

    @Test
    void addFixedRateTask_nullInitialDelay_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addFixedRateTask("nullDelay", Duration.ofSeconds(10), null, () -> { }));
    }

    // ─── Positive control: the sub-second boundary value is accepted ──

    @Test
    void addFixedDelayTask_positiveSubSecondInterval_accepted() {
        assertDoesNotThrow(() ->
                registrar.addFixedDelayTask("ok", Duration.ofMillis(1), Duration.ZERO, () -> { }));
        assertTrue(registrar.exists("ok"));
    }

    @Test
    void addFixedRateTask_positiveSubSecondInterval_accepted() {
        assertDoesNotThrow(() ->
                registrar.addFixedRateTask("ok", Duration.ofMillis(1), Duration.ZERO, () -> { }));
        assertTrue(registrar.exists("ok"));
    }
}
