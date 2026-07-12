# flex-schedule-redis

Redis-backed [`DistributedLock`](../flex-schedule/src/main/java/cn/wubo/flex/schedule/core/DistributedLock.java)
implementation for cluster-aware scheduling.

When a Spring Boot application runs in multiple instances (cluster / blue-green
deploy / rolling restart), two instances can both fire the same scheduled task at
the same time unless they coordinate. `RedisDistributedLock` uses Redis `SETNX`
with a TTL so that only the instance that wins the lock actually runs the task.

## Installation

```xml
<dependency>
    <groupId>io.github.wb04307201</groupId>
    <artifactId>flex-schedule-redis</artifactId>
    <version>1.2.1</version>
</dependency>
```

Or Gradle:

```groovy
implementation 'io.github.wb04307201:flex-schedule-redis:1.2.1'
```

`spring-boot-starter-data-redis` must be on the classpath for auto-configuration
to take effect. The starter is marked `optional` to avoid forcing a Redis
dependency on users who don't need it.

## Auto-configuration

Once both `flex-schedule-spring-boot-starter` and `flex-schedule-redis` are on the
classpath, `FlexScheduleAutoConfiguration` automatically wires
`RedisDistributedLock` into `FlexScheduledTaskRegistrar` whenever Spring Data
Redis is present (`org.springframework.data.redis.core.StringRedisTemplate`).

No additional configuration is required. Make sure your application has the
standard Spring Data Redis properties set:

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      # password: yourpassword   # if your Redis requires auth
```

### Manual bean override

If you want to customize the lock (e.g., key prefix, redis template, etc.),
define your own `DistributedLock` bean — auto-configuration backs off:

```java
@Bean
public DistributedLock redisDistributedLock(StringRedisTemplate redisTemplate) {
    return new RedisDistributedLock(redisTemplate);
}
```

## How it works

For each scheduled task at fire time, `FlexScheduledTaskRegistrar.instrument()`
calls `distributedLock.tryLock(taskName, lockDuration)` where `lockDuration`
defaults to the task's interval (or 30s for cron tasks). If `tryLock` returns
`false`, the instance skips the execution; if it returns `true`, the task runs
and the lock is released in `finally`.

Under the hood `RedisDistributedLock`:

1. Stores the lock as `flex-schedule:lock:<taskName>` in Redis.
2. Writes a per-instance UUID as the value so that only the instance that owns
   the lock can release it.
3. Sets a TTL on the key so a crashed instance's lock auto-expires.

## Semantics

| Behavior | Guarantee |
|----------|-----------|
| `tryLock` on an unowned key | Returns `true`. |
| `tryLock` on an already-owned key (same instance) | Returns `false`. |
| `tryLock` on an already-owned key (different instance) | Returns `false`. |
| `tryLock` after TTL expiry | Returns `true` (lock is re-acquirable). |
| `unlock` on own lock | Releases the lock. |
| `unlock` on another instance's lock | No-op (value mismatch). |
| `unlock` when no lock exists | No-op, never throws. |

This is **not** a Redlock implementation — it's a single-node SETNX with TTL,
which is sufficient for "at most one instance runs a task at a time" but does
not provide the stronger Redlock guarantees across multiple Redis nodes. If you
need Redlock-style safety, replace the bean with your own implementation.

## Testing

The integration tests in this module require a live Redis. By default they're
skipped; opt in by setting environment variables:

```bash
FLEX_RUN_REDIS_TESTS=true \
FLEX_REDIS_HOST=localhost \
FLEX_REDIS_PORT=6379 \
FLEX_REDIS_PASSWORD=optional \
mvn test
```

If `FLEX_RUN_REDIS_TESTS` is unset (or Redis is unreachable), the tests skip
silently so CI without Redis stays green.