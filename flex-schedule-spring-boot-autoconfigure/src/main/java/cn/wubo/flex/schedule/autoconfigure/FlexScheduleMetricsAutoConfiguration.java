package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.MetricsRecorder;
import cn.wubo.flex.schedule.metrics.MicrometerMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Micrometer metrics auto-configuration for flex-schedule.
 * Activates {@link MicrometerMetricsRecorder} and the wiring bean
 * {@link FlexScheduleMetricsRegistrar} when Micrometer is on the classpath.
 * <p>
 * Split from {@link FlexScheduleAutoConfiguration} for the same Boot 4
 * nested-class-discovery change documented in
 * {@link FlexScheduleEndpointAutoConfiguration}.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
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
