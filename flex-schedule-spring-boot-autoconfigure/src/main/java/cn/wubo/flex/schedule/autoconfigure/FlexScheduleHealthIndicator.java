package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.TaskInfo;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;

/**
 * Health indicator for the flex scheduler.
 * Reports UP when the scheduler is healthy and provides details about:
 * - Active tasks count
 * - Paused tasks count
 * - Thread pool utilization
 */
public class FlexScheduleHealthIndicator implements HealthIndicator {

    private final FlexScheduledTaskRegistrar registrar;
    private final ThreadPoolTaskScheduler taskScheduler;

    public FlexScheduleHealthIndicator(FlexScheduledTaskRegistrar registrar,
                                          ThreadPoolTaskScheduler taskScheduler) {
        this.registrar = registrar;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public Health health() {
        try {
            List<TaskInfo> tasks = registrar.listTasks();
            int totalTasks = tasks.size();

            // Count paused tasks
            long pausedTasks = tasks.stream()
                    .filter(task -> registrar.getTaskDetail(task.taskName())
                            .map(detail -> detail.paused())
                            .orElse(false))
                    .count();

            Health.Builder builder = Health.up()
                    .withDetail("totalTasks", totalTasks)
                    .withDetail("activeTasks", totalTasks - pausedTasks)
                    .withDetail("pausedTasks", pausedTasks);

            // Thread pool stats — defensive: the scheduler may have been
            // destroyed or replaced (e.g., across hot-reload or in tests).
            if (taskScheduler != null) {
                int poolSize = taskScheduler.getPoolSize();
                int activeCount = taskScheduler.getActiveCount();
                long completedTaskCount = taskScheduler.getScheduledThreadPoolExecutor().getCompletedTaskCount();
                builder.withDetail("poolSize", poolSize)
                        .withDetail("activeThreads", activeCount)
                        .withDetail("completedExecutions", completedTaskCount);
                // Warn if thread pool is heavily utilized
                if (poolSize > 0 && activeCount >= poolSize * 0.8) {
                    builder.withDetail("warning", "Thread pool is heavily utilized (>= 80%)");
                }
            } else {
                builder.withDetail("warning", "Task scheduler is not available");
            }

            return builder.build();
        } catch (Exception e) {
            return Health.down()
                    .withException(e)
                    .build();
        }
    }
}
