package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API-layer tests for the 1.3.0 misfire support on {@link TaskDefinition} and
 * {@link TaskBuilder}: new components, defaults, backward-compatible
 * 15-argument constructor, fluent setters, and validation behavior.
 * <p>
 * These tests pin the API contract only; runtime misfire behavior is covered
 * by the registrar tests (Task 3).
 * </p>
 */
class MisfirePolicyApiTest {

    private ThreadPoolTaskScheduler taskScheduler;
    private FlexScheduledTaskRegistrar registrar;
    private FlexScheduledTaskService service;

    @BeforeEach
    void setUp() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.setThreadNamePrefix("test-misfire-api-");
        taskScheduler.setRemoveOnCancelPolicy(true);
        taskScheduler.initialize();
        registrar = new FlexScheduledTaskRegistrar(taskScheduler, 5);
        service = new DefaultFlexScheduledTaskService(registrar);
    }

    @AfterEach
    void tearDown() {
        registrar.destroy();
    }

    // ─── 1. backward compatibility: old 15-arg canonical constructor ───────

    @Test
    void oldFifteenArgConstructor_stillCompiles_andGetsDefaults() {
        Instant now = Instant.now();
        TaskDefinition def = new TaskDefinition(
                "legacyTask",          // taskName
                "CRON",                // taskType
                "0 * * * * *",         // cronExpression
                ZoneId.of("UTC"),      // timezone
                null,                  // interval
                null,                  // initialDelay
                null,                  // delay
                null,                  // retryPolicy
                null,                  // timeout
                "demoBean",            // beanName
                "runIt",               // methodName
                List.of(),             // methodParams
                false,                 // paused
                now,                   // createdAt
                now                    // updatedAt
        );

        assertEquals(MisfirePolicy.FIRE_ONCE, def.misfirePolicy());
        assertEquals(Duration.ofSeconds(30), def.misfireThreshold());
        // Pin the constant values the compat constructor delegates with.
        assertEquals(MisfirePolicy.FIRE_ONCE, TaskDefinition.DEFAULT_MISFIRE_POLICY);
        assertEquals(Duration.ofSeconds(30), TaskDefinition.DEFAULT_MISFIRE_THRESHOLD);
    }

    // ─── 2. definition builder defaults when misfire setters are unused ────

    @Test
    void builder_defaults() {
        TaskDefinition def = TaskDefinition.builder("defaultMisfire", "CRON")
                .cronExpression("0 * * * * *")
                .build();

        assertEquals(TaskDefinition.DEFAULT_MISFIRE_POLICY, def.misfirePolicy());
        assertEquals(MisfirePolicy.FIRE_ONCE, def.misfirePolicy());
        assertEquals(TaskDefinition.DEFAULT_MISFIRE_THRESHOLD, def.misfireThreshold());
        assertEquals(Duration.ofSeconds(30), def.misfireThreshold());
    }

    // ─── 3. fluent setters transmit values; other fields unaffected ───────

    @Test
    void builder_fluentSetters() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant updated = Instant.parse("2026-01-02T00:00:00Z");
        RetryPolicy retry = RetryPolicy.fixed(2, Duration.ofMillis(50));

        TaskDefinition def = TaskDefinition.builder("fluentMisfire", "FIXED_DELAY")
                .cronExpression("0 * * * * *")
                .timezone(ZoneId.of("Asia/Shanghai"))
                .interval(Duration.ofSeconds(5))
                .initialDelay(Duration.ofSeconds(1))
                .delay(Duration.ofSeconds(2))
                .retryPolicy(retry)
                .timeout(Duration.ofSeconds(9))
                .beanName("someBean")
                .methodName("someMethod")
                .methodParams(List.of("a", 1))
                .paused(true)
                .createdAt(created)
                .updatedAt(updated)
                .misfirePolicy(MisfirePolicy.SKIP)
                .misfireThreshold(Duration.ofMillis(500))
                .build();

        assertEquals(MisfirePolicy.SKIP, def.misfirePolicy());
        assertEquals(Duration.ofMillis(500), def.misfireThreshold());

        // Other fields must be untouched by the new setters.
        assertEquals("fluentMisfire", def.taskName());
        assertEquals("FIXED_DELAY", def.taskType());
        assertEquals("0 * * * * *", def.cronExpression());
        assertEquals(ZoneId.of("Asia/Shanghai"), def.timezone());
        assertEquals(Duration.ofSeconds(5), def.interval());
        assertEquals(Duration.ofSeconds(1), def.initialDelay());
        assertEquals(Duration.ofSeconds(2), def.delay());
        assertSame(retry, def.retryPolicy());
        assertEquals(Duration.ofSeconds(9), def.timeout());
        assertEquals("someBean", def.beanName());
        assertEquals("someMethod", def.methodName());
        assertEquals(List.of("a", 1), def.methodParams());
        assertTrue(def.paused());
        assertEquals(created, def.createdAt());
        assertEquals(updated, def.updatedAt());
    }

    @Test
    void taskBuilder_misfireSetters_areFluentAndDoNotChangeRegistration() {
        TaskBuilder builder = service.task("misfireApiTask")
                .cron("0 * * * * *")
                .misfirePolicy(MisfirePolicy.SKIP)
                .misfireThreshold(Duration.ofSeconds(5));

        // Fluent API returns the same builder instance.
        assertSame(builder, builder.misfirePolicy(MisfirePolicy.FIRE_ONCE));
        assertSame(builder, builder.misfireThreshold(Duration.ofSeconds(7)));

        builder.register(() -> {});
        assertTrue(service.exists("misfireApiTask"));
    }

    // ─── 4. null policy / null threshold rejected at the setter ────────────

    @Test
    void builder_nullPolicyOrThreshold_throws() {
        // Validation style follows the project record idiom (Assert.notNull
        // -> IllegalArgumentException), cf. RetryPolicy.
        assertThrows(IllegalArgumentException.class,
                () -> TaskDefinition.builder("t", "CRON").misfirePolicy(null));
        assertThrows(IllegalArgumentException.class,
                () -> TaskDefinition.builder("t", "CRON").misfireThreshold(null));
    }

    // ─── 5. threshold must be strictly positive ────────────────────────────

    @Test
    void threshold_mustBePositive() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> new TaskDefinition(
                "t", "CRON", null, null, null, null, null, null, null,
                "b", "m", List.of(), false, now, now,
                MisfirePolicy.SKIP, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new TaskDefinition(
                "t", "CRON", null, null, null, null, null, null, null,
                "b", "m", List.of(), false, now, now,
                MisfirePolicy.SKIP, Duration.ZERO));
    }

    @Test
    void compactConstructor_nullPolicy_fallsBackToDefault() {
        Instant now = Instant.now();
        TaskDefinition def = new TaskDefinition(
                "t", "CRON", null, null, null, null, null, null, null,
                "b", "m", List.of(), false, now, now,
                null, Duration.ofSeconds(1));
        assertEquals(MisfirePolicy.FIRE_ONCE, def.misfirePolicy());
    }
}
