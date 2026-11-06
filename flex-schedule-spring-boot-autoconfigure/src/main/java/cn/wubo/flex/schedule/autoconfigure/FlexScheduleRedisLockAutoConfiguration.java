package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.core.DistributedLock;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.redis.RedisDistributedLock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed distributed-lock auto-configuration for flex-schedule.
 * Activates a singleton {@link RedisDistributedLock} when a
 * {@link StringRedisTemplate} bean is available and wires it onto the
 * {@link FlexScheduledTaskRegistrar}.
 * <p>
 * Split from {@link FlexScheduleAutoConfiguration} for the same Boot 4
 * nested-class-discovery change documented in
 * {@link FlexScheduleEndpointAutoConfiguration}. Declared {@code after}
 * {@link FlexScheduleAutoConfiguration} so that the {@link ConditionalOnBean}
 * conditions below evaluate against an already-registered registrar, and
 * {@code afterName} Boot's own {@code DataRedisAutoConfiguration} so that a
 * {@link StringRedisTemplate} bean definition (typically created there) is
 * already registered when the guard below is evaluated — without it the
 * alphabetically-first {@code cn.wubo} package would process this class before
 * {@code org.springframework} and the lock would silently never activate.
 */
@AutoConfiguration(
        after = FlexScheduleAutoConfiguration.class,
        afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
@ConditionalOnBean(FlexScheduledTaskRegistrar.class)
public class FlexScheduleRedisLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DistributedLock.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    public DistributedLock redisDistributedLock(
            StringRedisTemplate redisTemplate,
            FlexScheduledTaskRegistrar registrar) {
        DistributedLock lock = new RedisDistributedLock(redisTemplate);
        registrar.setDistributedLock(lock);
        return lock;
    }
}
