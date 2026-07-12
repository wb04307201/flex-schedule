package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TaskBuilderTest {

    private ThreadPoolTaskScheduler taskScheduler;
    private FlexScheduledTaskRegistrar registrar;
    private FlexScheduledTaskService service;

    @BeforeEach
    void setUp() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.setThreadNamePrefix("test-builder-");
        taskScheduler.setRemoveOnCancelPolicy(true);
        taskScheduler.initialize();
        registrar = new FlexScheduledTaskRegistrar(taskScheduler, 5);
        service = new DefaultFlexScheduledTaskService(registrar);
    }

    @AfterEach
    void tearDown() {
        registrar.destroy();
    }

    @Test
    void builder_cronTask_shouldRegister() {
        service.task("builderCron")
                .cron("0 * * * * *")
                .register(() -> {});

        assertTrue(service.exists("builderCron"));
    }

    @Test
    void builder_cronWithTimezone_shouldRegister() {
        service.task("builderCronTZ")
                .cron("0 0 9 * * ?")
                .timezone(ZoneId.of("Asia/Shanghai"))
                .register(() -> {});

        assertTrue(service.exists("builderCronTZ"));
        TaskDetail detail = service.getTaskDetail("builderCronTZ").orElseThrow();
        assertTrue(detail.schedule().contains("Asia/Shanghai"));
    }

    @Test
    void builder_cronWithRetry_shouldRegister() {
        service.task("builderCronRetry")
                .cron("0 * * * * *")
                .retry(RetryPolicy.fixed(2, Duration.ofMillis(100)))
                .register(() -> {});

        assertTrue(service.exists("builderCronRetry"));
    }

    @Test
    void builder_fixedDelay_shouldRegister() {
        service.task("builderDelay")
                .fixedDelay(Duration.ofSeconds(10))
                .register(() -> {});

        assertTrue(service.exists("builderDelay"));
    }

    @Test
    void builder_fixedDelayWithInitialDelay_shouldRegister() {
        service.task("builderDelayInit")
                .fixedDelay(Duration.ofSeconds(10), Duration.ofSeconds(5))
                .register(() -> {});

        assertTrue(service.exists("builderDelayInit"));
    }

    @Test
    void builder_fixedRate_shouldRegister() {
        service.task("builderRate")
                .fixedRate(Duration.ofSeconds(10))
                .register(() -> {});

        assertTrue(service.exists("builderRate"));
    }

    @Test
    void builder_oneShot_shouldRegisterAndExecute() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        service.task("builderOneShot")
                .oneShot(Duration.ofMillis(100))
                .register(latch::countDown);

        assertTrue(service.exists("builderOneShot"));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertFalse(service.exists("builderOneShot"));
    }

    @Test
    void builder_withTimeout_shouldRegister() {
        service.task("builderTimeout")
                .cron("0 * * * * *")
                .timeout(Duration.ofSeconds(30))
                .register(() -> {});

        assertTrue(service.exists("builderTimeout"));
    }

    @Test
    void builder_noTypeConfigured_shouldThrow() {
        assertThrows(IllegalStateException.class, () ->
                service.task("noType").register(() -> {}));
    }

    // ─── previously uncovered branches ────────────────────────────

    @Test
    void builder_fixedRate_withInitialDelay_shouldRegister() {
        service.task("rateInit")
                .fixedRate(Duration.ofSeconds(10), Duration.ofSeconds(5))
                .register(() -> {});

        assertTrue(service.exists("rateInit"));
    }

    @Test
    void builder_fixedDelay_withRetry_shouldRegister() {
        service.task("delayRetry")
                .fixedDelay(Duration.ofSeconds(10))
                .retry(RetryPolicy.fixed(2, Duration.ofMillis(100)))
                .register(() -> {});

        assertTrue(service.exists("delayRetry"));
        // The retry policy must be stored on the entry.
        TaskDetail detail = service.getTaskDetail("delayRetry").orElseThrow();
        assertNotNull(detail.retryPolicy());
        assertEquals(2, detail.retryPolicy().maxAttempts());
    }

    @Test
    void builder_fixedRate_withRetry_shouldRegister() {
        service.task("rateRetry")
                .fixedRate(Duration.ofSeconds(10))
                .retry(RetryPolicy.fixed(2, Duration.ofMillis(100)))
                .register(() -> {});

        assertTrue(service.exists("rateRetry"));
        TaskDetail detail = service.getTaskDetail("rateRetry").orElseThrow();
        assertNotNull(detail.retryPolicy());
    }

    @Test
    void builder_oneShot_withTimeout_shouldRegister() {
        service.task("oneShotTimeout")
                .oneShot(Duration.ofMillis(100))
                .timeout(Duration.ofSeconds(30))
                .register(() -> {});

        assertTrue(service.exists("oneShotTimeout"));
    }

    @Test
    void builder_oneShot_withRetry_silentlyDropsRetry() {
        // Current implementation: oneShot path does not honor retry policy.
        // This test pins that behavior so any future change is intentional.
        service.task("oneShotRetry")
                .oneShot(Duration.ofMillis(100))
                .retry(RetryPolicy.fixed(2, Duration.ofMillis(50)))
                .register(() -> {});

        assertTrue(service.exists("oneShotRetry"));
        TaskDetail detail = service.getTaskDetail("oneShotRetry").orElseThrow();
        // Retry is silently dropped on the oneShot path.
        assertNull(detail.retryPolicy(),
            "oneShot must not retain retry policy (design choice pinned by test)");
    }
}
