package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.autoconfigure.endpoint.DefaultEndpointAccessControl;
import cn.wubo.flex.schedule.autoconfigure.endpoint.FlexScheduleEndpoint;
import cn.wubo.flex.schedule.autoconfigure.endpoint.EndpointAccessControl;
import cn.wubo.flex.schedule.autoconfigure.FlexScheduleProperties;
import cn.wubo.flex.schedule.core.DefaultFlexScheduledTaskService;
import cn.wubo.flex.schedule.core.DistributedLock;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.MetricsRecorder;
import cn.wubo.flex.schedule.core.SpringContextUtils;
import cn.wubo.flex.schedule.metrics.MicrometerMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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
            FlexScheduleProperties properties) {
        return new FlexScheduledTaskRegistrar(
                threadPoolTaskScheduler, properties.getAwaitTerminationSeconds());
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

    /**
     * Actuator endpoint auto-configuration. Only active when actuator is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Endpoint.class)
    @ConditionalOnBean(FlexScheduledTaskService.class)
    static class EndpointConfiguration {

        @Bean
        @ConditionalOnMissingBean(EndpointAccessControl.class)
        public EndpointAccessControl flexScheduleEndpointAccessControl(FlexScheduleProperties properties) {
            return new DefaultEndpointAccessControl(
                    properties.getEndpoint().getWriteEnabled(),
                    properties.getEndpoint().getAllowedBeans());
        }

        @Bean
        @ConditionalOnMissingBean(FlexScheduleEndpoint.class)
        public FlexScheduleEndpoint flexScheduleEndpoint(
                FlexScheduledTaskService taskService,
                EndpointAccessControl accessControl) {
            return new FlexScheduleEndpoint(taskService, accessControl);
        }
    }

    /**
     * Micrometer metrics auto-configuration. Only active when Micrometer is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(MetricsRecorder.class)
        public MetricsRecorder flexScheduleMetricsRecorder(MeterRegistry meterRegistry) {
            return new MicrometerMetricsRecorder(meterRegistry);
        }

        @Bean
        @ConditionalOnBean({FlexScheduledTaskRegistrar.class, MetricsRecorder.class})
        public FlexScheduleMetricsRegistrar flexScheduleMetricsRegistrar(
                FlexScheduledTaskRegistrar registrar,
                MetricsRecorder metricsRecorder) {
            return new FlexScheduleMetricsRegistrar(registrar, metricsRecorder);
        }
    }

    /**
     * Health indicator auto-configuration. Only active when Spring Boot Actuator is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnBean({FlexScheduledTaskRegistrar.class, ThreadPoolTaskScheduler.class})
    static class HealthConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "flexScheduleHealthIndicator")
        public FlexScheduleHealthIndicator flexScheduleHealthIndicator(
                FlexScheduledTaskRegistrar registrar,
                @Qualifier("flexScheduleThreadPoolTaskScheduler") ThreadPoolTaskScheduler taskScheduler) {
            return new FlexScheduleHealthIndicator(registrar, taskScheduler);
        }
    }

    /**
     * Redis distributed lock auto-configuration.
     * Only active when Spring Data Redis is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
    @ConditionalOnBean(FlexScheduledTaskRegistrar.class)
    static class RedisLockConfiguration {

        @Bean
        @ConditionalOnMissingBean(DistributedLock.class)
        public DistributedLock redisDistributedLock(
                org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
                FlexScheduledTaskRegistrar registrar) {
            DistributedLock lock = new cn.wubo.flex.schedule.redis.RedisDistributedLock(redisTemplate);
            registrar.setDistributedLock(lock);
            return lock;
        }
    }
}
