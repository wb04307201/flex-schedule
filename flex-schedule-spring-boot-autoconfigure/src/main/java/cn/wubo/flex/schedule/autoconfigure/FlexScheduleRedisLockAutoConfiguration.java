package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.core.DistributedLock;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.redis.RedisDistributedLock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Redis-backed distributed-lock auto-configuration for flex-schedule.
 * Activates a singleton {@link RedisDistributedLock} when Spring Data Redis is
 * on the classpath and wires it onto the {@link FlexScheduledTaskRegistrar}.
 * <p>
 * Split from {@link FlexScheduleAutoConfiguration} for the same Boot 4
 * nested-class-discovery change documented in
 * {@link FlexScheduleEndpointAutoConfiguration}.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
@ConditionalOnBean(FlexScheduledTaskRegistrar.class)
public class FlexScheduleRedisLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DistributedLock.class)
    public DistributedLock redisDistributedLock(
            org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
            FlexScheduledTaskRegistrar registrar) {
        DistributedLock lock = new RedisDistributedLock(redisTemplate);
        registrar.setDistributedLock(lock);
        return lock;
    }
}
