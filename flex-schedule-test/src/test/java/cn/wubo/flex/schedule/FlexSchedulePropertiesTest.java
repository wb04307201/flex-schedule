package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.autoconfigure.FlexScheduleProperties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FlexSchedulePropertiesTest {

    @Test
    void defaultValues_shouldBeCorrect() {
        FlexScheduleProperties props = new FlexScheduleProperties();

        assertTrue(props.getEnabled());
        assertEquals(16, props.getPoolSize());
        assertEquals("FlexScheduleThreadPool-", props.getThreadNamePrefix());
        assertTrue(props.getRemoveOnCancel());
        assertEquals(30L, props.getAwaitTerminationSeconds());
    }

    @Test
    void setters_shouldWork() {
        FlexScheduleProperties props = new FlexScheduleProperties();

        props.setEnabled(false);
        props.setPoolSize(32);
        props.setThreadNamePrefix("Custom-");
        props.setRemoveOnCancel(false);
        props.setAwaitTerminationSeconds(60L);

        assertFalse(props.getEnabled());
        assertEquals(32, props.getPoolSize());
        assertEquals("Custom-", props.getThreadNamePrefix());
        assertFalse(props.getRemoveOnCancel());
        assertEquals(60L, props.getAwaitTerminationSeconds());
    }

    @Test
    void endpoint_defaults_writeDisabledAndEmptyAllowlist() {
        FlexScheduleProperties props = new FlexScheduleProperties();

        assertNotNull(props.getEndpoint());
        assertFalse(props.getEndpoint().getWriteEnabled(),
            "Default endpoint write must be disabled for security");
        assertNotNull(props.getEndpoint().getAllowedBeans());
        assertTrue(props.getEndpoint().getAllowedBeans().isEmpty(),
            "Default allowlist must be empty (= all beans allowed when write-enabled)");
    }

    /**
     * Task 6 scenario C12: relaxed-binding of every non-limits property through the
     * actual Spring Boot binder (limits.* binding is covered by FlexSchedulePropertiesLimitsTest).
     * Uses a plain {@code @EnableConfigurationProperties} context so {@code enabled=false}
     * can be bound without backing the auto-configuration off.
     */
    @Nested
    class Binding {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropsConfig.class);

        @Test
        void scalarProperties_bindFromPropertyValues() {
            runner.withPropertyValues(
                "flex.schedule.enabled=false",
                "flex.schedule.pool-size=8",
                "flex.schedule.thread-name-prefix=Bench-",
                "flex.schedule.remove-on-cancel=false",
                "flex.schedule.await-termination-seconds=45"
            ).run(ctx -> {
                FlexScheduleProperties props = ctx.getBean(FlexScheduleProperties.class);
                assertFalse(props.getEnabled());
                assertEquals(8, props.getPoolSize());
                assertEquals("Bench-", props.getThreadNamePrefix());
                assertFalse(props.getRemoveOnCancel());
                assertEquals(45L, props.getAwaitTerminationSeconds());
            });
        }

        @Test
        void endpointProperties_bindFromPropertyValues() {
            runner.withPropertyValues(
                "flex.schedule.endpoint.write-enabled=true",
                "flex.schedule.endpoint.allowed-beans=alpha,beta"
            ).run(ctx -> {
                FlexScheduleProperties.Endpoint endpoint =
                    ctx.getBean(FlexScheduleProperties.class).getEndpoint();
                assertTrue(endpoint.getWriteEnabled());
                assertEquals(Set.of("alpha", "beta"), endpoint.getAllowedBeans());
            });
        }

        @Test
        void noProperties_yieldsDefaultBindValues() {
            runner.run(ctx -> {
                FlexScheduleProperties props = ctx.getBean(FlexScheduleProperties.class);
                assertTrue(props.getEnabled());
                assertEquals(16, props.getPoolSize());
                assertEquals("FlexScheduleThreadPool-", props.getThreadNamePrefix());
                assertTrue(props.getRemoveOnCancel());
                assertEquals(30L, props.getAwaitTerminationSeconds());
                assertFalse(props.getEndpoint().getWriteEnabled());
                assertTrue(props.getEndpoint().getAllowedBeans().isEmpty());
            });
        }
    }

    @EnableConfigurationProperties(FlexScheduleProperties.class)
    static class PropsConfig {
    }
}
