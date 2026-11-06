package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.MetricsRecorder;
import cn.wubo.flex.schedule.core.SpringContextUtils;
import cn.wubo.flex.schedule.metrics.MicrometerMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conditional-wiring tests for {@link FlexScheduleMetricsAutoConfiguration}
 * (Task 6 scenario C4): the Micrometer recorder + wiring bean appear only when
 * a {@link MeterRegistry} bean is present, and a user-supplied
 * {@link MetricsRecorder} replaces the default.
 */
class FlexScheduleMetricsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            FlexScheduleAutoConfiguration.class,
            FlexScheduleMetricsAutoConfiguration.class));

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
    void withMeterRegistryBean_recorderAndRegistrarAreCreated() {
        runner.withUserConfiguration(MeterRegistryConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(MetricsRecorder.class);
            assertThat(ctx.getBean(MetricsRecorder.class)).isInstanceOf(MicrometerMetricsRecorder.class);
            assertThat(ctx).hasSingleBean(FlexScheduleMetricsRegistrar.class);
            // The wiring bean must actually install the recorder into the registrar.
            // (No getMetricsRecorder accessor exists on the registrar — assert behavior.)
            // setMetricsRecorder installs an active-task-count supplier on the recorder;
            // driving a real execution through the service proves the end-to-end wiring.
            // A dedicated end-to-end pin already exists (FlexScheduleMetricsRegistrarTest),
            // so identity of the created beans + presence of the registrar is enough here.
            assertThat(ctx.getBean(FlexScheduledTaskRegistrar.class)).isNotNull();
        });
    }

    @Test
    void withoutMeterRegistryBean_nothingIsCreated() {
        // @ConditionalOnBean(MeterRegistry.class) — classpath has the Micrometer classes
        // (the @ConditionalOnClass passes) but no registry bean exists.
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(MicrometerMetricsRecorder.class);
            assertThat(ctx).doesNotHaveBean(FlexScheduleMetricsRegistrar.class);
            // Registrar keeps its NOOP recorder: still functional, nothing wired.
            assertThat(ctx.getBean(FlexScheduledTaskRegistrar.class)).isNotNull();
        });
    }

    @Test
    void userDefinedMetricsRecorder_replacesMicrometerDefault() {
        runner.withUserConfiguration(MeterRegistryConfig.class, UserRecorderConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(MetricsRecorder.class);
            assertThat(ctx.getBean(MetricsRecorder.class)).isSameAs(UserRecorderConfig.RECORDER);
            // The wiring fragment still runs against the user recorder.
            assertThat(ctx).hasSingleBean(FlexScheduleMetricsRegistrar.class);
        });
    }

    @Configuration
    static class MeterRegistryConfig {
        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration
    static class UserRecorderConfig {
        static final MetricsRecorder RECORDER = new MetricsRecorder() {
            @Override
            public void recordExecution(String taskName, String taskType, Duration duration, boolean success) {
            }

            @Override
            public void setActiveTaskCountSupplier(Supplier<Integer> supplier) {
            }
        };

        @Bean
        public MetricsRecorder customMetricsRecorder() {
            return RECORDER;
        }
    }
}
