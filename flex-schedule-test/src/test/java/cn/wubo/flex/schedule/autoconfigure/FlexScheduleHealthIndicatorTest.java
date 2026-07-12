package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlexScheduleHealthIndicatorTest {

    private ThreadPoolTaskScheduler scheduler;
    private FlexScheduledTaskRegistrar registrar;
    private FlexScheduleHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("test-health-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();

        registrar = new FlexScheduledTaskRegistrar(scheduler, 5);
        indicator = new FlexScheduleHealthIndicator(registrar, scheduler);
    }

    @AfterEach
    void tearDown() {
        registrar.destroy();
    }

    @Test
    void emptyRegistry_isUp_withZeroCounts() {
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("totalTasks", 0);
        assertThat(health.getDetails()).containsEntry("activeTasks", 0L);
        assertThat(health.getDetails()).containsEntry("pausedTasks", 0L);
        assertThat(health.getDetails()).containsKey("poolSize");
    }

    @Test
    void registryWithTasks_reportsActiveAndPausedCounts() {
        registrar.addFixedDelayTask("running", Duration.ofMinutes(15), Duration.ZERO, () -> {});
        registrar.addFixedDelayTask("paused", Duration.ofMinutes(15), Duration.ZERO, () -> {});
        registrar.pause("paused");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("totalTasks", 2);
        assertThat(health.getDetails()).containsEntry("activeTasks", 1L);
        assertThat(health.getDetails()).containsEntry("pausedTasks", 1L);
    }

    @Test
    void heavilyUtilizedPool_addsWarningDetail() throws Exception {
        // Acquire 4 threads (full pool) and keep them busy to push utilization to >=80% (= 4/4 = 100%).
        // Use CountDownLatch to keep them blocked, then ask for health.
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger busy = new AtomicInteger(0);
        for (int i = 0; i < 4; i++) {
            scheduler.submit(() -> {
                try {
                    busy.incrementAndGet();
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // Wait until all 4 threads are busy
        long deadline = System.currentTimeMillis() + 2000;
        while (busy.get() < 4 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        try {
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsKey("warning");
            assertThat(health.getDetails().get("warning").toString()).contains("80%");
        } finally {
            latch.countDown();
            Thread.sleep(50); // let threads finish before teardown
        }
    }

    @Test
    void listTasks_throws_returnsDownWithException() {
        // Substitute a registrar whose listTasks throws
        FlexScheduledTaskRegistrar brokenRegistrar = mock(FlexScheduledTaskRegistrar.class);
        when(brokenRegistrar.listTasks()).thenThrow(new RuntimeException("boom"));

        FlexScheduleHealthIndicator broken = new FlexScheduleHealthIndicator(brokenRegistrar, scheduler);

        Health health = broken.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
        assertThat(health.getDetails().get("error").toString()).contains("boom");
    }
}