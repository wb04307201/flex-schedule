package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.TaskLimits;
import cn.wubo.flex.schedule.core.TaskLimits.Mode;
import cn.wubo.flex.schedule.exception.TaskLimitExceededException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class FlexScheduledTaskLimitsTest {

    private ThreadPoolTaskScheduler scheduler;
    private FlexScheduledTaskRegistrar strict;
    private FlexScheduledTaskRegistrar warn;
    private FlexScheduledTaskRegistrar off;
    private FlexScheduledTaskRegistrar defaults;

    @BeforeEach
    void setUp() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("test-limits-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();

        TaskLimits strictLimits = new TaskLimits(Duration.ofMinutes(10), null, Mode.STRICT);
        TaskLimits warnLimits = new TaskLimits(Duration.ofMinutes(10), null, Mode.WARN);
        TaskLimits offLimits = new TaskLimits(Duration.ofMinutes(10), null, Mode.OFF);

        strict = new FlexScheduledTaskRegistrar(scheduler, 5, strictLimits);
        warn = new FlexScheduledTaskRegistrar(scheduler, 5, warnLimits);
        off = new FlexScheduledTaskRegistrar(scheduler, 5, offLimits);
        defaults = new FlexScheduledTaskRegistrar(scheduler, 5);
    }

    @AfterEach
    void tearDown() {
        strict.destroy();
        warn.destroy();
        off.destroy();
        defaults.destroy();
    }

    // ─── Min-interval on FIXED_DELAY ────────────────────────────────

    @Test
    void fixedDelay_belowMin_strictThrows() {
        assertThrows(TaskLimitExceededException.class,
            () -> strict.addFixedDelayTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
        assertFalse(strict.exists("t"));
    }

    @Test
    void fixedDelay_belowMin_warnAllows() {
        assertDoesNotThrow(
            () -> warn.addFixedDelayTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
        assertTrue(warn.exists("t"));
    }

    @Test
    void fixedDelay_belowMin_offAllows() {
        assertDoesNotThrow(
            () -> off.addFixedDelayTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
        assertTrue(off.exists("t"));
    }

    @Test
    void fixedDelay_nullMinInterval_allowsAny() {
        assertDoesNotThrow(
            () -> defaults.addFixedDelayTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
        assertTrue(defaults.exists("t"));
    }

    @Test
    void fixedDelay_initialDelay_belowMin_stillAllowed() {
        assertDoesNotThrow(
            () -> strict.addFixedDelayTask("t", Duration.ofMinutes(15), Duration.ofSeconds(5), () -> {}));
        assertTrue(strict.exists("t"));
    }

    @Test
    void fixedDelay_atMin_allowed() {
        assertDoesNotThrow(
            () -> strict.addFixedDelayTask("t", Duration.ofMinutes(10), Duration.ZERO, () -> {}));
        assertTrue(strict.exists("t"));
    }

    // ─── Min-interval on FIXED_RATE ─────────────────────────────────

    @Test
    void fixedRate_belowMin_strictThrows() {
        assertThrows(TaskLimitExceededException.class,
            () -> strict.addFixedRateTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
    }

    @Test
    void fixedRate_aboveMin_allowed() {
        assertDoesNotThrow(
            () -> strict.addFixedRateTask("t", Duration.ofMinutes(15), Duration.ZERO, () -> {}));
    }

    // ─── Min-interval on ONE_SHOT ───────────────────────────────────

    @Test
    void oneShot_belowMin_strictThrows() {
        assertThrows(TaskLimitExceededException.class,
            () -> strict.schedule("t", Duration.ofSeconds(5), () -> {}));
    }

    @Test
    void oneShot_aboveMin_allowed() {
        assertDoesNotThrow(
            () -> strict.schedule("t", Duration.ofMinutes(15), () -> {}));
    }

    // ─── Min-interval EXEMPT for CRON ───────────────────────────────

    @Test
    void cron_evenWithStrictMinInterval_isAllowed() {
        // cron "every second" — interval is < 10min, but CRON is exempt
        assertDoesNotThrow(
            () -> strict.addCronTask("t", "* * * * * *", () -> {}));
        assertTrue(strict.exists("t"));
    }

    // ─── replace path ──────────────────────────────────────────────

    @Test
    void replaceFixedDelay_belowMin_strictThrows() {
        strict.addFixedDelayTask("t", Duration.ofMinutes(30), Duration.ZERO, () -> {});
        assertThrows(TaskLimitExceededException.class,
            () -> strict.replaceFixedDelayTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
        assertTrue(strict.exists("t"));  // original still there
    }

    @Test
    void replaceFixedRate_belowMin_strictThrows() {
        strict.addFixedRateTask("t", Duration.ofMinutes(30), Duration.ZERO, () -> {});
        assertThrows(TaskLimitExceededException.class,
            () -> strict.replaceFixedRateTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
    }

    @Test
    void replaceCron_belowMin_strictThrows() {
        // cron is exempt from min-interval, so replace-cron also exempt
        assertDoesNotThrow(
            () -> strict.replaceCronTask("neverExisted", "* * * * * *", () -> {}));
        assertTrue(strict.exists("neverExisted"));
    }
}