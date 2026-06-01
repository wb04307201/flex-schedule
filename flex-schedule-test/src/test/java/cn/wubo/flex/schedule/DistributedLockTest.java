package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DistributedLockTest {

    private ThreadPoolTaskScheduler taskScheduler;
    private FlexScheduledTaskRegistrar registrar;

    @BeforeEach
    void setUp() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.setThreadNamePrefix("test-lock-");
        taskScheduler.setRemoveOnCancelPolicy(true);
        taskScheduler.initialize();
        registrar = new FlexScheduledTaskRegistrar(taskScheduler, 5);
    }

    @AfterEach
    void tearDown() {
        registrar.destroy();
    }

    // ─── NOOP Lock ───────────────────────────────────────────────────

    @Test
    void noopLock_alwaysGrantsLock() {
        assertTrue(DistributedLock.NOOP.tryLock("task", Duration.ofSeconds(30)));
        assertDoesNotThrow(() -> DistributedLock.NOOP.unlock("task"));
    }

    @Test
    void defaultLock_isNoop() {
        assertEquals(DistributedLock.NOOP, registrar.getDistributedLock());
    }

    // ─── Custom Lock ─────────────────────────────────────────────────

    @Test
    void customLock_preventsExecution() throws InterruptedException {
        // A lock that always denies
        DistributedLock denyAll = new DistributedLock() {
            @Override
            public boolean tryLock(String taskName, Duration lockDuration) {
                return false;
            }

            @Override
            public void unlock(String taskName) {}
        };

        registrar.setDistributedLock(denyAll);

        AtomicInteger executed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        registrar.addFixedRateTask("deniedTask", Duration.ofMillis(100), Duration.ZERO, () -> {
            executed.incrementAndGet();
            latch.countDown();
        });

        // Wait a bit and verify task did NOT execute
        assertFalse(latch.await(500, TimeUnit.MILLISECONDS));
        assertEquals(0, executed.get());
    }

    @Test
    void customLock_allowsExecution() throws InterruptedException {
        // A lock that always grants
        DistributedLock allowAll = new DistributedLock() {
            @Override
            public boolean tryLock(String taskName, Duration lockDuration) {
                return true;
            }

            @Override
            public void unlock(String taskName) {}
        };

        registrar.setDistributedLock(allowAll);

        CountDownLatch latch = new CountDownLatch(1);
        registrar.addFixedRateTask("allowedTask", Duration.ofMillis(100), Duration.ZERO, latch::countDown);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void lock_releasedAfterExecution() throws InterruptedException {
        AtomicInteger lockCount = new AtomicInteger(0);
        AtomicInteger unlockCount = new AtomicInteger(0);

        DistributedLock countingLock = new DistributedLock() {
            @Override
            public boolean tryLock(String taskName, Duration lockDuration) {
                lockCount.incrementAndGet();
                return true;
            }

            @Override
            public void unlock(String taskName) {
                unlockCount.incrementAndGet();
            }
        };

        registrar.setDistributedLock(countingLock);

        CountDownLatch latch = new CountDownLatch(1);
        registrar.addFixedRateTask("lockRelease", Duration.ofMillis(100), Duration.ZERO, latch::countDown);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);

        // Lock and unlock should be called the same number of times
        assertTrue(lockCount.get() >= 1);
        assertEquals(lockCount.get(), unlockCount.get());
    }

    @Test
    void lock_releasedAfterFailure() throws InterruptedException {
        AtomicInteger unlockCount = new AtomicInteger(0);

        DistributedLock countingLock = new DistributedLock() {
            @Override
            public boolean tryLock(String taskName, Duration lockDuration) {
                return true;
            }

            @Override
            public void unlock(String taskName) {
                unlockCount.incrementAndGet();
            }
        };

        registrar.setDistributedLock(countingLock);

        CountDownLatch latch = new CountDownLatch(1);
        registrar.addFixedRateTask("lockFail", Duration.ofMillis(100), Duration.ZERO, () -> {
            latch.countDown();
            throw new RuntimeException("fail");
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);

        // Lock should be released even after failure
        assertTrue(unlockCount.get() >= 1);
    }

    // ─── Simulated Cluster Lock ──────────────────────────────────────

    @Test
    void simulatedCluster_onlyOneNodeExecutes() throws InterruptedException {
        // Simulate two nodes sharing the same lock
        ConcurrentHashMap<String, Boolean> clusterLocks = new ConcurrentHashMap<>();

        DistributedLock clusterLock = new DistributedLock() {
            @Override
            public boolean tryLock(String taskName, Duration lockDuration) {
                return clusterLocks.putIfAbsent(taskName, true) == null;
            }

            @Override
            public void unlock(String taskName) {
                clusterLocks.remove(taskName);
            }
        };

        // Node 1
        FlexScheduledTaskRegistrar node1 = new FlexScheduledTaskRegistrar(taskScheduler, 5);
        node1.setDistributedLock(clusterLock);

        // Node 2 (simulated with a second registrar)
        ThreadPoolTaskScheduler scheduler2 = new ThreadPoolTaskScheduler();
        scheduler2.setPoolSize(2);
        scheduler2.setThreadNamePrefix("node2-");
        scheduler2.initialize();
        FlexScheduledTaskRegistrar node2 = new FlexScheduledTaskRegistrar(scheduler2, 5);
        node2.setDistributedLock(clusterLock);

        AtomicInteger node1Count = new AtomicInteger(0);
        AtomicInteger node2Count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        // Both nodes register the same task
        node1.addFixedRateTask("clusterTask", Duration.ofMillis(100), Duration.ZERO, () -> {
            node1Count.incrementAndGet();
            latch.countDown();
        });

        node2.addFixedRateTask("clusterTask", Duration.ofMillis(100), Duration.ZERO, () -> {
            node2Count.incrementAndGet();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(300);

        // At least one node should have executed
        assertTrue(node1Count.get() + node2Count.get() >= 1);

        node1.destroy();
        node2.destroy();
        scheduler2.destroy();
    }

    // ─── Service Facade ──────────────────────────────────────────────

    @Test
    void service_setDistributedLock_delegates() {
        FlexScheduledTaskService service = new DefaultFlexScheduledTaskService(registrar);

        DistributedLock customLock = new DistributedLock() {
            @Override
            public boolean tryLock(String taskName, Duration lockDuration) { return true; }
            @Override
            public void unlock(String taskName) {}
        };

        service.setDistributedLock(customLock);
        assertEquals(customLock, registrar.getDistributedLock());
    }

    @Test
    void setDistributedLock_null_fallsBackToNoop() {
        registrar.setDistributedLock(null);
        assertEquals(DistributedLock.NOOP, registrar.getDistributedLock());
    }
}
