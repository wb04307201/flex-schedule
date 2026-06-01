package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class CronValidationTest {

    private ThreadPoolTaskScheduler taskScheduler;
    private FlexScheduledTaskRegistrar registrar;

    @BeforeEach
    void setUp() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.setThreadNamePrefix("test-cron-");
        taskScheduler.initialize();
        registrar = new FlexScheduledTaskRegistrar(taskScheduler, 5);
    }

    @AfterEach
    void tearDown() {
        registrar.destroy();
    }

    @Test
    void addCronTask_invalidCron_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addCronTask("invalidCron", "not a cron", () -> {}));
    }

    @Test
    void addCronTask_validCron6Fields_shouldRegister() {
        registrar.addCronTask("validCron6", "0 * * * * *", () -> {});
        assertTrue(registrar.exists("validCron6"));
    }

    @Test
    void addCronTask_emptyCron_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addCronTask("emptyCron", "", () -> {}));
    }

    @Test
    void addCronTask_nullCron_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addCronTask("nullCron", null, () -> {}));
    }

    @Test
    void addCronTask_withRetry_invalidCron_shouldThrow() {
        RetryPolicy policy = RetryPolicy.fixed(1, Duration.ofMillis(100));
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addCronTask("invalidRetry", "bad cron", () -> {}, policy));
    }

    @Test
    void addCronTask_withTimezone_invalidCron_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addCronTask("invalidTZ", "bad cron", java.time.ZoneId.of("UTC"), () -> {}));
    }

    @Test
    void replaceCronTask_invalidCron_shouldThrow() {
        registrar.addCronTask("replaceInvalid", "0 * * * * *", () -> {});
        assertThrows(IllegalArgumentException.class, () ->
                registrar.replaceCronTask("replaceInvalid", "bad cron", () -> {}));
    }
}
