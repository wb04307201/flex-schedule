package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 7 scenario D5: the actuator endpoint exercised through the real HTTP layer
 * (MockMvc over a mocked servlet web environment). Unlike the Java-level tests in
 * {@link FlexScheduleEndpointTest}, this drives Boot's WebMvcEndpointHandlerMapping,
 * so it is the guard against endpoint-method parameter-name binding regressions
 * (the autoconfigure module must compile with {@code -parameters} — see its POM)
 * and against record-serialization regressions on TaskInfo/TaskDetail.
 *
 * <p>Every "error" outcome below asserts the endpoint's actual representation: the
 * operation methods return error maps rather than throwing, so the HTTP status stays
 * 200 and the body carries the {@code error} field. Missing <em>required</em>
 * operation parameters are the exception — the actuator framework rejects the
 * request with 400 before the endpoint method runs.</p>
 */
class FlexScheduleEndpointHttpTest {

    /** Yearly cron (Jan 1, midnight) so no delegate fires while the suite runs. */
    private static final String SAFE_CRON = "0 0 0 1 1 ?";

    @Nested
    @SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "management.endpoints.web.exposure.include=flexschedule",
            "flex.schedule.endpoint.write-enabled=true"
        })
    @AutoConfigureMockMvc
    class WriteEnabled {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private FlexScheduledTaskService service;

        /** Task names created by the running test, cancelled after each method. */
        private final List<String> created = new ArrayList<>();

        @AfterEach
        void cleanUp() {
            for (String name : created) {
                if (service.exists(name)) {
                    service.cancel(name);
                }
            }
            created.clear();
        }

        @Test
        void getRoot_returnsRegisteredTasksAsJsonArray() throws Exception {
            service.add("http-seed-list", SAFE_CRON, () -> {});
            created.add("http-seed-list");

            mockMvc.perform(get("/actuator/flexschedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.taskName == 'http-seed-list')]").isArray())
                .andExpect(jsonPath("$[?(@.taskName == 'http-seed-list')].taskType")
                    .value(org.hamcrest.Matchers.hasItem("CRON")))
                .andExpect(jsonPath("$[?(@.taskName == 'http-seed-list')].schedule")
                    .value(org.hamcrest.Matchers.hasItem(SAFE_CRON)));
        }

        @Test
        void getDetail_returnsTaskDetailJson() throws Exception {
            service.add("http-seed-detail", SAFE_CRON, () -> {});
            created.add("http-seed-detail");

            mockMvc.perform(get("/actuator/flexschedule/http-seed-detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskName").value("http-seed-detail"))
                .andExpect(jsonPath("$.taskType").value("CRON"))
                .andExpect(jsonPath("$.schedule").value(SAFE_CRON))
                .andExpect(jsonPath("$.oneShot").value(false))
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.createdAt").exists());
        }

        @Test
        void getDetail_unknownTask_returnsNotFoundErrorBodyWithOkStatus() throws Exception {
            // This is the endpoint's real not-found representation: a 200 response whose
            // body carries {"error": "Task [name] not found"} (the operation returns a
            // map instead of null, so the actuator framework cannot turn it into 404).
            mockMvc.perform(get("/actuator/flexschedule/http-ghost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value("Task [http-ghost] not found"));
        }

        @Test
        void post_addCronTask_bindsNamedBodyParamsAndTaskBecomesVisible() throws Exception {
            // This is the -parameters binding proof: every operation parameter name
            // (taskName, taskType, beanName, methodName, cron) must resolve from the
            // JSON body keys. A missing/renamed parameter surfaces as 400, not 200.
            created.add("http-post-cron");
            String body = """
                {"taskName":"http-post-cron","taskType":"CRON",
                 "beanName":"testApplication","methodName":"noOp",
                 "cron":"0 0 0 1 1 ?"}
                """;

            mockMvc.perform(post("/actuator/flexschedule")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("added"))
                .andExpect(jsonPath("$.taskName").value("http-post-cron"));

            assertThat(service.exists("http-post-cron")).isTrue();

            mockMvc.perform(get("/actuator/flexschedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.taskName == 'http-post-cron')].taskType")
                    .value(org.hamcrest.Matchers.hasItem("CRON")));

            mockMvc.perform(get("/actuator/flexschedule/http-post-cron"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskName").value("http-post-cron"))
                .andExpect(jsonPath("$.taskType").value("CRON"));
        }

        @Test
        void post_addFixedRateTask_convertsStringIntervalToLong() throws Exception {
            // intervalSeconds/initialDelaySeconds are Long parameters; the actuator body
            // map is String-valued, so this pins the ConversionService parameter mapping.
            created.add("http-post-rate");
            String body = """
                {"taskName":"http-post-rate","taskType":"FIXED_RATE",
                 "beanName":"testApplication","methodName":"noOp",
                 "intervalSeconds":"3600","initialDelaySeconds":"3600"}
                """;

            mockMvc.perform(post("/actuator/flexschedule")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("added"))
                .andExpect(jsonPath("$.taskName").value("http-post-rate"));

            assertThat(service.exists("http-post-rate")).isTrue();
        }

        @Test
        void post_missingRequiredOperationParameter_returns400() throws Exception {
            // taskName/taskType/beanName/methodName carry no @Nullable → the actuator
            // rejects the invocation before the endpoint method runs.
            String body = """
                {"taskType":"CRON","beanName":"testApplication","methodName":"noOp"}
                """;

            mockMvc.perform(post("/actuator/flexschedule")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest());
        }

        @Test
        void post_invalidCron_returnsEndpointErrorMap() throws Exception {
            // Registration-time failure inside taskService.add must be surfaced by the
            // endpoint as {"error": ...} (200), not thrown through the MVC layer.
            String body = """
                {"taskName":"http-badcron","taskType":"CRON",
                 "beanName":"testApplication","methodName":"noOp",
                 "cron":"not a cron"}
                """;

            mockMvc.perform(post("/actuator/flexschedule")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").exists());

            assertThat(service.exists("http-badcron")).isFalse();
        }

        @Test
        void post_updateTask_selectsFromPathAndActionFromBody() throws Exception {
            // POST /{name} with body {"action": ...} exercises @Selector path binding +
            // body parameter binding together — the updateTask @WriteOperation.
            service.add("http-pause-me", SAFE_CRON, () -> {});
            created.add("http-pause-me");

            mockMvc.perform(post("/actuator/flexschedule/http-pause-me")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"pause\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paused"))
                .andExpect(jsonPath("$.taskName").value("http-pause-me"));
            assertThat(service.isPaused("http-pause-me")).isTrue();

            mockMvc.perform(get("/actuator/flexschedule/http-pause-me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true));

            mockMvc.perform(post("/actuator/flexschedule/http-pause-me")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"resume\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("resumed"));
            assertThat(service.isPaused("http-pause-me")).isFalse();
        }

        @Test
        void delete_existingTask_cancelsItAndItDisappears() throws Exception {
            created.add("http-delete-me");
            String body = """
                {"taskName":"http-delete-me","taskType":"CRON",
                 "beanName":"testApplication","methodName":"noOp",
                 "cron":"0 0 0 1 1 ?"}
                """;
            mockMvc.perform(post("/actuator/flexschedule")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk());

            mockMvc.perform(delete("/actuator/flexschedule/http-delete-me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.taskName").value("http-delete-me"));

            assertThat(service.exists("http-delete-me")).isFalse();
            mockMvc.perform(get("/actuator/flexschedule/http-delete-me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value("Task [http-delete-me] not found"));
        }

        @Test
        void delete_unknownTask_returnsNotFoundErrorBodyWithOkStatus() throws Exception {
            mockMvc.perform(delete("/actuator/flexschedule/http-never-existed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value("Task [http-never-existed] not found"));
        }
    }

    @Nested
    @SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "management.endpoints.web.exposure.include=flexschedule",
            "flex.schedule.endpoint.write-enabled=false"
        })
    @AutoConfigureMockMvc
    class WriteDisabled {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private FlexScheduledTaskService service;

        @Test
        void post_isRejectedWithEndpointForbiddenRepresentation() throws Exception {
            String body = """
                {"taskName":"http-denied","taskType":"CRON",
                 "beanName":"testApplication","methodName":"noOp",
                 "cron":"0 0 0 1 1 ?"}
                """;

            mockMvc.perform(post("/actuator/flexschedule")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error")
                    .value("Write operations are not enabled or bean is not in the allowlist"));

            assertThat(service.exists("http-denied")).isFalse();
        }

        @Test
        void delete_isRejectedWithEndpointForbiddenRepresentation() throws Exception {
            service.add("http-del-denied", SAFE_CRON, () -> {});
            try {
                mockMvc.perform(delete("/actuator/flexschedule/http-del-denied"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.error").value("Write operations are not enabled"));

                assertThat(service.exists("http-del-denied")).isTrue();
            } finally {
                service.cancel("http-del-denied");
            }
        }

        @Test
        void get_stillWorks_regardlessOfWriteEnabled() throws Exception {
            mockMvc.perform(get("/actuator/flexschedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        }
    }
}
