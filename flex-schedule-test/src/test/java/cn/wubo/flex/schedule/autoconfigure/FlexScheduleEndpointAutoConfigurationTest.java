package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.autoconfigure.endpoint.DefaultEndpointAccessControl;
import cn.wubo.flex.schedule.autoconfigure.endpoint.EndpointAccessControl;
import cn.wubo.flex.schedule.autoconfigure.endpoint.FlexScheduleEndpoint;
import cn.wubo.flex.schedule.core.SpringContextUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conditional-wiring tests for {@link FlexScheduleEndpointAutoConfiguration}
 * (Task 6 scenarios C1-C3): bean presence/absence against the service bean,
 * user-bean overrides via {@code @ConditionalOnMissingBean}, and the
 * {@code flex.schedule.endpoint.*} property chain reaching the default
 * {@link DefaultEndpointAccessControl} (asserted through its
 * canAddTask/canCancelTask behavior — the class exposes no getters).
 */
class FlexScheduleEndpointAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            FlexScheduleAutoConfiguration.class,
            FlexScheduleEndpointAutoConfiguration.class));

    /**
     * Reset the static SpringContextUtils.applicationContext after each test so we don't
     * leak a closed context to subsequent tests (same cleanup as TaskLimitsAutoConfigTest —
     * the core auto-configuration registers SpringContextUtils).
     */
    @AfterEach
    void clearSpringContextUtils() throws Exception {
        Field field = SpringContextUtils.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        field.set(null, null);
    }

    // ─── C1 / C2: conditional presence ─────────────────────────────────

    @Test
    void withCoreAutoConfiguration_endpointAndDefaultAccessControlAreCreated() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(FlexScheduleEndpoint.class);
            assertThat(ctx).hasSingleBean(EndpointAccessControl.class);
            assertThat(ctx.getBean(EndpointAccessControl.class))
                .isInstanceOf(DefaultEndpointAccessControl.class);
        });
    }

    @Test
    void withoutFlexScheduledTaskServiceBean_endpointAutoConfigurationBacksOff() {
        // C2: the class-level @ConditionalOnBean(FlexScheduledTaskService.class) keeps the
        // endpoint fragment inert when the core auto-configuration is absent (or disabled).
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlexScheduleEndpointAutoConfiguration.class))
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).doesNotHaveBean(FlexScheduleEndpoint.class);
                assertThat(ctx).doesNotHaveBean(EndpointAccessControl.class);
            });
    }

    // ─── C3: @ConditionalOnMissingBean overrides ───────────────────────

    @Test
    void userDefinedEndpointAccessControl_replacesDefaultButKeepsEndpoint() {
        runner.withUserConfiguration(UserAccessControlConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(EndpointAccessControl.class);
            assertThat(ctx.getBean(EndpointAccessControl.class)).isSameAs(UserAccessControlConfig.CONTROL);
            assertThat(ctx).doesNotHaveBean(DefaultEndpointAccessControl.class);
            // The endpoint itself is still auto-configured — it just consumes the user control.
            assertThat(ctx).hasSingleBean(FlexScheduleEndpoint.class);
        });
    }

    @Test
    void userDefinedFlexScheduleEndpoint_replacesDefault() {
        runner.withUserConfiguration(UserEndpointConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(FlexScheduleEndpoint.class);
            assertThat(ctx.getBean(FlexScheduleEndpoint.class)).isSameAs(UserEndpointConfig.ENDPOINT);
        });
    }

    @Test
    void endpointAutoConfiguration_declaresOrderingAfterCoreAutoConfiguration() {
        // @ConditionalOnBean(FlexScheduledTaskService.class) only sees the service
        // bean definition if the core auto-configuration is processed first; guard
        // the explicit after= declaration instead of relying on alphabetical luck
        // (same rationale as FlexScheduleRedisLockAutoConfigurationTest).
        AutoConfiguration annotation = FlexScheduleEndpointAutoConfiguration.class
            .getAnnotation(AutoConfiguration.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.after()).contains(FlexScheduleAutoConfiguration.class);
    }

    // ─── C1/C3: property chain lands in DefaultEndpointAccessControl ───

    @Test
    void defaultProperties_writeDisabledDeniesEveryOperation() {
        runner.run(ctx -> {
            EndpointAccessControl control = ctx.getBean(EndpointAccessControl.class);
            assertThat(control.canAddTask("anyBean", "anyMethod")).isFalse();
            assertThat(control.canCancelTask("anyTask")).isFalse();
        });
    }

    @Test
    void writeEnabledWithAllowlist_bindsIntoDefaultAccessControl() {
        runner.withPropertyValues(
            "flex.schedule.endpoint.write-enabled=true",
            "flex.schedule.endpoint.allowed-beans=alpha,beta"
        ).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            EndpointAccessControl control = ctx.getBean(EndpointAccessControl.class);
            assertThat(control).isInstanceOf(DefaultEndpointAccessControl.class);
            assertThat(control.canAddTask("alpha", "run")).isTrue();
            assertThat(control.canAddTask("beta", "run")).isTrue();
            assertThat(control.canAddTask("gamma", "run"))
                .as("beans outside the allowlist must be denied")
                .isFalse();
            // canCancelTask ignores the allowlist — write-enabled alone unlocks it.
            assertThat(control.canCancelTask("whatever")).isTrue();
        });
    }

    @Test
    void writeEnabledWithEmptyAllowlist_admitsEveryBean() {
        runner.withPropertyValues("flex.schedule.endpoint.write-enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            EndpointAccessControl control = ctx.getBean(EndpointAccessControl.class);
            assertThat(control.canAddTask("anything", "run")).isTrue();
            assertThat(control.canCancelTask("whatever")).isTrue();
        });
    }

    @Configuration
    static class UserAccessControlConfig {
        static final EndpointAccessControl CONTROL = new EndpointAccessControl() {
            @Override
            public boolean canAddTask(String beanName, String methodName) {
                return true;
            }

            @Override
            public boolean canCancelTask(String taskName) {
                return true;
            }
        };

        @Bean
        public EndpointAccessControl customEndpointAccessControl() {
            return CONTROL;
        }
    }

    @Configuration
    static class UserEndpointConfig {
        // Constructor only stores the references, so null collaborators are safe for an
        // identity assertion — this test never invokes an operation on the endpoint.
        static final FlexScheduleEndpoint ENDPOINT = new FlexScheduleEndpoint(null, null);

        @Bean
        public FlexScheduleEndpoint customFlexScheduleEndpoint() {
            return ENDPOINT;
        }
    }
}
