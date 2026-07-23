package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the {@code TaskBuilder.createdAt(Instant)} hook used by persistence-aware
 * consumers (e.g. spring-ai-loom-agent's ScheduleRestoreListener) to preserve a task's
 * logical lifetime across application restarts.
 *
 * <p>Without an explicit {@code createdAt}, the registrar stamps every freshly
 * scheduled task with {@code Instant.now()} — which resets the {@link TaskLimits}
 * max-lifetime clock on every restart. This suite pins the contract that a builder
 * CAN supply an explicit createdAt and the registrar honors it.</p>
 */
class TaskBuilderCreatedAtTest {

    private ThreadPoolTaskScheduler taskScheduler;
    private FlexScheduledTaskRegistrar registrar;
    private FlexScheduledTaskService service;

    @BeforeEach
    void setUp() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.setThreadNamePrefix("test-builder-created-at-");
        taskScheduler.setRemoveOnCancelPolicy(true);
        taskScheduler.initialize();
        registrar = new FlexScheduledTaskRegistrar(taskScheduler, 5);
        service = new DefaultFlexScheduledTaskService(registrar);
    }

    @AfterEach
    void tearDown() {
        registrar.destroy();
    }

    /**
     * Red-phase pin: registering a fixed-delay task with an explicit createdAt
     * 60 hours in the past must result in {@code TaskDetail.createdAt()} equal to
     * that instant — proving the builder-time override wins over {@code Instant.now()}.
     */
    @Test
    void builder_createdAt_overridesDefaultInstantNow() {
        Instant sixtyHoursAgo = Instant.now().minus(Duration.ofHours(60));
        service.task("futureRestartTask")
                .fixedDelay(Duration.ofSeconds(30))
                .createdAt(sixtyHoursAgo)
                .register(() -> {});

        TaskDetail detail = service.getTaskDetail("futureRestartTask").orElseThrow();
        assertEquals(sixtyHoursAgo, detail.createdAt(),
                "explicit createdAt must be honored on TaskDetail");
    }

    /**
     * Without an explicit createdAt the registrar falls back to {@code Instant.now()},
     * which is the existing behavior. This pins the contract so a future refactor
     * does not accidentally stop defaulting.
     */
    @Test
    void builder_noCreatedAt_defaultsToInstantNow() {
        Instant beforeRegister = Instant.now();
        service.task("defaultCreatedAtTask")
                .fixedDelay(Duration.ofSeconds(30))
                .register(() -> {});
        Instant afterRegister = Instant.now();

        TaskDetail detail = service.getTaskDetail("defaultCreatedAtTask").orElseThrow();
        Instant createdAt = detail.createdAt();
        assertTrue(!createdAt.isBefore(beforeRegister) && !createdAt.isAfter(afterRegister),
                "default createdAt must fall within the [beforeRegister, afterRegister] window");
    }

    /**
     * Cron tasks must honor the createdAt override too (same code path through
     * {@code TaskBuilder.register()} → {@code service.add(...)} → registrar).
     */
    @Test
    void builder_createdAt_withCron_shouldBeHonored() {
        Instant fortyEightHoursAgo = Instant.now().minus(Duration.ofHours(48));
        service.task("cronRestartTask")
                .cron("0 0 * * * *")
                .createdAt(fortyEightHoursAgo)
                .register(() -> {});

        TaskDetail detail = service.getTaskDetail("cronRestartTask").orElseThrow();
        assertEquals(fortyEightHoursAgo, detail.createdAt());
    }

    /**
     * One-shot tasks too: a one_shot that fire-restarts should still expose
     * the original {@code createdAt} so lifetime math works consistently.
     */
    @Test
    void builder_createdAt_withOneShot_shouldBeHonored() {
        Instant fixedInstant = Instant.now().minus(Duration.ofMinutes(30));
        service.task("oneShotRestartTask")
                .oneShot(Duration.ofSeconds(10))
                .createdAt(fixedInstant)
                .register(() -> {});

        TaskDetail detail = service.getTaskDetail("oneShotRestartTask").orElseThrow();
        assertEquals(fixedInstant, detail.createdAt());
    }

    /**
     * Fluent setter contract: {@code createdAt(...)} must return {@code this}
     * so it can be chained like other builder methods.
     */
    @Test
    void builder_createdAt_isFluent() {
        TaskBuilder b = service.task("fluentCheck")
                .fixedDelay(Duration.ofSeconds(30));
        // The compiler-enforced return is the proof; an explicit smoke test confirms runtime.
        assertSame(b, b.createdAt(Instant.now()),
                "createdAt(...) must return the same builder instance for chaining");
    }
}
