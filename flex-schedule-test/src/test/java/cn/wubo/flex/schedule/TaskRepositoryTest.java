package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TaskRepositoryTest {

    @Test
    void inMemoryRepository_save_shouldPersist() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        TaskDefinition def = TaskDefinition.builder("task1", "CRON")
                .cronExpression("0 * * * * *")
                .build();

        repo.save(def);

        assertEquals(1, repo.count());
        assertTrue(repo.findByName("task1").isPresent());
    }

    @Test
    void inMemoryRepository_findByName_notFound_shouldReturnEmpty() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        Optional<TaskDefinition> result = repo.findByName("nonExistent");
        assertTrue(result.isEmpty());
    }

    @Test
    void inMemoryRepository_findAll_shouldReturnAll() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        repo.save(TaskDefinition.builder("task1", "CRON").cronExpression("0 * * * * *").build());
        repo.save(TaskDefinition.builder("task2", "FIXED_DELAY").interval(Duration.ofSeconds(10)).build());

        List<TaskDefinition> all = repo.findAll();
        assertEquals(2, all.size());
    }

    @Test
    void inMemoryRepository_delete_shouldRemove() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        repo.save(TaskDefinition.builder("task1", "CRON").cronExpression("0 * * * * *").build());
        assertEquals(1, repo.count());

        repo.delete("task1");
        assertEquals(0, repo.count());
    }

    @Test
    void inMemoryRepository_deleteAll_shouldClear() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        repo.save(TaskDefinition.builder("task1", "CRON").cronExpression("0 * * * * *").build());
        repo.save(TaskDefinition.builder("task2", "FIXED_DELAY").interval(Duration.ofSeconds(10)).build());

        repo.deleteAll();
        assertEquals(0, repo.count());
    }

    @Test
    void inMemoryRepository_save_overwritesExisting() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        repo.save(TaskDefinition.builder("task1", "CRON").cronExpression("0 * * * * *").build());
        repo.save(TaskDefinition.builder("task1", "FIXED_DELAY").interval(Duration.ofSeconds(10)).build());

        assertEquals(1, repo.count());
        TaskDefinition def = repo.findByName("task1").orElseThrow();
        assertEquals("FIXED_DELAY", def.taskType());
    }

    @Test
    void taskDefinition_builder_shouldSetAllFields() {
        Instant now = Instant.now();
        TaskDefinition def = TaskDefinition.builder("myTask", "CRON")
                .cronExpression("0 * * * * *")
                .beanName("myBean")
                .methodName("myMethod")
                .retryPolicy(RetryPolicy.fixed(3, Duration.ofSeconds(1)))
                .timeout(Duration.ofSeconds(30))
                .paused(true)
                .createdAt(now)
                .build();

        assertEquals("myTask", def.taskName());
        assertEquals("CRON", def.taskType());
        assertEquals("0 * * * * *", def.cronExpression());
        assertEquals("myBean", def.beanName());
        assertEquals("myMethod", def.methodName());
        assertNotNull(def.retryPolicy());
        assertEquals(Duration.ofSeconds(30), def.timeout());
        assertTrue(def.paused());
        assertEquals(now, def.createdAt());
    }

    @Test
    void taskRepository_null_fallsBackToInMemory() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();
        FlexScheduledTaskRegistrar registrar = new FlexScheduledTaskRegistrar(scheduler, 5);
        registrar.setTaskRepository(null);
        assertNotNull(registrar.getTaskRepository());
        assertTrue(registrar.getTaskRepository() instanceof InMemoryTaskRepository);
        registrar.destroy();
    }
}
