package cn.wubo.flex.schedule.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * H2 / JDBC-backed {@link TaskRepository}.
 * <p>
 * Persists task definitions across application restarts. Auto-creates the
 * {@code flex_scheduled_task} table on first use via {@link #ensureSchema()}.
 * </p>
 */
public class JdbcTaskRepository implements TaskRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcTaskRepository.class);

    public static final String TABLE_NAME = "flex_scheduled_task";

    private static final String DDL = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("
            + " task_name VARCHAR(255) PRIMARY KEY,"
            + " type VARCHAR(20) NOT NULL,"
            + " cron_expression VARCHAR(100),"
            + " timezone VARCHAR(50),"
            + " interval_ms BIGINT,"
            + " initial_delay_ms BIGINT,"
            + " delay_ms BIGINT,"
            + " timeout_ms BIGINT,"
            + " retry_policy_json CLOB,"
            + " bean_name VARCHAR(255),"
            + " method_name VARCHAR(255),"
            + " method_params_json CLOB,"
            + " paused BOOLEAN NOT NULL DEFAULT FALSE,"
            + " created_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,"
            + " updated_at TIMESTAMP(9) WITH TIME ZONE NOT NULL"
            + ")";

    private final JdbcTemplate jdbc;

    public JdbcTaskRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public JdbcTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the persistence table if absent. Safe to invoke multiple times.
     */
    public void ensureSchema() {
        jdbc.execute(DDL);
        log.debug("JdbcTaskRepository schema ensured at {}", TABLE_NAME);
    }

    @Override
    public void save(TaskDefinition def) {
        String sql = "MERGE INTO " + TABLE_NAME
            + " (task_name, type, cron_expression, timezone,"
            + " interval_ms, initial_delay_ms, delay_ms, timeout_ms,"
            + " retry_policy_json, bean_name, method_name, method_params_json,"
            + " paused, created_at, updated_at)"
            + " KEY(task_name)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.update(sql,
                def.taskName(),
                def.taskType(),
                def.cronExpression(),
                def.timezone() != null ? def.timezone().getId() : null,
                msOrNull(def.interval()),
                msOrNull(def.initialDelay()),
                msOrNull(def.delay()),
                msOrNull(def.timeout()),
                null,                 // retry_policy_json — not implemented in JdbcTaskRepository v1
                def.beanName(),
                def.methodName(),
                null,                 // method_params_json — not implemented in JdbcTaskRepository v1
                def.paused(),
                Timestamp.from(def.createdAt()),
                Timestamp.from(def.updatedAt()));
    }

    @Override
    public Optional<TaskDefinition> findByName(String taskName) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM " + TABLE_NAME + " WHERE task_name = ?",
                    (rs, rowNum) -> mapRow(rs),
                    taskName));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<TaskDefinition> findAll() {
        return jdbc.query("SELECT * FROM " + TABLE_NAME + " ORDER BY task_name",
                (rs, rowNum) -> mapRow(rs))
                .stream()
                .sorted(Comparator.comparing(TaskDefinition::taskName))
                .toList();
    }

    @Override
    public void delete(String taskName) {
        jdbc.update("DELETE FROM " + TABLE_NAME + " WHERE task_name = ?", taskName);
    }

    @Override
    public void deleteAll() {
        jdbc.update("DELETE FROM " + TABLE_NAME);
    }

    @Override
    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE_NAME, Integer.class);
        return n != null ? n : 0;
    }

    private static Long msOrNull(Duration d) {
        return d == null ? null : d.toMillis();
    }

    private static Duration msOrNullToDuration(Long ms) {
        return ms == null ? null : Duration.ofMillis(ms);
    }

    private static TaskDefinition mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return TaskDefinition.builder(rs.getString("task_name"), rs.getString("type"))
                .cronExpression(rs.getString("cron_expression"))
                .timezone(rs.getString("timezone") != null ? java.time.ZoneId.of(rs.getString("timezone")) : null)
                .interval(msOrNullToDuration((Long) rs.getObject("interval_ms")))
                .initialDelay(msOrNullToDuration((Long) rs.getObject("initial_delay_ms")))
                .delay(msOrNullToDuration((Long) rs.getObject("delay_ms")))
                .timeout(msOrNullToDuration((Long) rs.getObject("timeout_ms")))
                .beanName(rs.getString("bean_name"))
                .methodName(rs.getString("method_name"))
                .paused(rs.getBoolean("paused"))
                .createdAt(rs.getTimestamp("created_at").toInstant())
                .updatedAt(rs.getTimestamp("updated_at").toInstant())
                .build();
    }
}