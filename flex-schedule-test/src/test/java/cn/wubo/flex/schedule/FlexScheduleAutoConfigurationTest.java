package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.autoconfigure.FlexScheduleAutoConfiguration;
import cn.wubo.flex.schedule.core.ExecutionHistory;
import cn.wubo.flex.schedule.core.ExecutionRecord;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.InMemoryExecutionHistory;
import cn.wubo.flex.schedule.core.SpringContextUtils;
import cn.wubo.flex.schedule.core.TaskDefinition;
import cn.wubo.flex.schedule.core.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class FlexScheduleAutoConfigurationTest {

    @Nested
    @SpringBootTest(classes = TestApplication.class)
    class WhenEnabled {

        @Autowired
        private ApplicationContext context;

        @Test
        void threadPoolTaskScheduler_isCreated() {
            assertThat(context.containsBean("flexScheduleThreadPoolTaskScheduler")).isTrue();
            assertThat(context.getBean("flexScheduleThreadPoolTaskScheduler"))
                    .isInstanceOf(ThreadPoolTaskScheduler.class);
        }

        @Test
        void registrar_isCreated() {
            assertThat(context.containsBean("flexScheduledTaskRegistrar")).isTrue();
            assertThat(context.getBean("flexScheduledTaskRegistrar"))
                    .isInstanceOf(FlexScheduledTaskRegistrar.class);
        }

        @Test
        void service_isCreated() {
            assertThat(context.containsBean("flexScheduledTaskService")).isTrue();
            assertThat(context.getBean("flexScheduledTaskService"))
                    .isInstanceOf(FlexScheduledTaskService.class);
        }

        @Test
        void springContextUtils_isCreated() {
            assertThat(context.containsBean("flexScheduleSpringContextUtils")).isTrue();
            assertThat(context.getBean("flexScheduleSpringContextUtils"))
                    .isInstanceOf(SpringContextUtils.class);
        }
    }

    @Nested
    @SpringBootTest(classes = TestApplication.class, properties = "flex.schedule.enabled=false")
    class WhenDisabled {

        @Autowired
        private ApplicationContext context;

        @Test
        void noScheduler_isCreated() {
            assertThat(context.containsBean("flexScheduleThreadPoolTaskScheduler")).isFalse();
        }

        @Test
        void noRegistrar_isCreated() {
            assertThat(context.containsBean("flexScheduledTaskRegistrar")).isFalse();
        }

        @Test
        void noService_isCreated() {
            assertThat(context.containsBean("flexScheduledTaskService")).isFalse();
        }

        @Test
        void noSpringContextUtils_isCreated() {
            assertThat(context.containsBean("flexScheduleSpringContextUtils")).isFalse();
        }
    }

    // ─── Task 6 scenarios C10 + C11: runner-based property wiring and SPI adoption ───

    /**
     * C10: the scheduler-facing properties must land on the created
     * {@link ThreadPoolTaskScheduler} bean itself (not just on the properties object).
     */
    @Nested
    class SchedulerPropertyWiring {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlexScheduleAutoConfiguration.class));

        /**
         * Reset the static SpringContextUtils.applicationContext after each test so we don't
         * leak a closed runner context to subsequent tests (pattern from TaskLimitsAutoConfigTest).
         */
        @AfterEach
        void clearSpringContextUtils() throws Exception {
            Field field = SpringContextUtils.class.getDeclaredField("applicationContext");
            field.setAccessible(true);
            field.set(null, null);
        }

        @Test
        void scalarProperties_landOnThreadPoolTaskScheduler() {
            runner.withPropertyValues(
                "flex.schedule.pool-size=3",
                "flex.schedule.thread-name-prefix=X-",
                "flex.schedule.await-termination-seconds=7",
                "flex.schedule.remove-on-cancel=false"
            ).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                ThreadPoolTaskScheduler scheduler =
                    ctx.getBean("flexScheduleThreadPoolTaskScheduler", ThreadPoolTaskScheduler.class);
                // ThreadPoolTaskScheduler.getPoolSize() reports the LIVE thread count (0 until a
                // task runs), so the configured size is read from the underlying executor.
                assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
                assertThat(scheduler.getThreadNamePrefix()).isEqualTo("X-");
                assertThat(scheduler.isRemoveOnCancelPolicy()).isFalse();
                // ThreadPoolTaskScheduler/ExecutorConfigurationSupport expose no public getter for
                // await-termination (setAwaitTerminationSeconds stores it into the private
                // awaitTerminationMillis field), so read the field to prove the wiring.
                assertThat(ReflectionTestUtils.getField(scheduler, "awaitTerminationMillis"))
                    .isEqualTo(7000L);
            });
        }

        @Test
        void schedulerDefaults_applyWhenNoPropertiesGiven() {
            runner.run(ctx -> {
                assertThat(ctx).hasNotFailed();
                ThreadPoolTaskScheduler scheduler =
                    ctx.getBean("flexScheduleThreadPoolTaskScheduler", ThreadPoolTaskScheduler.class);
                assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(16);
                assertThat(scheduler.getThreadNamePrefix()).isEqualTo("FlexScheduleThreadPool-");
                assertThat(scheduler.isRemoveOnCancelPolicy()).isTrue();
                assertThat(ReflectionTestUtils.getField(scheduler, "awaitTerminationMillis"))
                    .isEqualTo(30000L);
            });
        }
    }

    /**
     * C11: the default {@link ExecutionHistory} bean is {@link InMemoryExecutionHistory} and
     * gets wired into the registrar; user-defined {@link ExecutionHistory}/{@link TaskRepository}
     * beans replace the defaults and are adopted by the registrar.
     *
     * <p>Adoption is asserted through the registrar's public getters
     * ({@code getExecutionHistory()}/{@code getTaskRepository()}) plus one end-to-end execution
     * (the custom history actually receives records). Note: the registrar itself never calls
     * {@code TaskRepository.save(...)} (it only reads via {@code findAll()} during
     * {@code restoreTasks()}), so repository adoption is asserted as instance identity —
     * the least-invasive real assertion available.</p>
     */
    @Nested
    class SpiAdoption {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlexScheduleAutoConfiguration.class));

        @AfterEach
        void clearSpringContextUtils() throws Exception {
            Field field = SpringContextUtils.class.getDeclaredField("applicationContext");
            field.setAccessible(true);
            field.set(null, null);
        }

        @Test
        void defaultExecutionHistory_isInMemoryAndWiredIntoRegistrar() {
            runner.run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(ExecutionHistory.class);
                ExecutionHistory history = ctx.getBean(ExecutionHistory.class);
                assertThat(history).isInstanceOf(InMemoryExecutionHistory.class);
                assertThat(ctx.getBean(FlexScheduledTaskRegistrar.class).getExecutionHistory())
                    .isSameAs(history);
            });
        }

        @Test
        void userDefinedHistoryAndRepository_replaceDefaultsAndAreAdopted() {
            runner.withUserConfiguration(CustomSpiConfig.class).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                ExecutionHistory customHistory = ctx.getBean(ExecutionHistory.class);
                TaskRepository customRepository = ctx.getBean(TaskRepository.class);
                // @ConditionalOnMissingBean backs the InMemory default off entirely.
                assertThat(customHistory).isNotInstanceOf(InMemoryExecutionHistory.class);
                assertThat(ctx).hasSingleBean(ExecutionHistory.class);
                assertThat(ctx).hasSingleBean(TaskRepository.class);
                // The registrar adopted the user instances verbatim.
                FlexScheduledTaskRegistrar registrar = ctx.getBean(FlexScheduledTaskRegistrar.class);
                assertThat(registrar.getExecutionHistory()).isSameAs(customHistory);
                assertThat(registrar.getTaskRepository()).isSameAs(customRepository);
            });
        }

        @Test
        void runningTask_recordsIntoUserDefinedHistory() {
            runner.withUserConfiguration(CustomSpiConfig.class).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                RecordingHistory history = (RecordingHistory) ctx.getBean(ExecutionHistory.class);
                FlexScheduledTaskService service = ctx.getBean(FlexScheduledTaskService.class);
                service.addFixedDelayTask("c11-probe", Duration.ofMillis(50), Duration.ZERO, () -> { });
                assertThat(history.awaitRecord(3000))
                    .as("custom ExecutionHistory bean must receive execution records through the instrument() path")
                    .isTrue();
                assertThat(history.records)
                    .anySatisfy(record -> {
                        assertThat(record.taskName()).isEqualTo("c11-probe");
                        assertThat(record.success()).isTrue();
                    });
                service.cancel("c11-probe");
            });
        }
    }

    @Configuration
    static class CustomSpiConfig {
        @Bean
        public ExecutionHistory recordingExecutionHistory() {
            return new RecordingHistory();
        }

        @Bean
        public TaskRepository recordingTaskRepository() {
            return new RecordingRepository();
        }
    }

    /** Fresh instance per context (the @Bean method is not a static singleton). */
    static class RecordingHistory implements ExecutionHistory {
        final List<ExecutionRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void record(ExecutionRecord record) {
            records.add(record);
        }

        @Override
        public List<ExecutionRecord> getHistory(String taskName, int limit) {
            return records.stream()
                    .filter(r -> r.taskName().equals(taskName))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ExecutionRecord> getAllHistory(int limit) {
            return List.copyOf(records);
        }

        @Override
        public void clear(String taskName) {
            records.clear();
        }

        boolean awaitRecord(long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (records.isEmpty() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !records.isEmpty();
        }
    }

    static class RecordingRepository implements TaskRepository {
        final List<TaskDefinition> saved = new CopyOnWriteArrayList<>();

        @Override
        public void save(TaskDefinition definition) {
            saved.add(definition);
        }

        @Override
        public Optional<TaskDefinition> findByName(String taskName) {
            return saved.stream().filter(d -> d.taskName().equals(taskName)).findFirst();
        }

        @Override
        public List<TaskDefinition> findAll() {
            return List.copyOf(saved);
        }

        @Override
        public void delete(String taskName) {
            saved.removeIf(d -> d.taskName().equals(taskName));
        }

        @Override
        public void deleteAll() {
            saved.clear();
        }

        @Override
        public int count() {
            return saved.size();
        }
    }
}
