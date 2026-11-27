package io.github.aw1y2z.sesame.data.task;

public interface ChildTaskExecutor {

    Boolean addChildTask(ModelTask.ChildModelTask childTask);

    Boolean removeChildTask(ModelTask.ChildModelTask childTask);

    Boolean clearGroupChildTask(String group);

    Boolean clearAllChildTask();

    /**
     * 执行器是否还可用于提交新子任务。
     * <p>{@link #clearAllChildTask()} 会关掉内部线程池，之后提交会被拒绝；
     * 调用方据此判断是否需要重建执行器。
     */
    Boolean isUsable();

}
