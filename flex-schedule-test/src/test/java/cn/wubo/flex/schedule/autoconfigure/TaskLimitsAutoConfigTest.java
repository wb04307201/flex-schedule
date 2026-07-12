package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.core.SpringContextUtils;
import cn.wubo.flex.schedule.core.TaskLimits;
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

import static org.assertj.core.api.Assertions.assertThat;

class TaskLimitsAutoConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FlexScheduleAutoConfiguration.class))
        .withUserConfiguration(MeterRegistryConfig.class);

    /**
     * Reset the static SpringContextUtils.applicationContext after each test so we don't
     * leak a closed context to subsequent tests that rely on the static field (e.g.,
     * BeanMethodRunnableTest, which uses @SpringJUnitConfig and expects to register its own
     * context — but SpringContextUtils.setApplicationContext only assigns when null).
     */
    @AfterEach
    void clearSpringContextUtils() throws Exception {
        Field field = SpringContextUtils.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void defaultTaskLimits_isCreated_whenNoneProvided() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(TaskLimits.class);
            TaskLimits limits = ctx.getBean(TaskLimits.class);
            // Defaults: both null, mode STRICT
            assertThat(limits.minInterval()).isNull();
            assertThat(limits.maxLifetime()).isNull();
            assertThat(limits.mode()).isEqualTo(TaskLimits.Mode.STRICT);
        });
    }

    @Test
    void userDefinedTaskLimits_winsOverAutoConfig() {
        runner.withUserConfiguration(UserTaskLimitsConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(TaskLimits.class);
            TaskLimits limits = ctx.getBean(TaskLimits.class);
            assertThat(limits.minInterval()).isEqualTo(Duration.ofMinutes(42));
            assertThat(limits.maxLifetime()).isEqualTo(Duration.ofHours(7));
            assertThat(limits.mode()).isEqualTo(TaskLimits.Mode.WARN);
        });
    }

    @Configuration
    static class UserTaskLimitsConfig {
        @Bean
        public TaskLimits customTaskLimits() {
            return new TaskLimits(Duration.ofMinutes(42), Duration.ofHours(7), TaskLimits.Mode.WARN);
        }
    }

    @Configuration
    static class MeterRegistryConfig {
        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}