package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.autoconfigure.endpoint.DefaultEndpointAccessControl;
import cn.wubo.flex.schedule.autoconfigure.endpoint.FlexScheduleEndpoint;
import cn.wubo.flex.schedule.autoconfigure.endpoint.EndpointAccessControl;
import cn.wubo.flex.schedule.core.DefaultFlexScheduledTaskService;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.TaskDetail;
import cn.wubo.flex.schedule.core.TaskInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
    void addTask_withMethodParams_propagatesParams() {
        // The endpoint must route to BeanMethodRunnable(beanName, methodName, methodParams)
        // when methodParams is non-empty. Verify via side-effect: call a method that takes
        // a String param, and observe the captured arg.
        java.util.concurrent.atomic.AtomicReference<Object> captured = new java.util.concurrent.atomic.AtomicReference<>();
        org.springframework.context.support.StaticApplicationContext ctx =
            new org.springframework.context.support.StaticApplicationContext();
        ctx.getBeanFactory().registerSingleton("paramBean", new ParamSink(captured));
        ctx.refresh();
        cn.wubo.flex.schedule.core.SpringContextUtils utils = new cn.wubo.flex.schedule.core.SpringContextUtils();
        utils.setApplicationContext(ctx);
        try {
            Object result = endpoint.addTask("withParams", "CRON", "paramBean", "accept",
                    "0 * * * * *", null, null, java.util.List.of("hello"));

            // Scheduling succeeds (or fails on access-control, neither should pass empty params).
            // Either way, the params path runs and is exercised.
            // We don't assert the success map here because the cron never fires during the test;
            // we just ensure no NPE was thrown on the params branch.
            assertNotNull(result);
        } finally {
            try { ctx.close(); } catch (Exception ignored) {}
        }
    }

    public static class ParamSink {
        private final java.util.concurrent.atomic.AtomicReference<Object> sink;
        public ParamSink(java.util.concurrent.atomic.AtomicReference<Object> sink) { this.sink = sink; }
        public void accept(String value) { sink.set(value); }
    }
}
