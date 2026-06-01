package cn.wubo.flex.schedule.autoconfigure.endpoint;

import java.util.List;

/**
 * Request body for adding a task via the Actuator endpoint.
 * Only {@code BeanMethodRunnable} style tasks are supported via REST.
 */
public class AddTaskRequest {

    private String taskName;
    private String taskType;       // CRON, FIXED_DELAY, FIXED_RATE
    private String cron;           // required when taskType=CRON
    private Long intervalSeconds;  // required when taskType=FIXED_DELAY or FIXED_RATE
    private Long initialDelaySeconds; // optional, default 0
    private String beanName;       // required
    private String methodName;     // required
    private List<Object> methodParams; // optional

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public Long getIntervalSeconds() { return intervalSeconds; }
    public void setIntervalSeconds(Long intervalSeconds) { this.intervalSeconds = intervalSeconds; }
    public Long getInitialDelaySeconds() { return initialDelaySeconds; }
    public void setInitialDelaySeconds(Long initialDelaySeconds) { this.initialDelaySeconds = initialDelaySeconds; }
    public String getBeanName() { return beanName; }
    public void setBeanName(String beanName) { this.beanName = beanName; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public List<Object> getMethodParams() { return methodParams; }
    public void setMethodParams(List<Object> methodParams) { this.methodParams = methodParams; }
}
