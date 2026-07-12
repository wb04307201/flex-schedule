package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.autoconfigure.FlexScheduleProperties;
import cn.wubo.flex.schedule.autoconfigure.FlexScheduleProperties.Limits;
import cn.wubo.flex.schedule.core.TaskLimits;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FlexSchedulePropertiesLimitsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class);

    @Test
    void defaults_minAndMaxAreNullAndModeIsStrict() {
        runner.run(ctx -> {
            FlexScheduleProperties props = ctx.getBean(FlexScheduleProperties.class);
            Limits limits = props.getLimits();
            assertThat(limits.getMinInterval()).isNull();
            assertThat(limits.getMaxLifetime()).isNull();
            assertThat(limits.getMode()).isEqualTo(TaskLimits.Mode.STRICT);
        });
    }

    @Test
    void bindsYamlValues() {
        runner.withPropertyValues(
            "flex.schedule.limits.min-interval=PT10M",
            "flex.schedule.limits.max-lifetime=P7D",
            "flex.schedule.limits.mode=warn"
        ).run(ctx -> {
            Limits limits = ctx.getBean(FlexScheduleProperties.class).getLimits();
            assertThat(limits.getMinInterval()).isEqualTo(Duration.ofMinutes(10));
            assertThat(limits.getMaxLifetime()).isEqualTo(Duration.ofDays(7));
            assertThat(limits.getMode()).isEqualTo(TaskLimits.Mode.WARN);
        });
    }

    @Test
    void mode_offIsParsed() {
        runner.withPropertyValues("flex.schedule.limits.mode=off").run(ctx -> {
            assertThat(ctx.getBean(FlexScheduleProperties.class).getLimits().getMode())
                .isEqualTo(TaskLimits.Mode.OFF);
        });
    }

    @EnableConfigurationProperties(FlexScheduleProperties.class)
    static class TestConfig {}
}