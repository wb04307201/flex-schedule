package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.core.DistributedLock;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.SpringContextUtils;
import cn.wubo.flex.schedule.redis.RedisDistributedLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-configuration tests for {@link FlexScheduleRedisLockAutoConfiguration},
 * covering the F1 (missing after= ordering declaration) and F2 (missing
 * StringRedisTemplate bean guard) fixes.
 */
class FlexScheduleRedisLockAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            FlexScheduleAutoConfiguration.class,
            FlexScheduleRedisLockAutoConfiguration.class));

    /**
     * Reset the static SpringContextUtils.applicationContext after each test so we don't
     * leak a closed context to subsequent tests (same cleanup as TaskLimitsAutoConfigTest —
     * FlexScheduleAutoConfiguration registers SpringContextUtils).
     */
    @AfterEach
    void clearSpringContextUtils() throws Exception {
        Field field = SpringContextUtils.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void withRedisTemplateAndRegistrar_createsLockAndWiresRegistrar() {
        runner.withUserConfiguration(RedisTemplateConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(DistributedLock.class);
            DistributedLock lock = ctx.getBean(DistributedLock.class);
            assertThat(lock).isInstanceOf(RedisDistributedLock.class);
            // The registrar must end up wired with the very lock bean the auto-config created.
            FlexScheduledTaskRegistrar registrar = ctx.getBean(FlexScheduledTaskRegistrar.class);
            assertThat(registrar.getDistributedLock()).isSameAs(lock);
        });
    }

    @Test
    void withoutRedisTemplateBean_doesNotCreateLock() {
        // F2 regression: the spring-data-redis classes ARE on the classpath (the runner's
        // @ConditionalOnClass passes) but no StringRedisTemplate bean exists. Before the
        // fix the redisDistributedLock bean method failed on its unsatisfied
        // StringRedisTemplate parameter and broke the whole context.
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(DistributedLock.class);
            // Registrar must keep its default NOOP lock.
            FlexScheduledTaskRegistrar registrar = ctx.getBean(FlexScheduledTaskRegistrar.class);
            assertThat(registrar.getDistributedLock()).isSameAs(DistributedLock.NOOP);
        });
    }

    @Test
    void userDefinedDistributedLock_takesPrecedence() {
        runner.withUserConfiguration(RedisTemplateConfig.class, UserLockConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(DistributedLock.class);
            assertThat(ctx.getBean(DistributedLock.class)).isSameAs(UserLockConfig.LOCK);
        });
    }

    @Test
    void withoutRegistrar_doesNotCreateLock() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlexScheduleRedisLockAutoConfiguration.class))
            .withUserConfiguration(RedisTemplateConfig.class)
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).doesNotHaveBean(DistributedLock.class);
            });
    }

    @Test
    void withBootRedisAutoConfiguration_lockIsCreatedWithoutManualTemplate() {
        // Real-world path: the StringRedisTemplate comes from Boot's own
        // DataRedisAutoConfiguration, not from a user bean. The @ConditionalOnBean guard
        // on redisDistributedLock is order-sensitive, so the auto-configuration must
        // declare ordering after Boot's redis auto-configuration (afterName), otherwise
        // the condition evaluates against an empty registry and the lock is silently
        // never created.
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                FlexScheduleAutoConfiguration.class,
                DataRedisAutoConfiguration.class,
                FlexScheduleRedisLockAutoConfiguration.class))
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean(StringRedisTemplate.class)).isNotNull();
                assertThat(ctx.getBean(DistributedLock.class)).isInstanceOf(RedisDistributedLock.class);
                FlexScheduledTaskRegistrar registrar = ctx.getBean(FlexScheduledTaskRegistrar.class);
                assertThat(registrar.getDistributedLock()).isInstanceOf(RedisDistributedLock.class);
            });
    }

    @Test
    void redisLockAutoConfiguration_declaresOrderingAfterCoreAutoConfiguration() {
        // F1 regression: @ConditionalOnBean(FlexScheduledTaskRegistrar.class) only sees the
        // registrar's bean definition if the core auto-configuration is processed first.
        // Without an explicit after= declaration that relies on alphabetical class-name
        // luck, so guard the declaration itself.
        AutoConfiguration annotation = FlexScheduleRedisLockAutoConfiguration.class
            .getAnnotation(AutoConfiguration.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.after()).contains(FlexScheduleAutoConfiguration.class);
    }

    @Configuration
    static class RedisTemplateConfig {
        @Bean
        public StringRedisTemplate stringRedisTemplate() {
            // RedisDistributedLock only null-checks the template at construction time,
            // so a mock is enough to exercise the wiring without a live Redis.
            return Mockito.mock(StringRedisTemplate.class);
        }
    }

    @Configuration
    static class UserLockConfig {

        static final DistributedLock LOCK = new DistributedLock() {
            @Override
            public boolean tryLock(String taskName, Duration lockDuration) {
                return true;
            }

            @Override
            public void unlock(String taskName) {
                // no-op
            }
        };

        @Bean
        public DistributedLock customDistributedLock() {
            return LOCK;
        }
    }
}
