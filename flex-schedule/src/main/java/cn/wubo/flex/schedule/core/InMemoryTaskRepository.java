package cn.wubo.flex.schedule.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link TaskRepository}.
 * <p>
 * This is the default implementation that stores task definitions in a {@link ConcurrentHashMap}.
 * Task definitions are lost when the application restarts.
 * </p>
 * <p>
 * For production use where tasks must survive restarts, provide a persistent implementation
 * (e.g., JdbcTaskRepository) as a Spring bean to override this default.
 * </p>
 */
public class InMemoryTaskRepository implements TaskRepository {

    private final ConcurrentHashMap<String, TaskDefinition> store = new ConcurrentHashMap<>();

    @Override
    public void save(TaskDefinition definition) {
        store.put(definition.taskName(), definition);
    }

    @Override
    public Optional<TaskDefinition> findByName(String taskName) {
        return Optional.ofNullable(store.get(taskName));
    }

    @Override
    public List<TaskDefinition> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public void delete(String taskName) {
        store.remove(taskName);
    }

    @Override
    public void deleteAll() {
        store.clear();
    }

    @Override
    public int count() {
        return store.size();
    }
}
