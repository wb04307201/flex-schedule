package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Health-indicator auto-configuration for flex-schedule.
 * Reports the scheduler health to {@code /actuator/health} via
 * {@link FlexScheduleHealthIndicator}.
 * <p>
 * In Spring Boot 4 {@link HealthIndicator} moved out of {@code spring-boot-actuator}
 * into the new {@code spring-boot-health} artifact under
 * {@code org.springframework.boot.health.contributor}. The
 * {@code @ConditionalOnClass(HealthIndicator.class)} therefore requires that
 * {@code spring-boot-health} (which {@code spring-boot-starter-actuator} pulls
 * in transitively) is on the classpath.
 * <p>
 * Split from {@link FlexScheduleAutoConfiguration} for the same Boot 4
 * nested-class-discovery change documented in
 * {@link FlexScheduleEndpointAutoConfiguration}. Declared {@code after}
 * {@link FlexScheduleAutoConfiguration} so that the {@link ConditionalOnBean}
 * condition below evaluates against already-registered registrar / scheduler
 * bean definitions, instead of relying on the two classes happening to sort
 * alphabetically in the right order.
 */
@AutoConfiguration(after = FlexScheduleAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean({FlexScheduledTaskRegistrar.class, ThreadPoolTaskScheduler.class})
public class FlexScheduleHealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(FlexScheduleHealthIndicator.class)
    public FlexScheduleHealthIndicator flexScheduleHealthIndicator(
            FlexScheduledTaskRegistrar registrar,
            @Qualifier("flexScheduleThreadPoolTaskScheduler") ThreadPoolTaskScheduler taskScheduler) {
        return new FlexScheduleHealthIndicator(registrar, taskScheduler);
    }
}
