package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.MetricsRecorder;
import cn.wubo.flex.schedule.metrics.MicrometerMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Micrometer metrics auto-configuration for flex-schedule.
 * Activates {@link MicrometerMetricsRecorder} and the wiring bean
 * {@link FlexScheduleMetricsRegistrar} when Micrometer is on the classpath.
 * <p>
 * Split from {@link FlexScheduleAutoConfiguration} for the same Boot 4
 * nested-class-discovery change documented in
 * {@link FlexScheduleEndpointAutoConfiguration}.
 * <p>
 * <strong>Why the explicit kill-switch condition:</strong> unlike the endpoint,
 * health and redis-lock fragments — which back off transitively because their
 * class-level {@code @ConditionalOnBean} targets core flex-schedule beans that
 * disappear when {@code flex.schedule.enabled=false} — the
 * {@code flexScheduleMetricsRecorder} bean only depends on a user-supplied
 * {@link MeterRegistry}. Without the property guard below, disabling the library
 * still left a stray {@code MetricsRecorder} bean behind (bug exposed by the
 * 1.3.0 auto-configuration test sweep, scenario C8).
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = "flex.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FlexScheduleMetricsAutoConfiguration {

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
