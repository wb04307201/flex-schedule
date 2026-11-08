package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.autoconfigure.FlexScheduleProperties;
import cn.wubo.flex.schedule.core.DefaultFlexScheduledTaskService;
import cn.wubo.flex.schedule.core.ExecutionHistory;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.InMemoryExecutionHistory;
import cn.wubo.flex.schedule.core.SpringContextUtils;
import cn.wubo.flex.schedule.core.TaskLimits;
import cn.wubo.flex.schedule.core.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Core flex-schedule auto-configuration. Declares the thread pool, registrar,
 * service, default {@link ExecutionHistory}, {@link TaskLimits}, and Spring
 * context utils beans.
 *
 * <p>The optional pieces split out into sibling top-level configurations so
 * Spring Boot 4 can discover them through
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * — Boot 4 no longer pulls in nested static {@code @Configuration} classes that
 * live under an outer {@code @AutoConfiguration}:</p>
 * <ul>
 *   <li>{@link FlexScheduleEndpointAutoConfiguration} — {@code /actuator/flexschedule}</li>
 *   <li>{@link FlexScheduleMetricsAutoConfiguration} — Micrometer recorder + registrar</li>
 *   <li>{@link FlexScheduleHealthAutoConfiguration} — health indicator</li>
 *   <li>{@link FlexScheduleRedisLockAutoConfiguration} — Redis distributed lock</li>
 * </ul>
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(FlexScheduledTaskService.class)
@ConditionalOnProperty(prefix = "flex.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({FlexScheduleProperties.class})
public class FlexScheduleAutoConfiguration {

    @Bean(name = "flexScheduleThreadPoolTaskScheduler")
    public ThreadPoolTaskScheduler threadPoolTaskScheduler(FlexScheduleProperties properties) {
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(properties.getPoolSize());
        taskScheduler.setThreadNamePrefix(properties.getThreadNamePrefix());
        taskScheduler.setRemoveOnCancelPolicy(properties.getRemoveOnCancel());
        taskScheduler.setWaitForTasksToCompleteOnShutdown(true);
        taskScheduler.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds().intValue());
        taskScheduler.setErrorHandler(t -> log.error("Flex scheduled task threw an exception: {}", t.getMessage(), t));
        taskScheduler.initialize();
        log.info("Flex schedule thread pool initialized with poolSize={}", properties.getPoolSize());
        return taskScheduler;
    }

    @Bean(name = "flexScheduledTaskRegistrar")
    public FlexScheduledTaskRegistrar flexScheduledTaskRegistrar(
            @Qualifier("flexScheduleThreadPoolTaskScheduler") ThreadPoolTaskScheduler threadPoolTaskScheduler,
            FlexScheduleProperties properties,
            TaskLimits taskLimits,
            ObjectProvider<TaskRepository> taskRepositoryProvider,
            ObjectProvider<ExecutionHistory> executionHistoryProvider) {
        FlexScheduledTaskRegistrar registrar = new FlexScheduledTaskRegistrar(
                threadPoolTaskScheduler, properties.getAwaitTerminationSeconds(), taskLimits);
        TaskRepository repo = taskRepositoryProvider.getIfAvailable();
        if (repo != null) {
            registrar.setTaskRepository(repo);
            log.info("Flex schedule wired with custom TaskRepository: {}", repo.getClass().getSimpleName());
        } else {
            log.info("Flex schedule using default in-memory TaskRepository");
        }
        ExecutionHistory history = executionHistoryProvider.getIfAvailable();
        if (history != null) {
            registrar.setExecutionHistory(history);
            log.info("Flex schedule wired with ExecutionHistory: {}", history.getClass().getSimpleName());
        } else {
            log.info("Flex schedule using default NOOP ExecutionHistory (history is NOT recorded)");
        }
        return registrar;
    }

    /**
     * Default {@link ExecutionHistory} bean — keeps a per-task bounded ring of
     * recent execution records so {@code flexService.getExecutionHistory(name)}
     * returns real data. Without this bean, the registrar falls back to
     * {@link ExecutionHistory#NOOP} (silently drops records).
     *
     * <p>{@code @ConditionalOnMissingBean} so consumers can substitute their
     * own (Redis-backed, JDBC-backed, etc.) by registering a different
     * {@link ExecutionHistory} bean.</p>
     */
    @Bean
    @ConditionalOnMissingBean(ExecutionHistory.class)
    public ExecutionHistory flexScheduleExecutionHistory() {
        log.info("Flex schedule installing default InMemoryExecutionHistory (100 records/task)");
        return new InMemoryExecutionHistory();
    }

    @Bean
    @ConditionalOnMissingBean(TaskLimits.class)
    public TaskLimits taskLimits(FlexScheduleProperties properties) {
        FlexScheduleProperties.Limits l = properties.getLimits();
        return new TaskLimits(l.getMinInterval(), l.getMaxLifetime(), l.getMode());
    }

    @Bean(name = "flexScheduledTaskService")
    public FlexScheduledTaskService flexScheduledTaskService(
            @Qualifier("flexScheduledTaskRegistrar") FlexScheduledTaskRegistrar flexScheduledTaskRegistrar) {
        return new DefaultFlexScheduledTaskService(flexScheduledTaskRegistrar);
    }

    @Bean(name = "flexScheduleSpringContextUtils")
    public SpringContextUtils springContextUtils() {
        return new SpringContextUtils();
    }
}
