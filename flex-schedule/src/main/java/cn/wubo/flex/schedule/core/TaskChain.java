package cn.wubo.flex.schedule.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Allows chaining multiple task executions in sequence.
 * <p>
 * Each step in the chain executes after the previous one completes.
 * If any step fails, the chain stops and the error propagates.
 * </p>
 *
 * <pre>{@code
 * TaskChain.create(registrar)
 *     .then("step1", () -> doStep1())
 *     .then("step2", () -> doStep2())
 *     .then("step3", () -> doStep3())
 *     .execute();
 * }</pre>
 */
public class TaskChain {

    private final FlexScheduledTaskRegistrar registrar;
    private final List<ChainStep> steps = new ArrayList<>();
    private Executor executor;

    private TaskChain(FlexScheduledTaskRegistrar registrar) {
        this.registrar = registrar;
    }

    /**
     * Creates a new task chain.
     *
     * @param registrar the task registrar to use for scheduling
     * @return a new TaskChain builder
     */
    public static TaskChain create(FlexScheduledTaskRegistrar registrar) {
        return new TaskChain(registrar);
    }

    /**
     * Adds a step to the chain.
     *
     * @param name     step name (for logging)
     * @param runnable the step implementation
     * @return this chain builder
     */
    public TaskChain then(String name, Runnable runnable) {
        steps.add(new ChainStep(name, runnable, null));
        return this;
    }

    /**
     * Adds a step with a timeout to the chain.
     *
     * @param name     step name (for logging)
     * @param timeout  maximum execution time for this step
     * @param runnable the step implementation
     * @return this chain builder
     */
    public TaskChain then(String name, Duration timeout, Runnable runnable) {
        steps.add(new ChainStep(name, runnable, timeout));
        return this;
    }

    /**
     * Sets the executor for running chain steps.
     * If not set, uses {@link CompletableFuture#runAsync(Runnable)}.
     *
     * @param executor the executor to use
     * @return this chain builder
     */
    public TaskChain withExecutor(Executor executor) {
        this.executor = executor;
        return this;
    }

    /**
     * Executes the chain asynchronously.
     *
     * @return a CompletableFuture that completes when all steps finish
     */
    public CompletableFuture<Void> execute() {
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);

        for (ChainStep step : steps) {
            future = future.thenRunAsync(() -> executeStep(step),
                    executor != null ? executor : Runnable::run);
        }

        return future;
    }

    /**
     * Schedules the chain to execute after a delay.
     *
     * @param taskName unique name for the scheduled chain
     * @param delay    delay before execution
     * @return a CompletableFuture that completes when all steps finish
     */
    public CompletableFuture<Void> schedule(String taskName, Duration delay) {
        CompletableFuture<Void> result = new CompletableFuture<>();

        registrar.schedule(taskName, delay, () -> {
            try {
                execute().join();
                result.complete(null);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });

        return result;
    }

    private void executeStep(ChainStep step) {
        Runnable runnable = step.runnable();
        if (step.timeout() != null) {
            runnable = new TimeoutRunnable(step.name(), runnable, step.timeout());
        }
        runnable.run();
    }

    private record ChainStep(String name, Runnable runnable, Duration timeout) {}
}
