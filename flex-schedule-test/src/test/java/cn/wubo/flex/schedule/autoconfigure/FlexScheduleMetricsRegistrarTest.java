package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.MetricsRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FlexScheduleMetricsRegistrarTest {

    private ThreadPoolTaskScheduler scheduler;
    private FlexScheduledTaskRegistrar registrar;

    @BeforeEach
    void setUp() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("test-mr-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        registrar = new FlexScheduledTaskRegistrar(scheduler, 5);
    }

    @AfterEach
    void tearDown() {
        registrar.destroy();
    }

    @Test
    void afterPropertiesSet_wiresMetricsRecorderIntoRegistrar() {
        // Pre-wiring: record execution should be a no-op (NOOP recorder is the default).
        AtomicInteger recorded = new AtomicInteger();
        MetricsRecorder recorder = new MetricsRecorder() {
            @Override public void recordExecution(String name, String type, Duration d, boolean ok) {
                recorded.incrementAndGet();
            }
            @Override public void setActiveTaskCountSupplier(java.util.function.Supplier<Integer> s) {}
        };

        FlexScheduleMetricsRegistrar wiring = new FlexScheduleMetricsRegistrar(registrar, recorder);
        wiring.afterPropertiesSet();

        // Drive a task execution; the wired recorder must see it.
        AtomicInteger fires = new AtomicInteger();
        registrar.addFixedDelayTask("t", Duration.ofMillis(50), Duration.ZERO, fires::incrementAndGet);
        // The fix: status.afterExecution recordExecution only fires after a successful run; give it time.
        waitFor(() -> recorded.get() >= 1, 2000);
        assertThat(recorded.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void afterPropertiesSet_nullRecorder_doesNotThrow() {
        FlexScheduleMetricsRegistrar wiring = new FlexScheduleMetricsRegistrar(registrar, null);
        // Should silently skip; registrar keeps its default NOOP recorder.
        wiring.afterPropertiesSet();

        // No exception, no state corruption: registrar still functional
        assertThat(registrar.exists("anything")).isFalse();
    }

    @Test
    void afterPropertiesSet_invokesSetActiveTaskCountSupplierOnRecorder() {
        // The wiring must propagate the recorder's setActiveTaskCountSupplier so the recorder
        // can read the live task count for its gauge.
        MetricsRecorder recorder = mock(MetricsRecorder.class);
        FlexScheduleMetricsRegistrar wiring = new FlexScheduleMetricsRegistrar(registrar, recorder);

        wiring.afterPropertiesSet();

        verify(recorder).setActiveTaskCountSupplier(org.mockito.ArgumentMatchers.any());
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }
}