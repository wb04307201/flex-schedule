package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.core.SpringContextUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conditional-wiring tests for {@link FlexScheduleHealthAutoConfiguration}
 * (Task 6 scenario C5): the indicator appears only when both the registrar and
 * the thread-pool scheduler beans exist, and a user-supplied
 * {@link FlexScheduleHealthIndicator} replaces the default via
 * {@code @ConditionalOnMissingBean(FlexScheduleHealthIndicator.class)}.
 */
class FlexScheduleHealthAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            FlexScheduleAutoConfiguration.class,
            FlexScheduleHealthAutoConfiguration.class));

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
    void withRegistrarAndScheduler_indicatorIsCreated() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(FlexScheduleHealthIndicator.class);
            assertThat(ctx.getBean("flexScheduleHealthIndicator")).isInstanceOf(HealthIndicator.class);
        });
    }

    @Test
    void withoutRegistrarAndScheduler_indicatorIsAbsent() {
        // Class-level @ConditionalOnBean({FlexScheduledTaskRegistrar, ThreadPoolTaskScheduler})
        // — loading the fragment alone (core auto-configuration absent) must back off.
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlexScheduleHealthAutoConfiguration.class))
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).doesNotHaveBean(FlexScheduleHealthIndicator.class);
            });
    }

    @Test
    void userDefinedFlexScheduleHealthIndicator_replacesDefault() {
        runner.withUserConfiguration(UserHealthConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(FlexScheduleHealthIndicator.class);
            assertThat(ctx.getBean(FlexScheduleHealthIndicator.class)).isSameAs(UserHealthConfig.INDICATOR);
        });
    }

    @Test
    void userDefinedForeignHealthIndicator_doesNotSuppressDefault() {
        // Pins the real contract: @ConditionalOnMissingBean targets FlexScheduleHealthIndicator
        // specifically, so an unrelated HealthIndicator bean coexists with (does not replace)
        // the flex one. The health endpoint would then expose both indicators.
        runner.withUserConfiguration(ForeignHealthConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(FlexScheduleHealthIndicator.class);
            assertThat(ctx.getBeanNamesForType(HealthIndicator.class))
                .as("both the default flex indicator and the foreign user indicator must coexist")
                .hasSize(2)
                .contains("flexScheduleHealthIndicator", "foreignHealthIndicator");
        });
    }

    @Configuration
    static class UserHealthConfig {
        static final FlexScheduleHealthIndicator INDICATOR = new FlexScheduleHealthIndicator(null, null);

        @Bean
        public FlexScheduleHealthIndicator customFlexScheduleHealthIndicator() {
            return INDICATOR;
        }
    }

    @Configuration
    static class ForeignHealthConfig {
        @Bean
        public HealthIndicator foreignHealthIndicator() {
            return new HealthIndicator() {
                @Override
                public Health health() {
                    return Health.up().build();
                }
            };
        }
    }
}
