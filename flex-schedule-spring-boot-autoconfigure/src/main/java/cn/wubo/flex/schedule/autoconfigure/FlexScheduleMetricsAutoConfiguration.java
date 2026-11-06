package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.MetricsRecorder;
import cn.wubo.flex.schedule.metrics.MicrometerMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
 * {@link FlexScheduleEndpointAutoConfiguration}. Declared {@code after}
 * {@link FlexScheduleAutoConfiguration} so that the
 * {@code @ConditionalOnBean(FlexScheduledTaskRegistrar.class)} guard on the
 * wiring bean below evaluates against an already-registered registrar
 * definition, instead of relying on the two classes happening to sort
 * alphabetically in the right order. (The {@link MeterRegistry} lookup stays
 * late-bound via {@link ObjectProvider} for the separate reason documented
 * below — auto-configuration ordering cannot help there because Boot's own
 * metrics auto-configurations sort after this package.)
 * <p>
 * <strong>Late binding by design (fix round 1):</strong> this fragment used to carry
 * a class-level {@code @ConditionalOnBean(MeterRegistry.class)}. Auto-configuration
 * conditions are evaluated while bean <em>definitions</em> are being registered and
 * only against definitions registered <em>so far</em>; since {@code cn.wubo...} sorts
 * alphabetically before {@code org.springframework...}, Boot's own metrics auto-
 * configurations ({@code MetricsAutoConfiguration}, the Simple/Prometheus/OTLP
 * exporters, the composite) had not registered their {@link MeterRegistry} yet when
 * the condition ran — so in a default actuator application the fragment silently
 * never activated. Replacing the eager injection with an
 * {@link ObjectProvider}{@code <MeterRegistry>} resolved at bean-creation time makes
 * the lookup order-insensitive: by then every auto-configuration has contributed its
 * definitions and the provider finds a user-supplied registry, an exporter registry,
 * or the {@code @Primary} composite exactly like direct injection would. An
 * {@code @AutoConfiguration(afterName = ...)} chain was rejected because it would
 * have to enumerate every exporter class (and their FQNs move across Boot versions).
 * <p>
 * The {@code flex.schedule.enabled} kill switch is honored through
 * {@link ConditionalOnProperty} (scenario C8): when the library is disabled no bean
 * of this fragment is installed at all. When the library is enabled but no
 * {@link MeterRegistry} ever becomes available, the default recorder degrades to
 * {@link MetricsRecorder#NOOP} — metrics stay off, exactly as before.
 */
@Slf4j
@AutoConfiguration(after = FlexScheduleAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "flex.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FlexScheduleMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MetricsRecorder.class)
    public MetricsRecorder flexScheduleMetricsRecorder(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        if (meterRegistry == null) {
            log.info("Flex schedule metrics stay inactive — no MeterRegistry bean is available");
            return MetricsRecorder.NOOP;
        }
        log.info("Flex schedule installing MicrometerMetricsRecorder against {}", meterRegistry.getClass().getSimpleName());
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
