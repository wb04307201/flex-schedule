package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.DefaultFlexScheduledTaskService;
import cn.wubo.flex.schedule.core.ExecutionHistory;
import cn.wubo.flex.schedule.core.ExecutionRecord;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-delegation tests for {@link DefaultFlexScheduledTaskService} (Task 5, scenarios A6/A7):
 * the service must hand the EXACT arguments to the corresponding registrar/history method and
 * must not silently drop or re-order any of them. A mocked registrar is intentional here —
 * behavior of the registrar itself is covered by the registrar suites; these tests pin the
 * facade wiring only.
 */
class FlexScheduledTaskServiceDelegationTest {

    private FlexScheduledTaskRegistrar registrar;
    private FlexScheduledTaskService service;

    @BeforeEach
    void setUp() {
        registrar = mock(FlexScheduledTaskRegistrar.class);
        service = new DefaultFlexScheduledTaskService(registrar);
    }

    // ─── A6: timeout-aware overload delegation ─────────────────────────

    @Test
    void add_withTimeout_delegatesExactArgsToAddCronTask() {
        Runnable runnable = () -> { /* no-op */ };
        Duration timeout = Duration.ofSeconds(30);

        service.add("cronJob", "0 * * * * *", timeout, runnable);

        verify(registrar).addCronTask("cronJob", "0 * * * * *", timeout, runnable);
    }

    @Test
    void addFixedDelayTask_withTimeout_delegatesExactArgs() {
        Runnable runnable = () -> { /* no-op */ };
        Duration interval = Duration.ofSeconds(10);
        Duration initialDelay = Duration.ofSeconds(5);
        Duration timeout = Duration.ofSeconds(30);

        service.addFixedDelayTask("delayJob", interval, initialDelay, timeout, runnable);

        verify(registrar).addFixedDelayTask("delayJob", interval, initialDelay, timeout, runnable);
    }

    @Test
    void addFixedRateTask_withTimeout_delegatesExactArgs() {
        Runnable runnable = () -> { /* no-op */ };
        Duration interval = Duration.ofSeconds(10);
        Duration initialDelay = Duration.ofSeconds(5);
        Duration timeout = Duration.ofSeconds(30);

        service.addFixedRateTask("rateJob", interval, initialDelay, timeout, runnable);

        verify(registrar).addFixedRateTask("rateJob", interval, initialDelay, timeout, runnable);
    }

    // ─── A7: history / pause query delegation ──────────────────────────

    @Test
    void getAllExecutionHistory_delegatesToInstalledHistory() {
        ExecutionHistory history = mock(ExecutionHistory.class);
        List<ExecutionRecord> expected = List.of(
                new ExecutionRecord("job", "CRON", java.time.Instant.now(), Duration.ofMillis(5), true, null));
        when(registrar.getExecutionHistory()).thenReturn(history);
        when(history.getAllHistory(7)).thenReturn(expected);

        List<ExecutionRecord> actual = service.getAllExecutionHistory(7);

        assertSame(expected, actual);
        verify(history).getAllHistory(7);
    }

    @Test
    void clearExecutionHistory_delegatesToInstalledHistory() {
        ExecutionHistory history = mock(ExecutionHistory.class);
        when(registrar.getExecutionHistory()).thenReturn(history);

        service.clearExecutionHistory("job");

        verify(history).clear("job");
    }

    @Test
    void setExecutionHistory_delegatesToRegistrar() {
        ExecutionHistory history = mock(ExecutionHistory.class);

        service.setExecutionHistory(history);

        verify(registrar).setExecutionHistory(history);
    }

    @Test
    void isPaused_delegatesToRegistrar() {
        when(registrar.isPaused("pausedJob")).thenReturn(true);
        when(registrar.isPaused("liveJob")).thenReturn(false);

        assertTrue(service.isPaused("pausedJob"));
        assertFalse(service.isPaused("liveJob"));

        verify(registrar).isPaused("pausedJob");
        verify(registrar).isPaused("liveJob");
    }

    @Test
    void task_returnsFluentBuilderBoundToThisService() {
        // The builder must not touch the registrar until register() is called.
        assertNotNull(service.task("fluentJob"));
        verifyNoInteractions(registrar);
    }
}
