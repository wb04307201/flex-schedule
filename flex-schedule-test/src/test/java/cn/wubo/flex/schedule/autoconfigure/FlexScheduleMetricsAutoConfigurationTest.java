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
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conditional-wiring tests for {@link FlexScheduleMetricsAutoConfiguration}
 * (Task 6 scenario C4, plus the fix-round-1 late-binding regression): the Micrometer
 * recorder + wiring bean activate for any MeterRegistry source — a user bean or
 * Boot's own auto-configured registry — and stay inert (NOOP) when no registry
 * exists. A user-supplied {@link MetricsRecorder} replaces the default.
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
        // Late binding (fix round 1): with no MeterRegistry definition anywhere, the
        // recorder must NOT activate — the default MetricsRecorder bean degrades to the
        // NOOP singleton, so no Micrometer meters are ever registered and the registrar
        // keeps its default NOOP behavior end-to-end.
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(MicrometerMetricsRecorder.class);
            assertThat(ctx.getBean(MetricsRecorder.class)).isSameAs(MetricsRecorder.NOOP);
            // Registrar still fully functional.
            assertThat(ctx.getBean(FlexScheduledTaskRegistrar.class)).isNotNull();
        });
    }

    @Test
    void withBootAutoConfiguredRegistry_lateBindingActivatesMetrics() {
        // Fix-round-1 regression. In a standard actuator app the MeterRegistry comes from
        // Boot's SimpleMetricsExportAutoConfiguration, whose bean definitions are registered
        // AFTER the cn.wubo fragments are condition-evaluated (auto-config sort is
        // alphabetical: "cn.wubo..." < "org.springframework..."). A class-level
        // @ConditionalOnBean(MeterRegistry.class) therefore never matched and metrics
        // silently stayed off. The fragment must bind the registry late — via ObjectProvider
        // resolution at bean-instantiation time, when every definition (user or Boot) is
        // already registered — with NO user-supplied registry in the context.
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                FlexScheduleAutoConfiguration.class,
                FlexScheduleMetricsAutoConfiguration.class,
                // Boot's own metrics auto-configs — these are what a default actuator app
                // uses to produce its MeterRegistry; no user MeterRegistryConfig here.
                MetricsAutoConfiguration.class,
                SimpleMetricsExportAutoConfiguration.class))
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(MeterRegistry.class);
                assertThat(ctx.getBean(MeterRegistry.class)).isInstanceOf(SimpleMeterRegistry.class);
                assertThat(ctx).hasSingleBean(MetricsRecorder.class);
                MetricsRecorder recorder = ctx.getBean(MetricsRecorder.class);
                assertThat(recorder).isInstanceOf(MicrometerMetricsRecorder.class);
                // Bound to exactly Boot's auto-configured registry.
                assertThat(ReflectionTestUtils.getField(recorder, "meterRegistry"))
                    .isSameAs(ctx.getBean(MeterRegistry.class));
                assertThat(ctx).hasSingleBean(FlexScheduleMetricsRegistrar.class);
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
