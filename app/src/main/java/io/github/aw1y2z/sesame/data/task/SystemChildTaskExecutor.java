package io.github.aw1y2z.sesame.data.task;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import io.github.aw1y2z.sesame.hook.ApplicationHook;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.ThreadUtil;

import java.util.Map;
import java.util.concurrent.*;

public class SystemChildTaskExecutor implements ChildTaskExecutor {

    private final Handler handler;

    private final Map<String, ThreadPoolExecutor> groupChildTaskExecutorMap = new ConcurrentHashMap<>();

    /** 每组允许同时运行的最大子任务线程数（原先上限是 Integer.MAX_VALUE，等于无限起线程） */
    private static final int MAX_CHILD_TASK_THREADS = 16;

    public SystemChildTaskExecutor() {
        // mainHandler 仅在 Service onCreate hook 内赋值，构造期可能还没就绪；
        // Handler 只绑定 Looper，主 Looper 在 App 进程内必然存在，故兜底现建一个，
        // 避免把 null 固定进字段后延迟子任务被静默丢弃
        Handler initHandler = ApplicationHook.getMainHandler();
        handler = initHandler != null ? initHandler : new Handler(Looper.getMainLooper());
    }

    @Override
    public Boolean addChildTask(ModelTask.ChildModelTask childTask) {
        ThreadPoolExecutor threadPoolExecutor = getChildGroupHandler(childTask.getGroup());
        long execTime = childTask.getExecTime();
        if (execTime > 0) {
            Runnable runnable = () -> {
                if (childTask.getIsCancel()) {
                    return;
                }
                //String modelTaskId = getName();
                Future<?> future = threadPoolExecutor.submit(() -> {
                    try {
                        long delay = childTask.getExecTime() - System.currentTimeMillis();
                        if (delay > 0) {
                            try {
                                Thread.sleep(delay);
                            } catch (Exception e) {
                                return;
                            }
                        }
                        childTask.run();
                    } catch (Exception e) {
                        Log.printStackTrace(e);
                    } finally {
                        childTask.getModelTask().removeChildTask(childTask.getId());
                    }
                });
                // 取消动作要「叠加」而不是「覆盖」：
                // 延迟投递的 runnable 已经触发时，removeCallbacks 无效，
                // 只有取消线程池里的 future 才真正停得住。
                // 原写法第二次 setCancelTask 会把前一个覆盖掉，
                // 于是「已投递到线程池、正在 sleep 等 execTime」的子任务
                // 在切号后仍会醒来执行 —— 表现为上一个账号的定时任务跑到新账号上。
                childTask.appendCancelTask(() -> future.cancel(true));
            };
            long delayMillis = execTime - System.currentTimeMillis();
            if (delayMillis > 3000) {
                handler.postDelayed(runnable, delayMillis - 2500);
                childTask.appendCancelTask(() -> handler.removeCallbacks(runnable));
            } else {
                childTask.appendCancelTask(() -> handler.removeCallbacks(runnable));
                handler.post(runnable);
            }
        } else {
            Future<?> future = threadPoolExecutor.submit(() -> {
                try {
                    childTask.run();
                } catch (Exception e) {
                    Log.printStackTrace(e);
                } finally {
                    childTask.getModelTask().removeChildTask(childTask.getId());
                }
            });
            childTask.appendCancelTask(() -> future.cancel(true));
        }
        return true;
    }

    @Override
    public Boolean removeChildTask(ModelTask.ChildModelTask childTask) {
        childTask.cancel();
        return true;
    }

    @Override
    public Boolean clearGroupChildTask(String group) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            groupChildTaskExecutorMap.compute(group, (keyInner, valueInner) -> {
                if (valueInner != null) {
                    ThreadUtil.shutdownAndAwaitTermination(valueInner, 3, TimeUnit.SECONDS);
                }
                return null;
            });
        } else {
            synchronized (groupChildTaskExecutorMap) {
                ThreadPoolExecutor threadPoolExecutor = groupChildTaskExecutorMap.get(group);
                if (threadPoolExecutor != null) {
                    ThreadUtil.shutdownAndAwaitTermination(threadPoolExecutor, 3, TimeUnit.SECONDS);
                    groupChildTaskExecutorMap.remove(group);
                }
            }
        }
        return true;
    }

    @Override
    public Boolean clearAllChildTask() {
        for (ThreadPoolExecutor threadPoolExecutor : groupChildTaskExecutorMap.values()) {
            ThreadUtil.shutdownNow(threadPoolExecutor);
        }
        groupChildTaskExecutorMap.clear();
        return true;
    }

    @Override
    public Boolean isUsable() {
        // 线程池被 clearAllChildTask 关掉后 map 会清空，视为不可用，需要重建
        return !groupChildTaskExecutorMap.isEmpty();
    }

    private ThreadPoolExecutor getChildGroupHandler(String group) {
        ThreadPoolExecutor threadPoolExecutor = groupChildTaskExecutorMap.get(group);
        if (threadPoolExecutor != null) {
            return threadPoolExecutor;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            threadPoolExecutor = groupChildTaskExecutorMap.compute(group, (keyInner, valueInner) -> {
                if (valueInner == null) {
                    // 子任务有时效性，必须立即开始，所以保留 SynchronousQueue（不排队）；
                    // 但上限不能是 Integer.MAX_VALUE——那等于"每个子任务新建一条线程"。
                    // 改为有限上限后，超出部分由 CallerRunsPolicy 在提交线程内执行（背压，仍然立即执行不排队）。
                    valueInner = new ThreadPoolExecutor(1, MAX_CHILD_TASK_THREADS, 30L, TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
                }
                return valueInner;
            });
        } else {
            synchronized (groupChildTaskExecutorMap) {
                threadPoolExecutor = groupChildTaskExecutorMap.get(group);
                if (threadPoolExecutor == null) {
                    threadPoolExecutor = new ThreadPoolExecutor(1, MAX_CHILD_TASK_THREADS, 30L, TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
                    groupChildTaskExecutorMap.put(group, threadPoolExecutor);
                }
            }
        }
        return threadPoolExecutor;
    }

}
