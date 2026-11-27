package io.github.aw1y2z.sesame.data.task;

import static io.github.aw1y2z.sesame.model.normal.base.BaseModel.taskRpcRequest;

import io.github.aw1y2z.sesame.util.FileUtil;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;
import lombok.Getter;
import io.github.aw1y2z.sesame.data.Model;
import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.ModelType;
import io.github.aw1y2z.sesame.model.normal.base.BaseModel;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.NotificationUtil;
import io.github.aw1y2z.sesame.util.RunGeneration;
import io.github.aw1y2z.sesame.util.StringUtil;
import io.github.aw1y2z.sesame.util.TaskCancelledException;
import io.github.aw1y2z.sesame.util.TimeUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class ModelTask extends Model {

    /**
     * 正在运行的模型任务：模型实例 → 本次运行记录。
     * <p>key 是模型实例（同一实例的下一代会复用同一个 key），value 记录代际号与线程。
     */
    private static final Map<ModelTask, TaskRun> MAIN_TASK_MAP = new ConcurrentHashMap<>();

    /** 一次模型运行的记录 */
    private static final class TaskRun {

        /** 本次运行的代际号 */
        final long generation;
        /** 真正跑起来的线程；线程池异步提交，起来之前为 null */
        volatile Thread thread;
        /** 线程是否已经起来过（起来过又退出才算结束） */
        volatile boolean started;

        TaskRun(long generation) {
            this.generation = generation;
        }
    }

    /** 本模型的代际编号：stopTask 递增，旧代在下一个检查点作废退出，无需 join 阻塞调用方 */
    private volatile long generation = 0;

    /**
     * 起跑前登记的运行记录：{@link #startTask} 同步写入，线程真正开始时回填线程。
     * <p>必须与「线程开始」解耦：线程池是异步的，若等到线程真正跑起来才登记，
     * 登记与「本轮是否已结束」的判断之间会有空窗，调度器可能误判任务已全部结束。
     */
    private volatile TaskRun startRun;

    /** 执行槽（只限本实例；重载后是新对象，跨实例靠代际令牌在检查点退出）：抢不到即放弃，不用实例锁 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private static final int MODEL_COUNT = Math.max(getModelArray().length, 1);

    /**
     * 是否正在「派发一轮任务」。
     * <p>一轮任务里模型是逐个起跑的（每个模型之间有 750ms 间隔），
     * 前一个模型刚结束、后一个还没起跑的那一小段时间里，线程表也是空的。
     * 若只看线程表，切号会在这段空窗里误判「本轮已跑完」，
     * 于是把后面还没跑的模型连同这一轮一起丢掉，账号就只剩零星几个模型在跑。
     * 派发期间一律视为「本轮未完成」。
     */
    private static volatile boolean roundDispatching = false;

    /**
     * 主任务线程池：每个模型占一条线程（core = 模型数），上限 2 倍模型数，饱和后回退到调用线程执行。
     * <p>SynchronousQueue 不排队，故不存在「撤销已提交但尚未开始的那一轮」这种操作。
     */
    private static final ThreadPoolExecutor MAIN_THREAD_POOL = new ThreadPoolExecutor(MODEL_COUNT, MODEL_COUNT * 2, 30L, TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());

    private final Map<String, ChildModelTask> childTaskMap = new ConcurrentHashMap<>();

    private ChildTaskExecutor childTaskExecutor;

    @Getter
    private final Runnable mainRunnable = new Runnable() {

        private final ModelTask task = ModelTask.this;

        @Override
        public void run() {
            // 执行槽已由 startTask 抢住；此处只取快照，stopTask 递增后本代作废
            TaskRun run = task.startRun;
            long myGen = run == null ? task.generation : run.generation;
            run.thread = Thread.currentThread();
            run.started = true;
            // 绑定本代令牌：本线程的 sleep / RPC 入口据此感知作废
            RunGeneration prevGen = RunGeneration.bind(myGen, () -> task.generation);
            // 绑定本线程的账号简称：切号后全局简称会变成新账号，
            // 这里固定成本线程所属账号，避免旧账号的收尾日志被算到新账号头上
            Log.bindThreadAccountLabel(Log.getAccountLabel());
            // 静音计数分层：记下上层残留，本轮只统计自己这段
            int droppedPrev = Log.takeDroppedStaleLogCount();
            // 与执行槽配对：只有真正拿到槽的线程才计入 runningCount；同时驱动状态栏显示当前在跑的模块
            NotificationUtil.trackTaskStart(task.getName());
            ModelGroup group = task.getGroup();
            String prevModule = Log.beginModule(group == null ? null : group.getCode());
            Log.record("执行开始-" + task.getName());
            Log.startModuleLogCount();
            Log.resetUnresolved();
            try {
                task.run();
            } catch (TaskCancelledException e) {
                Log.record(task.getName() + "⏹代际已作废，本轮提前结束");
            } catch (Exception e) {
                Log.printStackTrace(e);
            } finally {
                // 作废期间被静音的错误日志条数一并报告，避免真故障无声丢失
                int dropped = Log.takeDroppedStaleLogCount();
                if (dropped > 0) {
                    Log.record(task.getName() + "⏹代际已作废，其间静音错误日志 " + dropped + " 条");
                }
                Log.restoreDroppedStaleLogCount(droppedPrev);
                // 还原而非清空：isSync 会在同一线程嵌套起跑，清掉会抹掉外层令牌
                RunGeneration.restore(prevGen);
                // 模块收尾汇总：按「是否还有未处理任务」与「本轮是否有动作」给状态
                Log.moduleSummary(task.getName()
                        + (Log.isUnresolved() ? "⏳仍有待完成或待领取任务"
                        : (Log.stopModuleLogCount() == 0 ? "✅本轮无操作" : "✅本轮完成")));
                Log.record("执行结束-" + task.getName());
                Log.endModule(prevModule);
                // 身份化移除：只删本代登记的那条，避免旧代收尾误删新一代条目
                MAIN_TASK_MAP.remove(task, run);
                task.running.set(false);
                NotificationUtil.trackTaskEnd(task.getName());
                Log.unbindThreadAccountLabel();
            }
        }

    };

    public ModelTask() {
    }

    @Override
    public final void prepare() {
        childTaskExecutor = newTimedTaskExecutor();
    }

    public String getId() {
        return toString();
    }

    public ModelType getType() {
        return ModelType.TASK;
    }

    public abstract String getName();

    public abstract ModelFields getFields();

    public abstract Boolean check();

    public Boolean isSync() {
        return false;
    }

    public abstract void run();

    public Boolean hasChildTask(String childId) {
        return childTaskMap.containsKey(childId);
    }

    public ChildModelTask getChildTask(String childId) {
        return childTaskMap.get(childId);
    }

    public Boolean addChildTask(ChildModelTask childTask) {
        String childId = childTask.getId();
        childTask.modelTask = this;
        // 执行器可能被 stopTask 清空过（clearAllChildTask 会关掉线程池），
        // 此时重新建一个，否则子任务永远提交不进去（表现为「定时任务再也不执行」）
        ChildTaskExecutor executor = childTaskExecutor;
        if (executor == null || !executor.isUsable()) {
            executor = newTimedTaskExecutor();
            childTaskExecutor = executor;
        }
        // 提交放锁外：池满时 CallerRunsPolicy 会让提交线程就地跑完整段子任务，放进 compute 会卡住整桶
        if (!executor.addChildTask(childTask)) {
            return false;
        }
        ChildModelTask oldTask = childTaskMap.put(childId, childTask);
        if (oldTask != null) {
            oldTask.cancel();
        }
        return true;
    }

    public void removeChildTask(String childId) {
        ChildModelTask childTask = childTaskMap.remove(childId);
        if (childTask != null) {
            ChildTaskExecutor executor = childTaskExecutor;
            if (executor != null) {
                // 通知执行器放锁外，别在 CHM 桶锁内调用可能阻塞的代码
                executor.removeChildTask(childTask);
            }
        }
    }

    public Integer countChildTask() {
        return childTaskMap.size();
    }

    public Boolean startTask() {
        return startTask(false);
    }

    /**
     * 启动一轮。不加 {@code synchronized}：互斥由 {@code running} 执行槽（CAS）保证，被挡住的只是执行
     * 线程自己，不像实例锁那样把整轮时间压给 {@link #stopTask()} 的调用方（宿主 onDestroy 在主线程）。
     * <p>抢槽在本方法内完成，故返回值如实反映「是否真的开始跑」：抢不到返回 false。
     */
    public Boolean startTask(Boolean force) {
        if (force) {
            stopTask();
        }
        try {
            if (!isEnable() || !check()) {
                return false;
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
            return false;
        }
        if (!running.compareAndSet(false, true)) {
            Log.record(getName() + "⏭本实例上一代未退出，本轮跳过");
            return false;
        }
        // 同步登记：线程池是异步的，若等到线程真正跑起来才登记，
        // 「本轮是否已结束」会在这段空窗里误判为已结束（切号就会切在半路上）
        TaskRun run = new TaskRun(generation);
        startRun = run;
        MAIN_TASK_MAP.put(this, run);
        try {
            if (isSync()) {
                mainRunnable.run();
            } else {
                try {
                    MAIN_THREAD_POOL.execute(mainRunnable);
                } catch (Throwable t) {
                    // 没能提交就要归还槽，否则该模型再也起不来
                    running.set(false);
                    MAIN_TASK_MAP.remove(this, run);
                    throw t;
                }
            }
            return true;
        } catch (Exception e) {
            Log.printStackTrace(e);
            running.set(false);
            MAIN_TASK_MAP.remove(this, run);
            return false;
        }
    }

    public synchronized void stopTask() {
        // 递增代际：旧代在下一个检查点自行退出，无需 join 阻塞调用方。
        // 令牌只覆盖模型主线程；子任务线程未绑定，其取消仍依赖 cancel() 与 clearAllChildTask()
        generation++;
        for (ChildModelTask childModelTask : childTaskMap.values()) {
            try {
                childModelTask.cancel();
            } catch (Exception e) {
                Log.printStackTrace(e);
            }
        }
        if (childTaskExecutor != null) {
            childTaskExecutor.clearAllChildTask();
        }
        childTaskMap.clear();
        // 这里刻意**不**把本实例从 MAIN_TASK_MAP 里摘掉：
        // 递增代际只是「通知」，旧线程要到下一个检查点才真正退出，
        // 提前摘掉会让 isAllTaskFinished() 误判为「任务已全部结束」，
        // 切号就会在旧账号任务还在跑的时候切走 —— 表现就是两个账号同时在处理任务。
        // 真正退出时由线程自己 remove（按代际身份化移除）。
    }

    public static void startAllTask() {
        startAllTask(false);
    }

    public static void startAllTask(Boolean force) {
        // 整轮开始：清零本轮收取能量。单分组执行（startGroupTask）不算整轮，故不在此清零
        NotificationUtil.startRound();
        // 自动触发备份配置文件
        if (!Status.hasFlagToday("Config::backup")) {
            FileUtil.backupConfigV2WithRolling(UserIdMap.getCurrentUid());
            Status.flagToday("Config::backup");
        }
        // 执行BaseModel中自定义执行请求
        taskRpcRequest();
        // 本轮开始：派发期间一律视为「本轮未完成」，直到最后一个模型起跑完
        roundDispatching = true;
        try {
            for (Model model : getModelArray()) {
                // 已停止就不再启动后续模型，否则 MAIN_TASK 会把整轮跑完
                if (RunGeneration.isStale()) {
                    Log.record("⏹已停止，本轮不再启动后续任务");
                    return;
                }
                if (model != null) {
                    if (ModelType.TASK == model.getType()) {
                        if (((ModelTask) model).startTask(force)) {
                            // 带代际检查的睡眠：停止时在下一个边界结束整轮（未绑定时同 Thread.sleep）
                            try {
                                TimeUtil.sleep(750);
                            } catch (TaskCancelledException e) {
                                return;
                            }
                        }
                    }
                }
            }
        } finally {
            roundDispatching = false;
        }
    }

    /**
     * 本模块的可执行功能单元（「执行任务」弹窗的列表行）。
     * <p>默认整模块为一个单元（key 取类名）；有多个独立功能的模块覆盖此方法逐个列出。
     * UI 进程也会调用（弹窗列数据），实现不得依赖 libxposed 类。
     */
    public List<ExecUnit> getExecUnits() {
        return Collections.singletonList(new ExecUnit(getClass().getSimpleName(), getName(), ""));
    }

    /**
     * 只执行指定分组下的任务（{@code groupCode} 取 {@link ModelGroup#getCode()}）。
     * <p>配置页的"执行"按钮必须通过广播交给<strong>注入进程</strong>跑：模块 App 自己的进程没有
     * libxposed 类（{@code ApplicationHook}/{@code hook.Toast}/{@code NotificationUtil} 一碰就是
     * NoClassDefFoundError），而且那是个 UI 进程，把任务循环压在主线程上会直接卡死界面。
     *
     * @return 实际触发的任务数
     */
    public static int startGroupTask(String groupCode) {
        List<Model> targets = new ArrayList<>();
        for (Model model : getModelArray()) {
            if (model == null || ModelType.TASK != model.getType()) {
                continue;
            }
            ModelGroup group = model.getGroup();
            if (group == null || !groupCode.equals(group.getCode())) {
                continue;
            }
            targets.add(model);
        }
        if (targets.isEmpty()) {
            return 0;
        }
        // 手动执行分组也算新一轮：先清零本轮收取能量，再起跑
        NotificationUtil.startRound();
        int count = 0;
        for (Model model : targets) {
            if (((ModelTask) model).startTask(false)) {
                count++;
            }
        }
        return count;
    }

    public static void stopAllTask() {
        for (Model model : getModelArray()) {
            if (model != null) {
                try {
                    if (ModelType.TASK == model.getType()) {
                        ((ModelTask) model).stopTask();
                    }
                } catch (Exception e) {
                    Log.printStackTrace(e);
                }
            }
        }
    }

    /**
     * 本轮任务是否已全部结束（不含未来到点的定时子任务）。
     * <p>自动切号用它判断"当前账号跑完了没有"：模型线程都退出、且没有仍在等待触发的子任务，才允许切号。
     */
    public static boolean isAllTaskFinished() {
        // 正在派发新一轮：还有模型没起跑，此时线程表为空也不代表本轮跑完
        if (roundDispatching) {
            return false;
        }
        if (!MAIN_TASK_MAP.isEmpty()) {
            // 线程已死却还挂在表里（收尾异常）：按已结束处理，避免永久卡住切号
            for (Map.Entry<ModelTask, TaskRun> entry : MAIN_TASK_MAP.entrySet()) {
                if (isStillRunning(entry.getValue())) {
                    return false;
                }
            }
            MAIN_TASK_MAP.clear();
        }
        long now = System.currentTimeMillis();
        for (Model model : getModelArray()) {
            if (model == null || ModelType.TASK != model.getType()) {
                continue;
            }
            ModelTask task = (ModelTask) model;
            for (ChildModelTask childTask : task.childTaskMap.values()) {
                if (childTask == null || Boolean.TRUE.equals(childTask.getIsCancel())) {
                    continue;
                }
                Long execTime = childTask.getExecTime();
                if (execTime == null || execTime <= 0 || execTime <= now) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 仍在运行的模型任务数量（含正在收尾的旧代线程）。
     * <p>切号时用它判断"旧账号的线程真的退出了没有"：{@link #isAllTaskFinished()}
     * 只能反映瞬时状态，线程退出是异步的，需要轮询确认。
     */
    public static int getRunningTaskCount() {
        return MAIN_TASK_MAP.size();
    }

    /**
     * 某次模型运行是否仍在进行。
     * <p>线程还没起来（线程池排队中）也算在进行中——那种情况很快就会起来。
     * 线程起来过又已经死掉、却没把自己从表里摘掉（收尾异常）算已结束，
     * 否则一次异常就能把切号永久卡死。
     */
    private static boolean isStillRunning(TaskRun run) {
        if (run == null) {
            return false;
        }
        Thread thread = run.thread;
        if (thread == null) {
            // 还没起来：要么正在排队，要么提交后立刻被取消
            return !run.started;
        }
        return thread.isAlive();
    }

    /**
     * 等待所有模型线程真正退出（最多等 {@code timeoutMillis}）。
     * <p>切号前必须调用：{@link #stopTask()} 只是递增代际通知旧线程退出，
     * 旧线程要到下一个检查点才真正结束。不等它退出会出现「旧账号的收尾动作」
     * 与「新账号的任务」重叠——表现就是两个账号同时在处理同一个任务。
     *
     * @return 是否在超时内全部退出
     */
    public static boolean waitForAllTaskStopped(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            boolean allStopped = true;
            for (Map.Entry<ModelTask, TaskRun> entry : MAIN_TASK_MAP.entrySet()) {
                if (isStillRunning(entry.getValue())) {
                    allStopped = false;
                    break;
                }
            }
            if (allStopped) {
                MAIN_TASK_MAP.clear();
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private ChildTaskExecutor newTimedTaskExecutor() {
        ChildTaskExecutor childTaskExecutor;
        Integer timedTaskModel = BaseModel.getTimedTaskModel().getValue();
        if (timedTaskModel == BaseModel.TimedTaskModel.SYSTEM) {
            childTaskExecutor = new SystemChildTaskExecutor();
        } else if (timedTaskModel == BaseModel.TimedTaskModel.PROGRAM) {
            childTaskExecutor = new ProgramChildTaskExecutor();
        } else {
            throw new RuntimeException("not found childTaskExecutor");
        }
        return childTaskExecutor;
    }

    public static class ChildModelTask implements Runnable {

        @Getter
        private ModelTask modelTask;

        @Getter
        private final String id;

        @Getter
        private final String group;

        private final Runnable runnable;

        @Getter
        private final Long execTime;

        private CancelTask cancelTask;

        @Getter
        private Boolean isCancel = false;

        public ChildModelTask() {
            this(null, null, () -> {
            }, 0L);
        }

        public ChildModelTask(String id) {
            this(id, null, () -> {
            }, 0L);
        }

        public ChildModelTask(String id, String group) {
            this(id, group, () -> {
            }, 0L);
        }

        protected ChildModelTask(String id, long execTime) {
            this(id, null, null, execTime);
        }

        /*protected ChildModelTask(String id, String group, Long time) {
            this(id, group, null, time);
        }*/

        public ChildModelTask(String id, Runnable runnable) {
            this(id, null, runnable, 0L);
        }

        public ChildModelTask(String id, String group, Runnable runnable) {
            this(id, group, runnable, 0L);
        }

        public ChildModelTask(String id, String group, Runnable runnable, Long execTime) {
            if (StringUtil.isEmpty(id)) {
                id = toString();
            }
            if (StringUtil.isEmpty(group)) {
                group = "DEFAULT";
            }
            if (runnable == null) {
                runnable = setRunnable();
            }
            this.id = id;
            this.group = group;
            this.runnable = runnable;
            this.execTime = execTime;
        }

        public Runnable setRunnable() {
            return null;
        }

        public final void run() {
            // 子任务线程继承所属模块：这些流程日志也带模块 tag（嵌套时恢复上层）
            ModelTask owner = modelTask;
            ModelGroup group = owner == null ? null : owner.getGroup();
            String prevModule = Log.beginModule(group == null ? null : group.getCode());
            try {
                runnable.run();
            } finally {
                Log.endModule(prevModule);
            }
        }

        protected void setCancelTask(CancelTask cancelTask) {
            this.cancelTask = cancelTask;
        }

        /**
         * 叠加一个取消动作，而不是覆盖已有的。
         * <p>子任务的取消往往需要同时做两件事（如：撤掉延迟投递 + 取消线程池里的 future），
         * 覆盖式赋值会丢掉前一个，导致切号后已投递的子任务仍会醒来执行。
         */
        protected void appendCancelTask(CancelTask extra) {
            CancelTask previous = cancelTask;
            cancelTask = () -> {
                if (previous != null) {
                    previous.cancel();
                }
                extra.cancel();
            };
        }

        public final void cancel() {
            if (cancelTask != null) {
                try {
                    cancelTask.cancel();
                    isCancel = true;
                } catch (Exception e) {
                    Log.printStackTrace(e);
                }
            }
        }

    }

    public interface CancelTask {

        void cancel();

    }

}
