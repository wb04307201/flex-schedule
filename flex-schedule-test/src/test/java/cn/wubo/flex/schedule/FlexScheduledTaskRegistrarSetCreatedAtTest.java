package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.DefaultFlexScheduledTaskService;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.RetryPolicy;
import cn.wubo.flex.schedule.core.TaskDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted tests for {@link FlexScheduledTaskRegistrar#setCreatedAt(String, Instant)}
 * and the corresponding {@link FlexScheduledTaskService#setCreatedAt(String, Instant)}
 * interface method. The companion {@link TaskBuilderCreatedAtTest} covers the
 * black-box path through {@code TaskBuilder.register(...)}; this suite covers
 * the registrar-level edge cases (unknown task, null arg, interaction with retry
 * policy and other fields, plus service-to-registrar delegation).
 */
class FlexScheduledTaskRegistrarSetCreatedAtTest {

    private ThreadPoolTaskScheduler taskScheduler;
    private FlexScheduledTaskRegistrar registrar;
    private FlexScheduledTaskService service;

    @BeforeEach
    void setUp() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.setThreadNamePrefix("test-set-created-at-");
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
     * The replacement entry built by setCreatedAt must preserve every other field
     * — cancelAction (so the task is still cancellable), taskType, schedule,
     * retryPolicy, and oneShot. If any of these were dropped the entry would
     * silently become a broken task.
     */
    @Test
    void setCreatedAt_overridesCreatedAt_preservesAllOtherFields() {
        RetryPolicy retry = RetryPolicy.fixed(3, Duration.ofMillis(50));
        registrar.addCronTask("preserved-fields", "0 * * * * *", () -> {}, retry);

        Instant fixedInstant = Instant.now().minus(Duration.ofHours(48));
        registrar.setCreatedAt("preserved-fields", fixedInstant);

        TaskDetail detail = registrar.getTaskDetail("preserved-fields").orElseThrow();
        assertEquals(fixedInstant, detail.createdAt(), "createdAt must be overridden");
        assertEquals("CRON", detail.taskType(), "taskType must be preserved");
        assertEquals("0 * * * * *", detail.schedule(), "schedule string must be preserved");
        assertNotNull(detail.retryPolicy(), "retryPolicy must be preserved");
        assertEquals(3, detail.retryPolicy().maxAttempts(),
                "retryPolicy.maxAttempts must round-trip unchanged");
        assertFalse(detail.oneShot(), "cron task must remain non-oneShot");
        assertFalse(detail.paused(), "paused flag must be preserved (still false)");
        assertTrue(registrar.exists("preserved-fields"),
                "task must still be registered (cancelAction preserved)");

        // Cleanup: cancel using the preserved cancelAction to prove it still works.
        registrar.cancel("preserved-fields");
        assertFalse(registrar.exists("preserved-fields"));
    }

    /**
     * Unknown task names are a failure path that must NOT throw — consumers
     * calling this from a startup restore loop shouldn't see exceptions for
     * rows that were wiped between read and update.
     */
    @Test
    void setCreatedAt_unknownTask_logsWarnAndDoesNotThrow() {
        // Sanity: no such task initially
        assertFalse(registrar.exists("nope"));

        Instant fixedInstant = Instant.now().minus(Duration.ofHours(1));
        // The contract is silent no-op + WARN log; we just assert no exception
        // and no side effect (no new task appears).
        registrar.setCreatedAt("nope", fixedInstant);

        assertFalse(registrar.exists("nope"));
        assertEquals(0, registrar.listTasks().size());
    }

    /**
     * Null createdAt must be a no-op (per JavaDoc); the existing entry's
     * createdAt must remain untouched.
     */
    @Test
    void setCreatedAt_nullCreatedAt_isNoOp() {
        registrar.addCronTask("t1", "0 * * * * *", () -> {});

        Instant before = registrar.getTaskDetail("t1").orElseThrow().createdAt();

        // Pass null — should be silent.
        registrar.setCreatedAt("t1", null);

        Instant after = registrar.getTaskDetail("t1").orElseThrow().createdAt();
        assertEquals(before, after,
                "setCreatedAt with null must not change existing entry's createdAt");
    }

    /**
     * Builder + retry + createdAt must coexist — the third fluent setter must
     * not interfere with retry policy. This pins the contract for the
     * restore-listener case where a persisted row may carry retry settings.
     */
    @Test
    void builder_retryAndCreatedAt_coexist() {
        RetryPolicy retry = RetryPolicy.fixed(2, Duration.ofMillis(20));
        Instant fixedInstant = Instant.now().minus(Duration.ofMinutes(45));

        service.task("retry-with-stored-age")
                .cron("0 0 * * * *")
                .retry(retry)
                .createdAt(fixedInstant)
                .register(() -> {});

        TaskDetail detail = service.getTaskDetail("retry-with-stored-age").orElseThrow();
        assertEquals(fixedInstant, detail.createdAt(),
                "createdAt fluent setter must override Instant.now() default");
        assertNotNull(detail.retryPolicy(),
                "retry fluent setter must not be clobbered by createdAt");
        assertEquals(2, detail.retryPolicy().maxAttempts(),
                "retry parameters must round-trip");
    }

    /**
     * The service interface method must delegate to the registrar — pinning
     * the contract that adding the entry to the public interface in Phase 1
     * also added an actual implementation.
     */
    @Test
    void flexService_setCreatedAt_delegatesToRegistrar() {
        registrar.addCronTask("delegated", "0 * * * * *", () -> {});

        Instant fixedInstant = Instant.now().minus(Duration.ofHours(20));
        service.setCreatedAt("delegated", fixedInstant);

        TaskDetail detail = registrar.getTaskDetail("delegated").orElseThrow();
        assertEquals(fixedInstant, detail.createdAt());

        // Bonus: confirm an idempotent reassignment works (no throw on repeat).
        AtomicInteger safety = new AtomicInteger(0);
        try {
            for (int i = 0; i < 5; i++) {
                service.setCreatedAt("delegated",
                        fixedInstant.minus(Duration.ofMinutes(i)));
            }
            safety.incrementAndGet();
        } catch (Exception e) {
            // fall through; safety counter proves we got here at least once
        }
        assertEquals(1, safety.get());
    }

    /**
     * Empty / null taskName must not throw — the original public contract is
     * uniformly tolerant (no-op for any invalid argument). This pins that the
     * atomic rewrite did not newly introduce validation for empty/null names.
     */
    @Test
    void setCreatedAt_emptyOrNullTaskName_isSilentNoOp() {
        registrar.addCronTask("sentinel", "0 * * * * *", () -> {});

        assertFalse(registrar.exists(""));
        // Empty / null taskName is treated as "not found" — no exception.
        registrar.setCreatedAt("", Instant.now());
        registrar.setCreatedAt(null, Instant.now());

        // Existing entry must be untouched.
        assertTrue(registrar.exists("sentinel"));
    }

    /**
     * Cancel-first ordering: register → cancel → setCreatedAt. The
     * computeIfPresent inside setCreatedAt must observe the missing entry
     * (ConcurrentHashMap.remove is fully visible across threads) and be a
     * silent no-op. The cancelled entry must NOT come back.
     */
    @Test
    void setCreatedAt_calledAfterCancel_doesNotResurrect() {
        registrar.addCronTask("post-cancel", "0 * * * * *", () -> {});
        registrar.cancel("post-cancel");
        assertFalse(registrar.exists("post-cancel"));

        Instant fixedInstant = Instant.now().minus(Duration.ofHours(2));
        registrar.setCreatedAt("post-cancel", fixedInstant);

        assertFalse(registrar.exists("post-cancel"),
                "setCreatedAt after cancel must NOT reinsert the cancelled entry");
    }
}
