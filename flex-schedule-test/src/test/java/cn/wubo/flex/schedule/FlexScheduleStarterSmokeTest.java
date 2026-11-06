package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.autoconfigure.FlexScheduleAutoConfiguration;
import cn.wubo.flex.schedule.autoconfigure.FlexScheduleEndpointAutoConfiguration;
import cn.wubo.flex.schedule.autoconfigure.FlexScheduleHealthAutoConfiguration;
import cn.wubo.flex.schedule.autoconfigure.FlexScheduleHealthIndicator;
import cn.wubo.flex.schedule.autoconfigure.FlexScheduleMetricsAutoConfiguration;
import cn.wubo.flex.schedule.autoconfigure.FlexScheduleMetricsRegistrar;
import cn.wubo.flex.schedule.autoconfigure.FlexScheduleProperties;
import cn.wubo.flex.schedule.autoconfigure.FlexScheduleRedisLockAutoConfiguration;
import cn.wubo.flex.schedule.autoconfigure.endpoint.EndpointAccessControl;
import cn.wubo.flex.schedule.autoconfigure.endpoint.FlexScheduleEndpoint;
import cn.wubo.flex.schedule.core.DistributedLock;
import cn.wubo.flex.schedule.core.ExecutionHistory;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.MetricsRecorder;
import cn.wubo.flex.schedule.core.SpringContextUtils;
import cn.wubo.flex.schedule.metrics.MicrometerMetricsRecorder;
import cn.wubo.flex.schedule.redis.RedisDistributedLock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 6 scenarios C14 (starter smoke) and C8 (kill switch).
 *
 * <p>C14: the starter's transitive dependency chain (flex-schedule core +
 * autoconfigure + imports file, with actuator/micrometer/redis on the classpath)
 * must resolve the five flex-schedule auto-configurations into one working
 * context — asserted here explicitly by loading all five fragments plus Boot's
 * {@code DataRedisAutoConfiguration} through a single runner.</p>
 *
 * <p>C8: {@code flex.schedule.enabled=false} must remove the core beans AND
 * every optional-fragment bean (endpoint, metrics, health, redis lock), even
 * when a {@link MeterRegistry} and a Redis template are available — the
 * optional fragments gate on core beans via {@code @ConditionalOnBean}, so with
 * the core switched off they must all back off.</p>
 */
class FlexScheduleStarterSmokeTest {

    private final ApplicationContextRunner fullStackRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            FlexScheduleAutoConfiguration.class,
            FlexScheduleEndpointAutoConfiguration.class,
            FlexScheduleMetricsAutoConfiguration.class,
            FlexScheduleHealthAutoConfiguration.class,
            FlexScheduleRedisLockAutoConfiguration.class,
            // Real-world Redis path: Boot's own auto-config creates the StringRedisTemplate
            // that FlexScheduleRedisLockAutoConfiguration locks onto (Task 1 ordering fix).
            DataRedisAutoConfiguration.class))
        .withUserConfiguration(MeterRegistryConfig.class);

    /**
     * Reset the static SpringContextUtils.applicationContext after each test so we don't
     * leak a closed context to subsequent tests (same cleanup as TaskLimitsAutoConfigTest).
     */
    @AfterEach
    void clearSpringContextUtils() throws Exception {
        Field field = SpringContextUtils.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void starterStack_allFiveAutoConfigurations_coreAndOptionalBeansAreCreated() {
        // C14 control: enabled by default, everything wires.
        fullStackRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            // Core four beans (C14 smoke).
            assertThat(ctx).hasSingleBean(ThreadPoolTaskScheduler.class);
            assertThat(ctx).hasSingleBean(FlexScheduledTaskRegistrar.class);
            assertThat(ctx).hasSingleBean(FlexScheduledTaskService.class);
            assertThat(ctx).hasSingleBean(SpringContextUtils.class);
            // Endpoint fragment.
            assertThat(ctx).hasSingleBean(FlexScheduleEndpoint.class);
            assertThat(ctx).hasSingleBean(EndpointAccessControl.class);
            // Metrics fragment (MeterRegistry bean present).
            assertThat(ctx).hasSingleBean(MetricsRecorder.class);
            assertThat(ctx.getBean(MetricsRecorder.class)).isInstanceOf(MicrometerMetricsRecorder.class);
            assertThat(ctx).hasSingleBean(FlexScheduleMetricsRegistrar.class);
            // Health fragment.
            assertThat(ctx).hasSingleBean(FlexScheduleHealthIndicator.class);
            // Redis lock fragment: created through Boot's DataRedisAutoConfiguration template.
            assertThat(ctx).hasSingleBean(DistributedLock.class);
            assertThat(ctx.getBean(DistributedLock.class)).isInstanceOf(RedisDistributedLock.class);
            // Properties bean bound by the core auto-configuration.
            assertThat(ctx).hasSingleBean(FlexScheduleProperties.class);
            // Default ExecutionHistory wired into the registrar.
            assertThat(ctx).hasSingleBean(ExecutionHistory.class);
            assertThat(ctx.getBean(FlexScheduledTaskRegistrar.class).getExecutionHistory())
                .isSameAs(ctx.getBean(ExecutionHistory.class));
        });
    }

    @Test
    void enabledFalse_coreAndAllOptionalFragmentBeansAreAbsent() {
        // C8 kill switch: even with a MeterRegistry on the context and Boot's Redis
        // auto-config producing a StringRedisTemplate, nothing flex-schedule must remain.
        fullStackRunner
            .withPropertyValues("flex.schedule.enabled=false")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                // Core.
                assertThat(ctx).doesNotHaveBean(ThreadPoolTaskScheduler.class);
                assertThat(ctx).doesNotHaveBean(FlexScheduledTaskRegistrar.class);
                assertThat(ctx).doesNotHaveBean(FlexScheduledTaskService.class);
                assertThat(ctx).doesNotHaveBean(SpringContextUtils.class);
                assertThat(ctx).doesNotHaveBean(ExecutionHistory.class);
                assertThat(ctx).doesNotHaveBean(FlexScheduleProperties.class);
                // Optional fragments.
                assertThat(ctx).doesNotHaveBean(FlexScheduleEndpoint.class);
                assertThat(ctx).doesNotHaveBean(EndpointAccessControl.class);
                assertThat(ctx).doesNotHaveBean(MetricsRecorder.class);
                assertThat(ctx).doesNotHaveBean(MicrometerMetricsRecorder.class);
                assertThat(ctx).doesNotHaveBean(FlexScheduleMetricsRegistrar.class);
                assertThat(ctx).doesNotHaveBean(FlexScheduleHealthIndicator.class);
                assertThat(ctx).doesNotHaveBean(DistributedLock.class);
                assertThat(ctx).doesNotHaveBean(RedisDistributedLock.class);
                // Boot's own Redis auto-config is independent and still runs.
                assertThat(ctx).hasSingleBean(MeterRegistry.class);
                assertThat(ctx).hasSingleBean(StringRedisTemplate.class);
            });
    }

    @Configuration
    static class MeterRegistryConfig {
        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
