package cn.wubo.flex.schedule.autoconfigure.endpoint;

import cn.wubo.flex.schedule.core.BeanMethodRunnable;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.TaskDetail;
import cn.wubo.flex.schedule.core.TaskInfo;
import org.springframework.boot.actuate.endpoint.annotation.*;
import org.springframework.util.Assert;

import java.util.*;

/**
 * Actuator endpoint for flex schedule management.
 * <p>
 * Exposed at {@code /actuator/flexschedule}. Supports:
 * <ul>
 *   <li>GET — list all tasks</li>
 *   <li>GET /{name} — get task detail</li>
 *   <li>POST — add a task (BeanMethodRunnable style)</li>
 *   <li>DELETE /{name} — cancel a task</li>
 * </ul>
 * <p>
 * POST and DELETE operations are controlled by {@link EndpointAccessControl}.
 * By default, write operations are disabled.
 */
@Endpoint(id = "flexschedule")
public class FlexScheduleEndpoint {

    private final FlexScheduledTaskService taskService;
    private final EndpointAccessControl accessControl;

    public FlexScheduleEndpoint(FlexScheduledTaskService taskService, EndpointAccessControl accessControl) {
        this.taskService = taskService;
        this.accessControl = accessControl;
    }

    @ReadOperation
    public List<TaskInfo> listTasks() {
        return taskService.listTasks();
    }

    @ReadOperation
    public Object getTask(@Selector String name) {
        Optional<TaskDetail> detail = taskService.getTaskDetail(name);
        if (detail.isPresent()) {
            return detail.get();
        }
        return Map.of("error", "Task [" + name + "] not found");
    }

    @WriteOperation
    public Object addTask(String taskName, String taskType, String beanName, String methodName,
                         String cron, Long intervalSeconds, Long initialDelaySeconds,
                         List<Object> methodParams) {
        if (!accessControl.canAddTask(beanName, methodName)) {
            return Map.of("error", "Write operations are not enabled or bean is not in the allowlist");
        }

        AddTaskRequest request = new AddTaskRequest();
        request.setTaskName(taskName);
        request.setTaskType(taskType);
        request.setBeanName(beanName);
        request.setMethodName(methodName);
        request.setCron(cron);
        request.setIntervalSeconds(intervalSeconds);
        request.setInitialDelaySeconds(initialDelaySeconds);
        request.setMethodParams(methodParams);

        try {
            validateRequest(request);
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        }

        Runnable runnable;
        if (methodParams != null && !methodParams.isEmpty()) {
            runnable = new BeanMethodRunnable(beanName, methodName, methodParams);
        } else {
            runnable = new BeanMethodRunnable(beanName, methodName);
        }

        try {
            long initialDelay = initialDelaySeconds != null ? initialDelaySeconds : 0L;
            switch (taskType.toUpperCase()) {
                case "CRON" -> taskService.add(taskName, cron, runnable);
                case "FIXED_DELAY" -> taskService.addFixedDelayTask(taskName, intervalSeconds, initialDelay, runnable);
                case "FIXED_RATE" -> taskService.addFixedRateTask(taskName, intervalSeconds, initialDelay, runnable);
                default -> {
                    return Map.of("error", "Unknown taskType: " + taskType);
                }
            }
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }

        return Map.of("status", "added", "taskName", taskName);
    }

    @DeleteOperation
    public Object cancelTask(@Selector String name) {
        if (!accessControl.canCancelTask(name)) {
            return Map.of("error", "Write operations are not enabled");
        }

        if (!taskService.exists(name)) {
            return Map.of("error", "Task [" + name + "] not found");
        }
        taskService.cancel(name);
        return Map.of("status", "cancelled", "taskName", name);
    }

    @WriteOperation
    public Object pauseTask(@Selector(match = Selector.Match.ALL_REMAINING) String taskToPause) {
        if (!accessControl.canCancelTask(taskToPause)) {
            return Map.of("error", "Write operations are not enabled");
        }

        if (!taskService.exists(taskToPause)) {
            return Map.of("error", "Task [" + taskToPause + "] not found");
        }
        taskService.pause(taskToPause);
        return Map.of("status", "paused", "taskName", taskToPause);
    }

    @WriteOperation
    public Object resumeTask(@Selector(match = Selector.Match.ALL_REMAINING) String taskToResume) {
        if (!accessControl.canCancelTask(taskToResume)) {
            return Map.of("error", "Write operations are not enabled");
        }

        if (!taskService.exists(taskToResume)) {
            return Map.of("error", "Task [" + taskToResume + "] not found");
        }
        taskService.resume(taskToResume);
        return Map.of("status", "resumed", "taskName", taskToResume);
    }

    private void validateRequest(AddTaskRequest request) {
        Assert.hasText(request.getTaskName(), "taskName is required");
        Assert.hasText(request.getTaskType(), "taskType is required");
        Assert.hasText(request.getBeanName(), "beanName is required");
        Assert.hasText(request.getMethodName(), "methodName is required");

        String type = request.getTaskType().toUpperCase();
        if ("CRON".equals(type)) {
            Assert.hasText(request.getCron(), "cron is required for CRON tasks");
        } else if ("FIXED_DELAY".equals(type) || "FIXED_RATE".equals(type)) {
            Assert.notNull(request.getIntervalSeconds(), "intervalSeconds is required for FIXED_DELAY/FIXED_RATE tasks");
            Assert.isTrue(request.getIntervalSeconds() > 0, "intervalSeconds must be positive");
        } else {
            throw new IllegalArgumentException("Unknown taskType: " + request.getTaskType()
                    + ". Must be CRON, FIXED_DELAY, or FIXED_RATE");
        }
    }
}
