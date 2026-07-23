# flex-schedule-redis（中文）

<div align="right">
  <a href="README.md">English</a> | 中文
</div>

基于 Redis 的 [`DistributedLock`](../flex-schedule/src/main/java/cn/wubo/flex/schedule/core/DistributedLock.java)
实现，用于集群感知调度。

当 Spring Boot 应用以多实例方式部署（集群 / 蓝绿发布 / 滚动重启）时，如果没有协调机制，
两个实例会在同一时刻触发同一个定时任务。`RedisDistributedLock` 通过 Redis `SETNX`
加 TTL 来保证同一时刻只有一个实例能拿到锁并真正执行任务。

## 安装

```xml
<dependency>
    <groupId>io.github.wb04307201</groupId>
    <artifactId>flex-schedule-redis</artifactId>
    <version>1.2.2</version>
</dependency>
```

或者 Gradle：

```groovy
implementation 'io.github.wb04307201:flex-schedule-redis:1.2.2'
```

`spring-boot-starter-data-redis` 必须存在于 classpath，自动装配才会生效。
该依赖被标记为 `optional`，避免给不需要 Redis 的用户强加依赖。

## 自动装配

只要 `flex-schedule-spring-boot-starter` 和 `flex-schedule-redis` 都在 classpath 上，
`FlexScheduleAutoConfiguration` 会在 Spring Data Redis 存在时（`org.springframework.data.redis.core.StringRedisTemplate`）
自动把 `RedisDistributedLock` 装配到 `FlexScheduledTaskRegistrar`。

无需额外配置。只需要确保应用配置了标准的 Spring Data Redis 属性：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      # password: 你的密码   # 如果 Redis 要求认证
```

### 手动覆盖 Bean

如果想自定义锁（例如修改 key 前缀、使用不同的 Redis 客户端等），定义自己的 `DistributedLock` Bean，自动装配会自动让位：

```java
@Bean
public DistributedLock redisDistributedLock(StringRedisTemplate redisTemplate) {
    return new RedisDistributedLock(redisTemplate);
}
```

## 工作原理

每次定时任务触发时，`FlexScheduledTaskRegistrar.instrument()` 调用
`distributedLock.tryLock(taskName, lockDuration)`，其中 `lockDuration` 默认为
任务的 interval（fixed-delay / fixed-rate 任务）或 30 秒（cron 和 one-shot 任务）。
如果 `tryLock` 返回 `false`，实例跳过本次执行；如果返回 `true`，执行任务并在
`finally` 中释放锁。

`RedisDistributedLock` 内部实现：

1. 把锁以 `flex-schedule:lock:<taskName>` 为 key 存到 Redis。
2. 用每个实例的 UUID 作为 value，确保只有持锁实例能释放锁。
3. 给 key 设置 TTL，避免崩溃实例的锁永远阻塞。

## 语义保证

| 行为 | 保证 |
|------|------|
| `tryLock` 未被持有的 key | 返回 `true` |
| `tryLock` 已被同实例持有的 key | 返回 `false` |
| `tryLock` 已被其他实例持有的 key | 返回 `false` |
| `tryLock` 在 TTL 过期之后 | 返回 `true`（锁可重新获取） |
| `unlock` 释放自己的锁 | 释放成功 |
| `unlock` 释放他人的锁 | no-op（value 不匹配） |
| `unlock` 释放不存在的锁 | no-op，永不抛异常 |

**这不是 Redlock 实现** —— 这是单节点 SETNX + TTL，足以满足"同一时刻只有一个实例
执行任务"的需求，但不提供 Redlock 在多个 Redis 节点间的更强保证。如果你需要 Redlock
级别的安全保证，请替换为自定义实现。

## 测试

本模块的集成测试需要真实的 Redis。默认情况下会跳过；通过环境变量显式开启：

```bash
FLEX_RUN_REDIS_TESTS=true \
FLEX_REDIS_HOST=localhost \
FLEX_REDIS_PORT=6379 \
FLEX_REDIS_PASSWORD=可选 \
mvn test
```

如果 `FLEX_RUN_REDIS_TESTS` 未设置（或 Redis 不可达），测试会静默跳过，
这样没有 Redis 的 CI 环境依然能保持绿色。