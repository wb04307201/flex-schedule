package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.autoconfigure.endpoint.DefaultEndpointAccessControl;
import cn.wubo.flex.schedule.autoconfigure.endpoint.EndpointAccessControl;
import cn.wubo.flex.schedule.autoconfigure.endpoint.FlexScheduleEndpoint;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Actuator endpoint auto-configuration for flex-schedule.
 * Exposed at {@code /actuator/flexschedule}.
 * <p>
 * Split from {@link FlexScheduleAutoConfiguration} because Spring Boot 4 no
 * longer auto-discovers nested static {@code @Configuration} classes inside
 * an {@code @AutoConfiguration} class — each fragment must be its own
 * top-level {@code @AutoConfiguration} registered in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * <p>
 * Declared {@code after} {@link FlexScheduleAutoConfiguration} so that the
 * {@link ConditionalOnBean} condition below evaluates against an
 * already-registered {@link FlexScheduledTaskService} definition. Without it
 * the fragment would activate only because {@code FlexScheduleAutoConfiguration}
 * happens to sort alphabetically first — pure registration-order luck.
 */
@AutoConfiguration(after = FlexScheduleAutoConfiguration.class)
@ConditionalOnClass(Endpoint.class)
@ConditionalOnBean(FlexScheduledTaskService.class)
public class FlexScheduleEndpointAutoConfiguration {

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
