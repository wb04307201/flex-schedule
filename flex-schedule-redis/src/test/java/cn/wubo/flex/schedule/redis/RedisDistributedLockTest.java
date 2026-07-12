package cn.wubo.flex.schedule.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.Socket;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-Redis integration tests for {@link RedisDistributedLock}.
 *
 * <p>Requires a reachable Redis instance. By default tries {@code localhost:6379};
 * override via the {@code FLEX_REDIS_HOST} / {@code FLEX_REDIS_PORT} environment variables.
 * If no Redis is reachable, every test is skipped (assumption failure rather than
 * hard failure — so the build stays green in environments without Redis).
 */
@EnabledIfEnvironmentVariable(named = "FLEX_RUN_REDIS_TESTS", matches = "true")
class RedisDistributedLockTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;

    private RedisDistributedLock lockA;
    private RedisDistributedLock lockB;

    @BeforeAll
    static void connectRedis() {
        String host = System.getenv().getOrDefault("FLEX_REDIS_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("FLEX_REDIS_PORT", "6379"));
        String password = System.getenv("FLEX_REDIS_PASSWORD"); // optional

        // Skip the whole class if Redis is not reachable.
        assumeTrue(canConnect(host, port),
            "Skipping Redis tests: cannot connect to " + host + ":" + port);

        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            cfg.setPassword(password);
        }
        connectionFactory = new LettuceConnectionFactory(cfg);
        connectionFactory.afterPropertiesSet();

        template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void newLocks() {
        lockA = new RedisDistributedLock(template);
        lockB = new RedisDistributedLock(template);
    }

    @AfterEach
    void cleanup() {
        // Clean up all lock keys we created so tests don't pollute each other.
        var keys = template.keys("flex-schedule:lock:redis-test-*");
        if (keys != null && !keys.isEmpty()) {
            template.delete(keys);
        }
    }

    private static boolean canConnect(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String freshTaskName() {
        return "redis-test-" + UUID.randomUUID();
    }

    // ─── constructor ────────────────────────────────────────────────

    @Test
    void constructor_nullTemplate_throws() {
        assertThrows(NullPointerException.class, () -> new RedisDistributedLock(null));
    }

    // ─── tryLock ─────────────────────────────────────────────────────

    @Test
    void tryLock_unowned_returnsTrue() {
        String task = freshTaskName();
        assertTrue(lockA.tryLock(task, Duration.ofSeconds(10)),
            "First tryLock on an unowned key should succeed");
    }

    @Test
    void tryLock_alreadyOwnedBySameInstance_returnsFalse() {
        String task = freshTaskName();
        assertTrue(lockA.tryLock(task, Duration.ofSeconds(10)));
        assertFalse(lockA.tryLock(task, Duration.ofSeconds(10)),
            "Re-acquiring an already-held lock must fail");
    }

    @Test
    void tryLock_alreadyOwnedByOtherInstance_returnsFalse() {
        String task = freshTaskName();
        assertTrue(lockA.tryLock(task, Duration.ofSeconds(10)));
        assertFalse(lockB.tryLock(task, Duration.ofSeconds(10)),
            "Concurrent instance must not steal an existing lock");
    }

    @Test
    void tryLock_differentTasks_bothSucceed() {
        String taskA = freshTaskName();
        String taskB = freshTaskName();
        assertTrue(lockA.tryLock(taskA, Duration.ofSeconds(10)));
        assertTrue(lockA.tryLock(taskB, Duration.ofSeconds(10)),
            "Independent tasks must not block each other");
    }

    @Test
    void tryLock_expiresAfterDuration_canBeReacquired() throws InterruptedException {
        String task = freshTaskName();
        assertTrue(lockA.tryLock(task, Duration.ofMillis(300)));
        assertFalse(lockB.tryLock(task, Duration.ofSeconds(10)));

        // Wait for the TTL to lapse.
        Thread.sleep(500);

        assertTrue(lockB.tryLock(task, Duration.ofSeconds(10)),
            "Lock must be re-acquirable once the TTL elapses");
    }

    // ─── unlock ──────────────────────────────────────────────────────

    @Test
    void unlock_ownLock_releasesIt() {
        String task = freshTaskName();
        assertTrue(lockA.tryLock(task, Duration.ofSeconds(10)));
        assertFalse(lockB.tryLock(task, Duration.ofSeconds(10)));

        lockA.unlock(task);

        assertTrue(lockB.tryLock(task, Duration.ofSeconds(10)),
            "After unlock, another instance must be able to acquire the lock");
    }

    @Test
    void unlock_otherInstancesLock_doesNotRelease() {
        String task = freshTaskName();
        assertTrue(lockA.tryLock(task, Duration.ofSeconds(10)));

        // lockB tries to unlock a lock it doesn't own — must be a no-op.
        lockB.unlock(task);

        // The lock is still held by A.
        assertFalse(lockB.tryLock(task, Duration.ofSeconds(10)),
            "unlock from non-owner must NOT release the lock");
    }

    @Test
    void unlock_lockNotHeld_doesNotThrow() {
        // No prior tryLock — unlock should silently no-op.
        assertDoesNotThrow(() -> lockA.unlock(freshTaskName()));
    }

    @Test
    void unlock_afterExpiration_doesNotThrow() throws InterruptedException {
        String task = freshTaskName();
        assertTrue(lockA.tryLock(task, Duration.ofMillis(100)));
        Thread.sleep(300); // wait for expiry
        assertDoesNotThrow(() -> lockA.unlock(task),
            "Unlocking an expired lock must not throw");
    }

    // ─── value isolation across instances ──────────────────────────

    @Test
    void eachInstance_hasItsOwnLockValue() {
        // Constructing two locks should produce distinct UUID values; if they
        // shared a value, one instance could unlock the other's lock.
        // We can't access lockValue directly (private), but the behavior
        // observable through unlock proves it: lockA can unlock what lockA acquired,
        // but lockB cannot unlock what lockA acquired (covered by unlock_otherInstancesLock_doesNotRelease).
        String task = freshTaskName();
        assertTrue(lockA.tryLock(task, Duration.ofSeconds(10)));
        lockB.unlock(task); // must be a no-op

        // A new tryLock from B must still fail because A still holds it.
        assertFalse(lockB.tryLock(task, Duration.ofSeconds(10)));
    }
}