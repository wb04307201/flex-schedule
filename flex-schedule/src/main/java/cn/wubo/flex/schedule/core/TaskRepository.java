package cn.wubo.flex.schedule.core;

import java.util.List;
import java.util.Optional;

/**
 * Persistence interface for scheduled task definitions.
 * <p>
 * Implementations store and retrieve {@link TaskDefinition} objects so that
 * dynamically added tasks survive application restarts.
 * </p>
 * <p>
 * The default implementation ({@link InMemoryTaskRepository}) stores tasks in memory
 * and loses them on restart. For production use, provide a persistent implementation
 * (e.g., JDBC, Redis) as a Spring bean to override the default.
 * </p>
 */
public interface TaskRepository {

    /**
     * Saves or updates a task definition.
     *
     * @param definition the task definition to save
     */
    void save(TaskDefinition definition);

    /**
     * Finds a task definition by name.
     *
     * @param taskName the task name
     * @return the task definition, or empty if not found
     */
    Optional<TaskDefinition> findByName(String taskName);

    /**
     * Returns all persisted task definitions.
     *
     * @return list of all task definitions
     */
    List<TaskDefinition> findAll();

    /**
     * Deletes a task definition by name.
     *
     * @param taskName the task name to delete
     */
    void delete(String taskName);

    /**
     * Deletes all task definitions.
     */
    void deleteAll();

    /**
     * Returns the number of persisted task definitions.
     */
    int count();
}
