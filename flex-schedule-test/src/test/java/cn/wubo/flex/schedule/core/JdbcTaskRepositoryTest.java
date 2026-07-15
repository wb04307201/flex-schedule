package cn.wubo.flex.schedule.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTaskRepositoryTest {

    private JdbcTaskRepository repository;

    @BeforeEach
    void setUp() {
        // Use H2 in-memory for tests
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:test-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        repository = new JdbcTaskRepository(jdbc);
        repository.ensureSchema();
    }

    @Test
    void savesAndFindsByName() {
        TaskDefinition def = TaskDefinition.builder("cron-task", "CRON")
                .cronExpression("0 * * * * *")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        repository.save(def);
        Optional<TaskDefinition> found = repository.findByName("cron-task");

        assertThat(found).isPresent();
        assertThat(found.get().cronExpression()).isEqualTo("0 * * * * *");
        assertThat(found.get().createdAt()).isEqualTo(def.createdAt());
    }

    @Test
    void saveIsUpsert() {
        TaskDefinition def = TaskDefinition.builder("cron-task", "CRON")
                .cronExpression("0 * * * * *")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        repository.save(def);

        TaskDefinition updated = TaskDefinition.builder("cron-task", "CRON")
                .cronExpression("0 0 * * * *")
                .createdAt(def.createdAt())
                .updatedAt(Instant.now())
                .build();
        repository.save(updated);

        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findByName("cron-task").get().cronExpression())
                .isEqualTo("0 0 * * * *");
    }

    @Test
    void findAllReturnsEverythingSortedByName() {
        repository.save(TaskDefinition.builder("beta", "FIXED_RATE")
                .interval(Duration.ofMinutes(5)).createdAt(Instant.now()).updatedAt(Instant.now()).build());
        repository.save(TaskDefinition.builder("alpha", "FIXED_RATE")
                .interval(Duration.ofMinutes(1)).createdAt(Instant.now()).updatedAt(Instant.now()).build());
        repository.save(TaskDefinition.builder("gamma", "CRON")
                .cronExpression("0 0 * * * *").createdAt(Instant.now()).updatedAt(Instant.now()).build());

        List<TaskDefinition> all = repository.findAll();

        assertThat(all).extracting(TaskDefinition::taskName)
                .containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void deleteRemovesByName() {
        repository.save(TaskDefinition.builder("cron-task", "CRON")
                .cronExpression("0 * * * * *").createdAt(Instant.now()).updatedAt(Instant.now()).build());
        repository.delete("cron-task");
        assertThat(repository.findByName("cron-task")).isEmpty();
        assertThat(repository.count()).isZero();
    }

    @Test
    void deleteAllClearsTable() {
        repository.save(TaskDefinition.builder("a", "CRON")
                .cronExpression("0 * * * * *").createdAt(Instant.now()).updatedAt(Instant.now()).build());
        repository.save(TaskDefinition.builder("b", "CRON")
                .cronExpression("0 * * * * *").createdAt(Instant.now()).updatedAt(Instant.now()).build());
        repository.deleteAll();
        assertThat(repository.count()).isZero();
    }

    @Test
    void persistsIntervalAndInitialDelay() {
        repository.save(TaskDefinition.builder("fd", "FIXED_DELAY")
                .interval(Duration.ofSeconds(30))
                .initialDelay(Duration.ofSeconds(10))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        TaskDefinition loaded = repository.findByName("fd").orElseThrow();
        assertThat(loaded.interval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(loaded.initialDelay()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void persistsOneShotDelay() {
        repository.save(TaskDefinition.builder("once", "ONE_SHOT")
                .delay(Duration.ofMinutes(5))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        TaskDefinition loaded = repository.findByName("once").orElseThrow();
        assertThat(loaded.delay()).isEqualTo(Duration.ofMinutes(5));
    }
}