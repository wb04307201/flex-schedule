package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.autoconfigure.endpoint.AddTaskRequest;
import cn.wubo.flex.schedule.autoconfigure.endpoint.DefaultEndpointAccessControl;
import cn.wubo.flex.schedule.autoconfigure.endpoint.FlexScheduleEndpoint;
import cn.wubo.flex.schedule.autoconfigure.endpoint.EndpointAccessControl;
import cn.wubo.flex.schedule.core.DefaultFlexScheduledTaskService;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.SpringContextUtils;
import cn.wubo.flex.schedule.core.TaskDetail;
import cn.wubo.flex.schedule.core.TaskInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class FlexScheduleEndpointTest {

    private FlexScheduleEndpoint endpoint;
    private FlexScheduledTaskService service;
    private FlexScheduledTaskRegistrar registrar;

    @BeforeEach
    void setUp() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("test-endpoint-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        registrar = new FlexScheduledTaskRegistrar(scheduler, 5);
        service = new DefaultFlexScheduledTaskService(registrar);
        // Default: write enabled, no bean restrictions
        EndpointAccessControl accessControl = new DefaultEndpointAccessControl(true, Set.of());
        endpoint = new FlexScheduleEndpoint(service, accessControl);
    }

    @AfterEach
    void tearDown() {
        registrar.destroy();
    }

    // ─── GET /actuator/flexschedule ───────────────────────────────

    @Test
    void listTasks_empty_shouldReturnEmptyList() {
        List<TaskInfo> tasks = endpoint.listTasks();
        assertTrue(tasks.isEmpty());
    }

    @Test
    void listTasks_withTasks_shouldReturnAll() {
        service.add("task1", "0 * * * * *", () -> {});
        service.addFixedDelayTask("task2", 10, 0, () -> {});

        List<TaskInfo> tasks = endpoint.listTasks();
        assertEquals(2, tasks.size());
    }

    // ─── GET /{name} ─────────────────────────────────────────────────

    @Test
    void getTask_found_shouldReturnTaskDetail() {
        service.add("detail", "0 * * * * *", () -> {});
        Object result = endpoint.getTask("detail");

        assertTrue(result instanceof TaskDetail);
        assertEquals("detail", ((TaskDetail) result).taskName());
    }

    @Test
    void getTask_notFound_shouldReturnError() {
        Object result = endpoint.getTask("nope");

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> errorMap = (Map<String, String>) result;
        assertTrue(errorMap.containsKey("error"));
    }

    // ─── POST /actuator/flexschedule ──────────────────────────────

    @Test
    void addTask_missingTaskName_shouldReturnError() {
        Object result = endpoint.addTask(null, "CRON", "testBean", "testMethod",
                "0 * * * * *", null, null, null);
        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> errorMap = (Map<String, String>) result;
        assertTrue(errorMap.containsKey("error"));
    }

    @Test
    void addTask_missingTaskType_shouldReturnError() {
        Object result = endpoint.addTask("task", null, "testBean", "testMethod",
                "0 * * * * *", null, null, null);
        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> errorMap = (Map<String, String>) result;
        assertTrue(errorMap.containsKey("error"));
    }

    @Test
    void addTask_cronWithoutExpression_shouldReturnError() {
        Object result = endpoint.addTask("task", "CRON", "testBean", "testMethod",
                null, null, null, null);
        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> errorMap = (Map<String, String>) result;
        assertTrue(errorMap.containsKey("error"));
    }

    @Test
    void addTask_fixedDelayWithoutInterval_shouldReturnError() {
        Object result = endpoint.addTask("task", "FIXED_DELAY", "testBean", "testMethod",
                null, null, null, null);
        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> errorMap = (Map<String, String>) result;
        assertTrue(errorMap.containsKey("error"));
    }

    @Test
    void addTask_unknownTaskType_shouldReturnError() {
        Object result = endpoint.addTask("task", "UNKNOWN", "testBean", "testMethod",
                null, null, null, null);
        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> errorMap = (Map<String, String>) result;
        assertTrue(errorMap.containsKey("error"));
    }

    // ─── DELETE /{name} ──────────────────────────────────────────────

    @Test
    void cancelTask_existing_shouldReturnSuccess() {
        service.add("toCancel", "0 * * * * *", () -> {});
        Object result = endpoint.cancelTask("toCancel");

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> successMap = (Map<String, String>) result;
        assertEquals("cancelled", successMap.get("status"));
        assertFalse(service.exists("toCancel"));
    }

    @Test
    void cancelTask_notFound_shouldReturnError() {
        Object result = endpoint.cancelTask("nope");

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> errorMap = (Map<String, String>) result;
        assertTrue(errorMap.containsKey("error"));
    }

    // ─── Access Control ──────────────────────────────────────────────

    @Test
    void addTask_writeDisabled_shouldReturnForbidden() {
        EndpointAccessControl disabled = new DefaultEndpointAccessControl(false, Set.of());
        FlexScheduleEndpoint restrictedEndpoint = new FlexScheduleEndpoint(service, disabled);

        Object result = restrictedEndpoint.addTask("task", "CRON", "testBean", "testMethod",
                "0 * * * * *", null, null, null);
        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> errorMap = (Map<String, String>) result;
        assertTrue(errorMap.containsKey("error"));
        assertTrue(errorMap.get("error").contains("not enabled"));
    }

    @Test
    void cancelTask_writeDisabled_shouldReturnForbidden() {
        service.add("task", "0 * * * * *", () -> {});

        EndpointAccessControl disabled = new DefaultEndpointAccessControl(false, Set.of());
        FlexScheduleEndpoint restrictedEndpoint = new FlexScheduleEndpoint(service, disabled);

        Object result = restrictedEndpoint.cancelTask("task");
        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> errorMap = (Map<String, String>) result;
        assertTrue(errorMap.containsKey("error"));
        assertTrue(errorMap.get("error").contains("not enabled"));
    }

    @Test
    void addTask_beanNotInAllowlist_shouldReturnForbidden() {
        EndpointAccessControl allowlist = new DefaultEndpointAccessControl(true, Set.of("allowedBean"));
        FlexScheduleEndpoint restrictedEndpoint = new FlexScheduleEndpoint(service, allowlist);

        Object result = restrictedEndpoint.addTask("task", "CRON", "forbiddenBean", "testMethod",
                "0 * * * * *", null, null, null);
        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> errorMap = (Map<String, String>) result;
        assertTrue(errorMap.containsKey("error"));
        assertTrue(errorMap.get("error").contains("allowlist"));
    }

    @Test
    void addTask_beanInAllowlist_shouldPass() {
        EndpointAccessControl allowlist = new DefaultEndpointAccessControl(true, Set.of("allowedBean"));
        FlexScheduleEndpoint restrictedEndpoint = new FlexScheduleEndpoint(service, allowlist);

        // Will pass access control but may fail at BeanMethodRunnable (bean doesn't exist)
        // We're only testing that access control passes
        Object result = restrictedEndpoint.addTask("task", "CRON", "allowedBean", "testMethod",
                "0 * * * * *", null, null, null);
        // Should NOT contain "allowlist" or "not enabled" error
        if (result instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, String> map = (Map<String, String>) result;
            if (map.containsKey("error")) {
                assertFalse(map.get("error").contains("allowlist"));
                assertFalse(map.get("error").contains("not enabled"));
            }
        }
    }

    @Test
    void getTask_alwaysAllowed_regardlessOfWriteEnabled() {
        service.add("task", "0 * * * * *", () -> {});

        EndpointAccessControl disabled = new DefaultEndpointAccessControl(false, Set.of());
        FlexScheduleEndpoint restrictedEndpoint = new FlexScheduleEndpoint(service, disabled);

        // GET should always work
        Object result = restrictedEndpoint.getTask("task");
        assertTrue(result instanceof TaskDetail);
    }

    @Test
    void listTasks_alwaysAllowed_regardlessOfWriteEnabled() {
        EndpointAccessControl disabled = new DefaultEndpointAccessControl(false, Set.of());
        FlexScheduleEndpoint restrictedEndpoint = new FlexScheduleEndpoint(service, disabled);

        List<TaskInfo> tasks = restrictedEndpoint.listTasks();
        assertNotNull(tasks);
    }

    // ─── pauseTask / resumeTask ───────────────────────────────────

    @Test
    void pauseTask_existingTask_returnsStatusAndPausesIt() {
        service.addFixedDelayTask("p1", 10, 0, () -> {});

        Object result = endpoint.pauseTask("p1");

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("paused", map.get("status"));
        assertEquals("p1", map.get("taskName"));
        assertTrue(registrar.isPaused("p1"));
    }

    @Test
    void pauseTask_nonexistentTask_returnsErrorMap() {
        Object result = endpoint.pauseTask("ghost");

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertTrue(map.get("error").toString().contains("ghost"));
    }

    @Test
    void pauseTask_writeDisabled_returnsError() {
        EndpointAccessControl disabled = new DefaultEndpointAccessControl(false, Set.of());
        FlexScheduleEndpoint restricted = new FlexScheduleEndpoint(service, disabled);
        service.addFixedDelayTask("p2", 10, 0, () -> {});

        Object result = restricted.pauseTask("p2");

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertNotNull(map.get("error"));
        assertFalse(registrar.isPaused("p2"));
    }

    @Test
    void resumeTask_pausedTask_returnsStatusAndResumesIt() {
        service.addFixedDelayTask("r1", 10, 0, () -> {});
        service.pause("r1");
        assertTrue(registrar.isPaused("r1"));

        Object result = endpoint.resumeTask("r1");

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("resumed", map.get("status"));
        assertEquals("r1", map.get("taskName"));
        assertFalse(registrar.isPaused("r1"));
    }

    @Test
    void resumeTask_nonexistentTask_returnsErrorMap() {
        Object result = endpoint.resumeTask("missing");

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertTrue(map.get("error").toString().contains("missing"));
    }

    @Test
    void resumeTask_writeDisabled_returnsError() {
        EndpointAccessControl disabled = new DefaultEndpointAccessControl(false, Set.of());
        FlexScheduleEndpoint restricted = new FlexScheduleEndpoint(service, disabled);
        service.addFixedDelayTask("r2", 10, 0, () -> {});
        service.pause("r2");

        Object result = restricted.resumeTask("r2");

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertNotNull(map.get("error"));
        assertTrue(registrar.isPaused("r2"));
    }

    // ─── addTask validation + error paths ─────────────────────────

    @Test
    void addTask_unknownType_returnsError() {
        Object result = endpoint.addTask("bad", "WIBBLE", "bean", "method",
                null, 10L, 0L, null);

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertNotNull(map.get("error"));
        assertTrue(map.get("error").toString().contains("WIBBLE"));
    }

    @Test
    void addTask_cronMissingCronExpression_returnsError() {
        Object result = endpoint.addTask("c", "CRON", "bean", "method",
                null, null, null, null);

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertNotNull(map.get("error"));
        assertTrue(map.get("error").toString().contains("cron"));
    }

    @Test
    void addTask_fixedDelayMissingInterval_returnsError() {
        Object result = endpoint.addTask("f", "FIXED_DELAY", "bean", "method",
                null, null, null, null);

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertNotNull(map.get("error"));
        assertTrue(map.get("error").toString().contains("intervalSeconds"));
    }

    @Test
    void addTask_fixedDelayZeroInterval_returnsError() {
        Object result = endpoint.addTask("f", "FIXED_DELAY", "bean", "method",
                null, 0L, 0L, null);

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertNotNull(map.get("error"));
        assertTrue(map.get("error").toString().contains("positive"));
    }

    @Test
    void addTask_missingBeanName_returnsError() {
        Object result = endpoint.addTask("f", "FIXED_DELAY", "", "method",
                null, 10L, 0L, null);

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertNotNull(map.get("error"));
        assertTrue(map.get("error").toString().contains("beanName"));
    }

    @Test
    void addTask_unknownBean_succeedsAtRegistration_errorOccursAtExecutionTime() {
        // The endpoint cannot fail at registration for a non-existent bean because
        // BeanMethodRunnable resolves the bean lazily inside run(). The endpoint only
        // surfaces errors that happen synchronously (validation, BeanMethodRunnable
        // construction, schedule call). This test pins the current behavior: a missing
        // bean still produces a "added" response, and the failure surfaces only when the
        // scheduled cron fires and run() can't resolve the bean.
        Object result = endpoint.addTask("ghost", "CRON", "noSuchBean", "noMethod",
                "0 * * * * *", null, null, null);

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("added", map.get("status"));
        assertEquals("ghost", map.get("taskName"));
    }

    @Test
    void addTask_withMethodParams_propagatesParams() throws Exception {
        // D4: real methodParams propagation — the target bean method records the arguments
        // it actually receives. A FIXED_DELAY task with a 1s interval (the smallest positive
        // value the endpoint's seconds-based API allows) and zero initial delay fires almost
        // immediately on the scheduler thread; we await the recorded invocation instead of
        // sleeping a fixed amount, so the assertion is timing-tolerant on slow CI.
        StaticApplicationContext ctx = new StaticApplicationContext();
        ParamSink sink = new ParamSink();
        ctx.getBeanFactory().registerSingleton("paramBean", sink);
        ctx.refresh();

        // SpringContextUtils caches the context set-once semantics; force the swap via
        // reflection so this test does not depend on execution order relative to other
        // classes (same disclosure pattern as TaskLimitsAutoConfigTest), and restore after.
        Field field = SpringContextUtils.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, ctx);
        try {
            Object result = endpoint.addTask("withParams", "FIXED_DELAY", "paramBean", "accept",
                    null, 1L, 0L, List.of("hello", 42));

            assertInstanceOf(Map.class, result);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) result;
            assertEquals("added", map.get("status"));

            assertTrue(sink.awaitFirst(10_000), "target bean method was never invoked within 10s");
            assertEquals(List.of("hello", 42), sink.received.get(0),
                "sink must receive exactly the requested parameter values, in order");
        } finally {
            try { service.cancel("withParams"); } catch (RuntimeException ignored) {}
            field.set(null, previous);
            try { ctx.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    void addTask_withEmptyMethodParams_fallsBackToNoArgConstructorAndStillInvokes() throws Exception {
        // The 8-arg overload branches on methodParams.isEmpty(): empty list must route to
        // BeanMethodRunnable(beanName, methodName) and invoke the no-arg target method.
        StaticApplicationContext ctx = new StaticApplicationContext();
        ParamSink sink = new ParamSink();
        ctx.getBeanFactory().registerSingleton("paramBean", sink);
        ctx.refresh();

        Field field = SpringContextUtils.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, ctx);
        try {
            Object result = endpoint.addTask("noParams", "FIXED_DELAY", "paramBean", "ping",
                    null, 1L, 0L, List.of());

            assertInstanceOf(Map.class, result);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) result;
            assertEquals("added", map.get("status"));

            assertTrue(sink.awaitPing(10_000), "no-arg target method was never invoked within 10s");
            assertTrue(sink.pings > 0);
        } finally {
            try { service.cancel("noParams"); } catch (RuntimeException ignored) {}
            field.set(null, previous);
            try { ctx.close(); } catch (Exception ignored) {}
        }
    }

    public static class ParamSink {
        final List<List<Object>> received = new CopyOnWriteArrayList<>();
        private final java.util.concurrent.CountDownLatch first = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch pinged = new java.util.concurrent.CountDownLatch(1);
        volatile int pings;

        public void accept(String value, Integer count) {
            received.add(List.of(value, count));
            first.countDown();
        }

        public void ping() {
            pings++;
            pinged.countDown();
        }

        boolean awaitFirst(long timeoutMillis) throws InterruptedException {
            return first.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        boolean awaitPing(long timeoutMillis) throws InterruptedException {
            return pinged.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    // ─── D1: 7-arg @WriteOperation addTask (no methodParams) ─────────────

    @Test
    void writeOperationAddTask_cronSuccess_returnsAdded() {
        // Yearly cron (Jan 1) so the delegate never fires during the test window.
        Object result = endpoint.addTask("w-cron", "CRON", "wBean", "wMethod",
                "0 0 0 1 1 ?", null, null);

        assertInstanceOf(Map.class, result);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("added", map.get("status"));
        assertEquals("w-cron", map.get("taskName"));
        assertTrue(service.exists("w-cron"));
    }

    @Test
    void writeOperationAddTask_missingTaskName_returnsError() {
        Object result = endpoint.addTask(null, "CRON", "wBean", "wMethod", "0 0 0 1 1 ?", null, null);
        assertEquals("taskName is required", errorOf(result));
    }

    @Test
    void writeOperationAddTask_missingTaskType_returnsError() {
        Object result = endpoint.addTask("w-t", null, "wBean", "wMethod", "0 0 0 1 1 ?", null, null);
        assertEquals("taskType is required", errorOf(result));
    }

    @Test
    void writeOperationAddTask_missingBeanName_returnsError() {
        Object result = endpoint.addTask("w-t", "CRON", "", "wMethod", "0 0 0 1 1 ?", null, null);
        assertEquals("beanName is required", errorOf(result));
    }

    @Test
    void writeOperationAddTask_missingMethodName_returnsError() {
        Object result = endpoint.addTask("w-t", "CRON", "wBean", " ", "0 0 0 1 1 ?", null, null);
        assertEquals("methodName is required", errorOf(result));
    }

    @Test
    void writeOperationAddTask_cronWithoutExpression_returnsError() {
        Object result = endpoint.addTask("w-t", "CRON", "wBean", "wMethod", null, null, null);
        assertEquals("cron is required for CRON tasks", errorOf(result));
    }

    @Test
    void writeOperationAddTask_fixedDelayMissingInterval_returnsError() {
        Object result = endpoint.addTask("w-t", "FIXED_DELAY", "wBean", "wMethod", null, null, null);
        assertEquals("intervalSeconds is required for FIXED_DELAY/FIXED_RATE tasks", errorOf(result));
    }

    @Test
    void writeOperationAddTask_fixedDelayZeroInterval_returnsError() {
        Object result = endpoint.addTask("w-t", "FIXED_DELAY", "wBean", "wMethod", null, 0L, null);
        assertEquals("intervalSeconds must be positive", errorOf(result));
    }

    @Test
    void writeOperationAddTask_fixedDelayNegativeInterval_returnsError() {
        Object result = endpoint.addTask("w-t", "FIXED_DELAY", "wBean", "wMethod", null, -1L, 0L);
        assertEquals("intervalSeconds must be positive", errorOf(result));
    }

    @Test
    void writeOperationAddTask_unknownTaskType_returnsError() {
        Object result = endpoint.addTask("w-t", "WIBBLE", "wBean", "wMethod", null, null, null);
        assertEquals("Unknown taskType: WIBBLE. Must be CRON, FIXED_DELAY, or FIXED_RATE", errorOf(result));
    }

    @Test
    void writeOperationAddTask_writeDisabled_returnsForbidden() {
        FlexScheduleEndpoint restricted =
            new FlexScheduleEndpoint(service, new DefaultEndpointAccessControl(false, Set.of()));

        Object result = restricted.addTask("w-t", "CRON", "wBean", "wMethod", "0 0 0 1 1 ?", null, null);
        assertEquals("Write operations are not enabled or bean is not in the allowlist", errorOf(result));
    }

    @Test
    void writeOperationAddTask_beanNotInAllowlist_returnsForbidden() {
        FlexScheduleEndpoint restricted =
            new FlexScheduleEndpoint(service, new DefaultEndpointAccessControl(true, Set.of("otherBean")));

        Object result = restricted.addTask("w-t", "CRON", "wBean", "wMethod", "0 0 0 1 1 ?", null, null);
        assertEquals("Write operations are not enabled or bean is not in the allowlist", errorOf(result));
    }

    @Test
    void writeOperationAddTask_duplicateTaskName_returnsRegistrarError() {
        service.add("w-dup", "0 0 0 1 1 ?", () -> {});

        Object result = endpoint.addTask("w-dup", "CRON", "wBean", "wMethod", "0 0 0 1 1 ?", null, null);
        assertTrue(errorOf(result).contains("w-dup"),
            "duplicate registration error must surface the task name, was: " + errorOf(result));
    }

    // ─── D2: updateTask(name, action) full branch matrix ───────────────────

    @Test
    void updateTask_pause_returnsPausedAndPausesRegistrar() {
        service.addFixedDelayTask("u-p", 3600, 0, () -> {});

        Object result = endpoint.updateTask("u-p", "pause");

        assertInstanceOf(Map.class, result);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("paused", map.get("status"));
        assertEquals("u-p", map.get("taskName"));
        assertTrue(registrar.isPaused("u-p"));
    }

    @Test
    void updateTask_pauseIsCaseInsensitive() {
        service.addFixedDelayTask("u-c", 3600, 0, () -> {});

        Object result = endpoint.updateTask("u-c", "PAUSE");

        assertEquals("paused", asMap(result).get("status"));
        assertTrue(registrar.isPaused("u-c"));
    }

    @Test
    void updateTask_resume_returnsResumedAndUnpausesRegistrar() {
        service.addFixedDelayTask("u-r", 3600, 0, () -> {});
        service.pause("u-r");
        assertTrue(registrar.isPaused("u-r"));

        Object result = endpoint.updateTask("u-r", "resume");

        assertEquals("resumed", asMap(result).get("status"));
        assertEquals("u-r", asMap(result).get("taskName"));
        assertFalse(registrar.isPaused("u-r"));
    }

    @Test
    void updateTask_nullAction_returnsRequiredError() {
        service.addFixedDelayTask("u-n", 3600, 0, () -> {});

        Object result = endpoint.updateTask("u-n", null);

        assertEquals("action is required: pause | resume", errorOf(result));
        assertFalse(registrar.isPaused("u-n"));
    }

    @Test
    void updateTask_unknownAction_returnsError() {
        service.addFixedDelayTask("u-u", 3600, 0, () -> {});

        Object result = endpoint.updateTask("u-u", "wiggle");

        assertEquals("Unknown action: wiggle", errorOf(result));
        assertFalse(registrar.isPaused("u-u"));
    }

    @Test
    void updateTask_writeDisabled_returnsForbiddenWithoutPausing() {
        service.addFixedDelayTask("u-d", 3600, 0, () -> {});
        FlexScheduleEndpoint restricted =
            new FlexScheduleEndpoint(service, new DefaultEndpointAccessControl(false, Set.of()));

        Object result = restricted.updateTask("u-d", "pause");

        assertEquals("Write operations are not enabled", errorOf(result));
        assertFalse(registrar.isPaused("u-d"));
    }

    @Test
    void updateTask_taskNotFound_returnsNotFoundError() {
        Object result = endpoint.updateTask("u-ghost", "pause");

        assertEquals("Task [u-ghost] not found", errorOf(result));
    }

    @Test
    void updateTask_resumeNonexistent_returnsNotFoundError() {
        Object result = endpoint.updateTask("u-ghost2", "resume");

        assertEquals("Task [u-ghost2] not found", errorOf(result));
    }

    // ─── D3: FIXED_RATE branches ───────────────────────────────────────────

    @Test
    void addTask_fixedRate_success_returnsAdded() {
        // 3600s initial delay keeps the first fire outside the test window (the
        // unresolvable bean name would otherwise throw on an immediate execution).
        Object result = endpoint.addTask("fr-1", "FIXED_RATE", "frBean", "frMethod",
                null, 3600L, 3600L, null);

        assertEquals("added", asMap(result).get("status"));
        assertEquals("fr-1", asMap(result).get("taskName"));
        assertTrue(service.exists("fr-1"));
        assertEquals("FIXED_RATE", service.getTaskDetail("fr-1").orElseThrow().taskType());
    }

    @Test
    void addTask_fixedRate_negativeInterval_returnsError() {
        Object result = endpoint.addTask("fr-2", "FIXED_RATE", "frBean", "frMethod",
                null, -1L, 0L, null);

        assertEquals("intervalSeconds must be positive", errorOf(result));
    }

    @Test
    void addTask_fixedRate_blankMethodName_returnsError() {
        Object result = endpoint.addTask("fr-3", "FIXED_RATE", "frBean", "   ",
                null, 10L, 0L, null);

        assertEquals("methodName is required", errorOf(result));
    }

    @Test
    void addTask_fixedRate_missingMethodName_returnsError() {
        Object result = endpoint.addTask("fr-4", "FIXED_RATE", "frBean", null,
                null, 10L, 0L, null);

        assertEquals("methodName is required", errorOf(result));
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object result) {
        assertInstanceOf(Map.class, result);
        return (Map<String, Object>) result;
    }

    private static String errorOf(Object result) {
        Object error = asMap(result).get("error");
        assertNotNull(error, "expected an error entry but got: " + result);
        return error.toString();
    }

    // ─── D6: DefaultEndpointAccessControl contract (dedicated) ─────────────

    @Nested
    class DefaultEndpointAccessControlContract {

        @Test
        void writeDisabled_deniesAddAndCancel() {
            DefaultEndpointAccessControl control = new DefaultEndpointAccessControl(false, Set.of());

            assertFalse(control.canAddTask("anyBean", "anyMethod"));
            assertFalse(control.canCancelTask("anyTask"));
        }

        @Test
        void writeEnabledWithEmptyAllowlist_admitsEverything() {
            DefaultEndpointAccessControl control = new DefaultEndpointAccessControl(true, Set.of());

            assertTrue(control.canAddTask("anyBean", "anyMethod"));
            assertTrue(control.canAddTask("otherBean", "otherMethod"));
            assertTrue(control.canCancelTask("anyTask"));
        }

        @Test
        void nonEmptyAllowlist_gatesAddOnly_byBeanName() {
            DefaultEndpointAccessControl control =
                new DefaultEndpointAccessControl(true, Set.of("allowedBean"));

            assertTrue(control.canAddTask("allowedBean", "anyMethod"), "listed bean must pass");
            assertFalse(control.canAddTask("otherBean", "anyMethod"), "unlisted bean must be denied");
            // methodName is never part of the allowlist decision
            assertFalse(control.canAddTask("otherBean", "allowedBean"));
            // canCancelTask ignores the allowlist — write-enabled alone unlocks it
            assertTrue(control.canCancelTask("whatever"));
        }

        @Test
        void nullAllowedBeans_treatedAsEmpty_noNpe() {
            DefaultEndpointAccessControl control = new DefaultEndpointAccessControl(true, null);

            assertTrue(control.canAddTask("anyBean", "anyMethod"));
            assertTrue(control.canCancelTask("anyTask"));
        }

        @Test
        void nullAllowedBeansWithWriteDisabled_stillDenies_noNpe() {
            DefaultEndpointAccessControl control = new DefaultEndpointAccessControl(false, null);

            assertFalse(control.canAddTask("anyBean", "anyMethod"));
            assertFalse(control.canCancelTask("anyTask"));
        }
    }

    // ─── D7: AddTaskRequest round-trip ─────────────────────────────────────

    @Nested
    class AddTaskRequestRoundTrip {

        @Test
        void allFields_roundTrip() {
            List<Object> params = List.of("a", 1, true);
            AddTaskRequest request = new AddTaskRequest();
            request.setTaskName("t1");
            request.setTaskType("CRON");
            request.setCron("0 * * * * *");
            request.setIntervalSeconds(60L);
            request.setInitialDelaySeconds(5L);
            request.setBeanName("demoBean");
            request.setMethodName("demoMethod");
            request.setMethodParams(params);

            assertEquals("t1", request.getTaskName());
            assertEquals("CRON", request.getTaskType());
            assertEquals("0 * * * * *", request.getCron());
            assertEquals(60L, request.getIntervalSeconds());
            assertEquals(5L, request.getInitialDelaySeconds());
            assertEquals("demoBean", request.getBeanName());
            assertEquals("demoMethod", request.getMethodName());
            assertSame(params, request.getMethodParams());
            assertEquals(List.of("a", 1, true), request.getMethodParams());
        }

        @Test
        void freshInstance_allFieldsNull() {
            AddTaskRequest request = new AddTaskRequest();

            assertNull(request.getTaskName());
            assertNull(request.getTaskType());
            assertNull(request.getCron());
            assertNull(request.getIntervalSeconds());
            assertNull(request.getInitialDelaySeconds());
            assertNull(request.getBeanName());
            assertNull(request.getMethodName());
            assertNull(request.getMethodParams());
        }
    }
}
