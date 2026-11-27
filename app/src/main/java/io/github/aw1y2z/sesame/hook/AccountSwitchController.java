package io.github.aw1y2z.sesame.hook;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.aw1y2z.sesame.data.AccountSwitchConfig;
import io.github.aw1y2z.sesame.data.AccountSwitchState;
import io.github.aw1y2z.sesame.data.task.ModelTask;
import io.github.aw1y2z.sesame.hook.accountswitch.SwitchPlanner;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.RunGeneration;
import io.github.aw1y2z.sesame.util.StringUtil;
import io.github.aw1y2z.sesame.util.TimeUtil;

/**
 * 自动切换登录账号调度器（状态机实现）。
 *
 * 设计要点（与早期实现的差异）：
 *   1. 规划与执行分离：切号顺序、目标选择由纯逻辑 {@link SwitchPlanner} 决定；
 *      本类只负责状态机、时序与宿主交互。
 *   2. 运行时状态落盘（{@link AccountSwitchState}）：轮次等待起点、定时触发记录、
 *      失败计数、账号隔离、全局熔断都跨进程保存，宿主重启后按事实恢复。
 *   3. 登录态只有一个事实来源：支付宝账号服务实时读到的 uid。模块内存里的
 *      「当前账号」只是缓存；看门狗每 15 秒对账一次，不一致就按实际账号重载，
 *      切号迟到落地、人工切号都不会造成串号运行。
 *   4. 防串号靠账号纪元：切号开始时推进 {@link RunGeneration} 的全局纪元，
 *      旧账号的任务线程在下一个检查点（sleep/RPC 入口）自动作废，
 *      不再只依赖「等旧线程退出」的时间窗口。
 *   5. 失败分类处理：桥层把切号结果分成已受理/需手动登录/被拒/方法缺失/异常，
 *      需手动登录的账号被隔离（等人工登录后自动解除），单账号连续失败 3 次隔离 1 小时，
 *      全局连续失败 5 次熔断 2 小时；普通失败指数退避，不再每 60 秒无限重试。
 *   6. 切号确认超时不等于失败：进入「未知态」，任务保持暂停并持续对账，
 *      落地到目标就继续加载，落到别处就按实际账号恢复，最长 5 分钟。
 *
 * 切号顺序与轮次语义（用户配置层面的约定，不变）：
 *   按 config_common.json 的账号列表顺序循环；常态保持账号挪到一轮末尾收尾，
 *   轮次等待从切入它那一刻起算，期间它继续按自己的执行间隔跑任务但不切号；
 *   定时切号到点后立即终止当前任务，20 秒后切到常态保持账号执行一轮。
 */
public final class AccountSwitchController {

    public interface Host {
        String currentUid();

        boolean isReadyForSwitch();

        /** 初始化指定账号；startMain=false 时只加载配置，不启动任务 */
        boolean initialize(String uid, boolean startMain);

        void pause();

        void resume();
    }

    /** 调度阶段。阶段迁移只发生在 tick 与切换流程里，onAccountRunStarted 不迁移阶段 */
    private enum Phase {
        /** 正常运行，监控任务完成情况 */
        RUNNING,
        /** 一次切换流程正在执行（静默/请求/确认/加载） */
        SWITCHING,
        /** 切号请求已受理但确认超时，登录态未知，持续对账中 */
        CONFIRMING,
        /** 坐在常态保持账号上等轮次间隔结束 */
        ROUND_WAIT
    }

    private static final String TAG = "AccountSwitch";

    /** 任务刚结束时留一点缓冲，避免任务链切换间隙误判为全部完成 */
    private static final long IDLE_GRACE_MILLIS = 5_000L;
    private static final long SWITCH_POLL_INTERVAL_MILLIS = 500L;
    /** 切号确认等待：支付宝风控/网络慢时落地可能超过 20 秒，给到 120 秒 */
    private static final long CONFIRM_TIMEOUT_MILLIS = 120_000L;
    /** 确认超时后未知态的最长对账时间，之后按实际账号恢复 */
    private static final long CONFIRM_UNKNOWN_MILLIS = 5 * 60_000L;
    /** 普通失败的基础重试间隔（指数退避的起点） */
    private static final long FAILED_RETRY_MILLIS = 60_000L;
    /** 指数退避上限 */
    private static final long BACKOFF_CAP_MILLIS = 30 * 60_000L;
    /** 单账号连续失败达到该次数后隔离 */
    private static final int ACCOUNT_FAILURE_BUDGET = 3;
    /** 隔离时长：普通连续失败 */
    private static final long QUARANTINE_REPEATED_MILLIS = 60 * 60_000L;
    /** 隔离时长：需要手动登录（实际由看门狗在检测到手动登录后提前解除） */
    private static final long QUARANTINE_MANUAL_MILLIS = 30L * 24 * 60 * 60_000L;
    /** 全局连续失败达到该次数后熔断 */
    private static final int GLOBAL_FAILURE_BUDGET = 5;
    /** 全局熔断时长（方法缺失也用这个时长） */
    private static final long GLOBAL_DISABLE_MILLIS = 2 * 60 * 60_000L;
    /** 看门狗对账间隔 */
    private static final long WATCHDOG_INTERVAL_MILLIS = 15_000L;
    /** 任务长时间未结束时输出卡点诊断的阈值 */
    private static final long STUCK_DIAGNOSE_MILLIS = 60_000L;
    /** 轮次等待期间输出等待状态的间隔 */
    private static final long ROUND_WAIT_LOG_INTERVAL_MILLIS = 30 * 60_000L;
    /** 账号列表不足时补充读取支付宝账号的最小间隔 */
    private static final long ACCOUNT_SYNC_INTERVAL_MILLIS = 30_000L;
    /** 账号列表够用时的定期复核间隔：新登录的账号会自动追加到列表末尾 */
    private static final long ACCOUNT_RESYNC_INTERVAL_MILLIS = 30 * 60_000L;
    /** 启动后延迟读取支付宝账号，等宿主框架就绪，避免和宿主启动流程抢服务 */
    private static final long STARTUP_SYNC_DELAY_MILLIS = 10_000L;
    /** 进程启动后的冷却时间，期间不切号，避免干扰支付宝自身的启动/登录流程 */
    private static final long STARTUP_SETTLE_MILLIS = 60_000L;
    /** 两次切号之间的最小间隔，避免任务很快结束时连续调用宿主登录接口 */
    private static final long MIN_SWITCH_GAP_MILLIS = 60_000L;
    /**
     * 切号前等待旧账号任务真正退出的超时。
     * <p>退出是协作式的（检查点作废），等不到也不硬卡：账号纪元已推进，
     * 旧线程即使还活着也发不出 RPC。
     */
    private static final long WAIT_TASK_STOPPED_MILLIS = 30_000L;
    /** 定时切号：终止当前任务后到切换之间的固定间隔（20 秒） */
    private static final long SCHEDULE_SWITCH_DELAY_MILLIS = 20_000L;
    /** 定时切号失败后的最大重试次数（每次间隔 FAILED_RETRY_MILLIS） */
    private static final int SCHEDULED_MAX_RETRY = 3;
    /** 定时点在停机期间错过后的最长补触发窗口，超过则跳过该时间点 */
    private static final long SCHEDULE_CATCHUP_MILLIS = 10 * 60_000L;
    /** 定时检查基准落盘的最短间隔（触发时立即落盘，平时节流） */
    private static final long STATE_PERSIST_INTERVAL_MILLIS = 60_000L;

    private static final Object LOCK = new Object();

    private static volatile Host host;
    private static volatile Phase phase = Phase.RUNNING;
    /** 任务是否被保持停止（切号中/未知态）；ApplicationHook 据此决定是否启动新一轮任务 */
    private static volatile boolean tasksHeld;
    /** 是否有切换流程正在执行（流程串行化，同一时刻最多一个） */
    private static volatile boolean flowActive;
    /** 演练模式：已为当前真实任务轮次出过计划，等下一轮任务开始后再继续推演 */
    private static volatile boolean dryRunAwaitingRun;
    private static volatile long runStartTime;
    private static volatile long tasksStartedAt;
    private static volatile long idleSince;
    private static volatile long nextAttemptAt;
    /** 本账号是否已经输出过「等待切号间隔」的提示，避免重复刷日志 */
    private static volatile boolean intervalWaitLogged;
    /** 上一次输出轮次等待状态的时间 */
    private static volatile long roundWaitLastLogAt;
    /** 轮次等待结束时刻 */
    private static volatile long resumeAt;
    /** 轮次等待结束后计划切入的账号（仅用于日志，真正目标由流程按最新配置重算） */
    private static volatile String pendingTargetUid;
    private static volatile long lastAccountSync;
    private static volatile long lastSwitchEndAt;
    private static volatile long processStartAt;
    /** 有待执行的定时切号（已触发、未执行） */
    private static volatile boolean scheduledPending;
    /** 定时切号计划执行时刻 */
    private static volatile long scheduledFireAt;
    /** 定时切号在切换流程进行中触发：流程在步骤边界让路 */
    private static volatile boolean scheduledPreempt;
    /** 定时切号失败后的重试计数，成功后清零 */
    private static volatile int scheduledRetryCount;
    // 未知态对账信息（CONFIRMING 阶段）
    private static volatile String confirmFromUid;
    private static volatile String confirmTargetUid;
    private static volatile boolean confirmArrivingHold;
    private static volatile long confirmDeadline;
    private static volatile String lastConfirmActual;
    private static volatile boolean confirmHandling;
    // 看门狗与诊断
    private static volatile long lastWatchdogAt;
    private static volatile long unfinishedSince;
    private static volatile String lastDiagnosedReason;
    private static volatile long lastStatePersistAt;
    private static volatile boolean disabledBaselineSynced;

    private static final AtomicBoolean syncRequested = new AtomicBoolean();

    private static ScheduledExecutorService scheduler;
    private static ExecutorService switchExecutor;
    private static ExecutorService syncExecutor;

    private AccountSwitchController() {
    }

    private static AccountSwitchState state() {
        return AccountSwitchState.get();
    }

    /** 发布状态给状态卡片（宿主进程写、UI 进程读同一份落盘状态）；失败不影响调度 */
    private static void publishStatus(Phase p, String uid, String nextUid, String event) {
        try {
            state().publishStatus(p == null ? null : p.name(), uid, nextUid, event);
        } catch (Throwable ignored) {
            // 状态发布失败不影响切号本身
        }
    }

    /** 心跳：刷新状态时间戳与当前账号，让状态卡片能判断调度器是否还活着 */
    private static void publishHeartbeat(String uid) {
        try {
            state().heartbeat(phase.name(), uid);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    public static void configure(Host newHost) {
        synchronized (LOCK) {
            host = newHost;
            if (processStartAt <= 0) {
                processStartAt = System.currentTimeMillis();
            }
            if (scheduler == null) {
                scheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());
                scheduler.scheduleWithFixedDelay(AccountSwitchController::tick,
                        0, 1, TimeUnit.SECONDS);
            }
            if (switchExecutor == null) {
                switchExecutor = Executors.newSingleThreadExecutor(new DaemonThreadFactory());
            }
            if (syncExecutor == null) {
                syncExecutor = Executors.newSingleThreadExecutor(new DaemonThreadFactory());
            }
        }
    }

    /** 主任务被暂停（切号中/未知态）时，ApplicationHook 不应执行新一轮任务 */
    public static boolean isPaused() {
        return tasksHeld;
    }

    /**
     * 当前账号开始运行：只记录本轮运行的计时信息，不迁移阶段、不清退避——
     * 阶段迁移由 tick 与切换流程负责，失败退避由落盘状态负责，
     * 避免 resume()/重载把退避与轮次等待误清掉。
     */
    public static void onAccountRunStarted(String uid) {
        runStartTime = System.currentTimeMillis();
        tasksStartedAt = 0;
        idleSince = 0;
        intervalWaitLogged = false;
        unfinishedSince = 0;
        lastDiagnosedReason = null;
        // 新一轮真实任务开始：演练推演可以继续出下一份计划
        dryRunAwaitingRun = false;
        // 演练模式下轮次等待是虚拟的：真实账号必然与虚拟保持账号不同，不能据此取消等待
        if (phase == Phase.ROUND_WAIT && !StringUtil.isEmpty(uid)
                && !uid.equals(AccountSwitchConfig.getAccountSwitchFinalAccountUid())
                && !AccountSwitchConfig.isAccountSwitchDryRun()) {
            // 轮次等待期间实际跑起来的不是保持账号（人工切号/对账纠正），等待作废
            phase = Phase.RUNNING;
            resumeAt = 0;
            pendingTargetUid = null;
            Log.accountSwitch("自动切号🔄️ 轮次等待期间账号已变更，取消轮次等待");
        }
        publishHeartbeat(uid);
        Log.accountSwitch("自动切号🔄️ 账号开始运行: " + label(uid));
    }

    /** 一轮任务已下发，允许开始判断任务是否全部结束 */
    public static void onTasksStarted() {
        tasksStartedAt = System.currentTimeMillis();
        idleSince = 0;
        unfinishedSince = 0;
    }

    /**
     * 请求在后台线程同步支付宝登录账号。
     * 反射调用支付宝服务比较重，禁止在宿主主线程直接执行（会造成支付宝卡死）。
     */
    public static void requestAccountSync() {
        ExecutorService executor = syncExecutor;
        if (executor == null) {
            return;
        }
        if (!syncRequested.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    Thread.sleep(STARTUP_SYNC_DELAY_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    syncRequested.set(false);
                }
                syncAccountsFromHost();
            });
        } catch (Throwable t) {
            syncRequested.set(false);
            Log.printStackTrace(TAG + "#提交账号同步失败", t);
        }
    }

    /** 定期维护用的账号同步：走后台线程，不阻塞 tick */
    private static void submitAccountSync() {
        ExecutorService executor = syncExecutor;
        if (executor == null) {
            return;
        }
        if (!syncRequested.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    syncAccountsFromHost();
                } finally {
                    syncRequested.set(false);
                }
            });
        } catch (Throwable t) {
            syncRequested.set(false);
            Log.printStackTrace(TAG + "#提交账号同步失败", t);
        }
    }

    /** 模块启动时把支付宝登录账号合并到公共配置，已有顺序不变，新账号追加 */
    public static void syncAccountsFromHost() {
        lastAccountSync = System.currentTimeMillis();
        try {
            List<AccountSwitchConfig.Account> accounts =
                    AlipayAccountSwitchBridge.fetchAccountsForConfig();
            if (accounts.isEmpty()) {
                Log.accountSwitch("自动切号🔄️ 未读取到支付宝登录账号");
                return;
            }
            AccountSwitchConfig.mergeHostAccounts(accounts);
            Log.accountSwitch("自动切号🔄️ 账号列表已同步，共 "
                    + AccountSwitchConfig.getAccountSwitchAccounts().size() + " 个账号");
        } catch (Throwable t) {
            Log.printStackTrace(TAG + "#同步登录账号失败", t);
        }
    }

    // ------------------------------------------------------------------
    // tick：每秒推进状态机
    // ------------------------------------------------------------------

    private static void tick() {
        try {
            Host currentHost = host;
            if (currentHost == null) {
                return;
            }
            AccountSwitchConfig.Snapshot config = AccountSwitchConfig.read();
            long now = System.currentTimeMillis();
            if (!config.isAccountSwitchEnabled()) {
                handleDisabled(currentHost, now);
                return;
            }
            disabledBaselineSynced = false;
            // 定时切号优先于一切常规判断
            if (checkScheduleTrigger(config, now)) {
                onScheduleFired(currentHost, now);
            }
            // 未知态对账优先于新切换：登录态没落定之前不发起任何新动作
            if (phase == Phase.CONFIRMING) {
                resolveConfirming(currentHost, now);
                return;
            }
            if (flowActive) {
                return;
            }
            if (scheduledPending) {
                if (now >= scheduledFireAt) {
                    scheduledPending = false;
                    scheduledPreempt = false;
                    phase = Phase.SWITCHING;
                    if (!submitFlow(AccountSwitchController::flowScheduled)) {
                        phase = Phase.RUNNING;
                        nextAttemptAt = now + FAILED_RETRY_MILLIS;
                    }
                }
                return;
            }
            maintainAccountSync(config, now);
            if (phase == Phase.ROUND_WAIT) {
                tickRoundWait(currentHost, now);
                return;
            }
            // 看门狗对账：实际登录账号与已加载账号不一致时按实际账号纠正
            if (now - lastWatchdogAt >= WATCHDOG_INTERVAL_MILLIS) {
                lastWatchdogAt = now;
                if (watchdogReconcile(currentHost, now)) {
                    return;
                }
            }
            if (!currentHost.isReadyForSwitch()) {
                idleSince = 0;
                return;
            }
            // 进程刚启动时先让支付宝自己的启动/登录流程跑完
            if (processStartAt > 0 && now - processStartAt < STARTUP_SETTLE_MILLIS) {
                return;
            }
            if (runStartTime <= 0) {
                runStartTime = now;
                return;
            }
            if (tasksStartedAt <= 0) {
                return;
            }
            if (!ModelTask.isAllTaskFinished()) {
                idleSince = 0;
                intervalWaitLogged = false;
                if (unfinishedSince <= 0) {
                    unfinishedSince = now;
                } else if (now - unfinishedSince >= STUCK_DIAGNOSE_MILLIS) {
                    // 长时间等不到任务结束时把卡点打出来，同一种卡点只打一次
                    String reason = ModelTask.diagnoseUnfinished();
                    if (!StringUtil.isEmpty(reason) && !reason.equals(lastDiagnosedReason)) {
                        lastDiagnosedReason = reason;
                        Log.accountSwitch("自动切号🔄️ 任务长时间未结束，暂不切号：" + reason);
                    }
                }
                return;
            }
            unfinishedSince = 0;
            lastDiagnosedReason = null;
            if (idleSince <= 0) {
                idleSince = now;
            }
            if (now - idleSince < IDLE_GRACE_MILLIS) {
                return;
            }
            if (nextAttemptAt > now) {
                return;
            }
            if (state().getSwitchDisabledUntil() > now) {
                // 全局熔断中（失败预算耗尽或版本不支持），到点自动恢复
                return;
            }
            if (lastSwitchEndAt > 0 && now - lastSwitchEndAt < MIN_SWITCH_GAP_MILLIS) {
                return;
            }
            long intervalMillis = config.getAccountSwitchIntervalMinutes() * 60_000L;
            /*
             * 「切号等待间隔」从任务跑完那一刻（idleSince）起算，而不是从账号开始运行起算：
             * 语义是「这个账号的任务跑完，再等 N 分钟才切走」，与配置项说明一致。
             */
            long waitedMillis = now - idleSince;
            if (waitedMillis < intervalMillis) {
                if (!intervalWaitLogged) {
                    intervalWaitLogged = true;
                    long remainMinutes = (intervalMillis - waitedMillis + 59_999L) / 60_000L;
                    Log.accountSwitch("自动切号🔄️ 当前账号任务已全部完成，距离切号间隔还需 "
                            + remainMinutes + " 分钟，等待中");
                }
                return;
            }
            intervalWaitLogged = false;
            if (config.getAccountSwitchAccounts().size() < 2) {
                // 账号不足时只补全列表，不提交注定失败的切换（维护逻辑在前面已处理）
                return;
            }
            if (config.isAccountSwitchDryRun() && dryRunAwaitingRun) {
                // 演练模式：一轮真实任务只出一份计划，避免计划以最小间隔连环推进、
                // 把时间线推得比真实执行快得多；等当前账号下一轮任务开始后继续
                return;
            }
            submitRotate();
        } catch (Throwable t) {
            Log.printStackTrace(TAG + "#自动切号检查失败", t);
        }
    }

    /** 开关关闭时的收尾：解除一切等待与暂停，按实际账号恢复运行 */
    private static void handleDisabled(Host currentHost, long now) {
        if (!disabledBaselineSynced) {
            // 关闭期间不参与定时判定：把检查基准推到现在并落盘，重新开启后不补触发
            disabledBaselineSynced = true;
            AccountSwitchState st = state();
            st.setLastScheduleCheckAt(now);
            st.persist();
            try {
                st.publishStatus("", null, "", "自动切换已关闭");
            } catch (Throwable ignored) {
                // 状态发布失败不影响关闭流程
            }
        }
        scheduledPending = false;
        scheduledPreempt = false;
        scheduledFireAt = 0;
        if (flowActive) {
            // 流程进行中不硬打断：它会在步骤边界发现已关闭并自行释放；
            // 已发出切号请求的，落地后完成加载比半途丢下更安全
            return;
        }
        if (phase == Phase.CONFIRMING) {
            // 登录态在变动中被关掉：按实际账号恢复，别把任务永久停住
            phase = Phase.RUNNING;
            confirmTargetUid = null;
            confirmHandling = false;
            tasksHeld = false;
            currentHost.resume();
            return;
        }
        if (phase == Phase.ROUND_WAIT) {
            phase = Phase.RUNNING;
            resumeAt = 0;
            pendingTargetUid = null;
        }
        if (tasksHeld && !flowActive) {
            tasksHeld = false;
            currentHost.resume();
        }
        // flowActive 时不硬打断：流程会在步骤边界发现已关闭并自行释放
    }

    /** 账号列表维护：不足时补全，够用时定期复核（新登录账号追加到末尾） */
    private static void maintainAccountSync(AccountSwitchConfig.Snapshot config, long now) {
        if (config.getAccountSwitchAccounts().size() < 2) {
            if (processStartAt > 0 && now - processStartAt < STARTUP_SYNC_DELAY_MILLIS) {
                return;
            }
            if (now - lastAccountSync >= ACCOUNT_SYNC_INTERVAL_MILLIS) {
                lastAccountSync = now;
                submitAccountSync();
            }
            return;
        }
        if (now - lastAccountSync >= ACCOUNT_RESYNC_INTERVAL_MILLIS) {
            lastAccountSync = now;
            submitAccountSync();
        }
    }

    /** 轮次等待：保持账号继续跑自己的任务，时间到且任务跑完后提交轮换 */
    private static void tickRoundWait(Host currentHost, long now) {
        if (now - lastWatchdogAt >= WATCHDOG_INTERVAL_MILLIS) {
            lastWatchdogAt = now;
            watchdogReconcile(currentHost, now);
            if (phase != Phase.ROUND_WAIT) {
                return;
            }
        }
        if (now >= resumeAt) {
            if (!ModelTask.isAllTaskFinished()) {
                // 保持账号还在按自己的执行间隔跑任务，等这一轮跑完再切，避免中途打断
                resumeAt = now + SWITCH_POLL_INTERVAL_MILLIS;
                return;
            }
            resumeAt = 0;
            pendingTargetUid = null;
            phase = Phase.RUNNING;
            Log.accountSwitch("自动切号🔄️ 轮次等待结束，开始下一轮");
            submitRotate();
        } else if (now - roundWaitLastLogAt >= ROUND_WAIT_LOG_INTERVAL_MILLIS) {
            roundWaitLastLogAt = now;
            long remainMinutes = (resumeAt - now + 59_999L) / 60_000L;
            // 演练模式下等待的是虚拟保持账号，日志按虚拟游标报，避免和真实账号对不上
            String waitUid = currentHost.currentUid();
            if (AccountSwitchConfig.isAccountSwitchDryRun()
                    && !StringUtil.isEmpty(state().getDryRunVirtualUid())) {
                waitUid = state().getDryRunVirtualUid();
            }
            Log.accountSwitch("自动切号🔄️ 轮次等待中，当前保持账号: "
                    + label(waitUid)
                    + "，预计还需 " + remainMinutes + " 分钟");
        }
    }

    /**
     * 看门狗对账：以支付宝实时登录 uid 为事实，纠正模块已加载账号。
     * 顺带处理「需手动登录」隔离的自动解除：某账号成为实际登录账号，
     * 说明用户已经手动登录过它了。
     *
     * @return 是否发生了纠正（调用方本 tick 不再做其它判断）
     */
    private static boolean watchdogReconcile(Host currentHost, long now) {
        String actual = AlipayAccountSwitchBridge.currentUid();
        if (StringUtil.isEmpty(actual)) {
            return false;
        }
        AccountSwitchState st = state();
        if (st.isQuarantined(actual, now)
                && AccountSwitchState.REASON_MANUAL_LOGIN.equals(st.getQuarantineReason(actual))) {
            st.clearQuarantine(actual);
            st.clearFailures(actual);
            st.persist();
            Log.accountSwitch("自动切号🔄️ 检测到账号 " + label(actual) + " 已手动登录，解除隔离");
        }
        publishHeartbeat(actual);
        String loaded = currentHost.currentUid();
        if (StringUtil.isEmpty(loaded) || actual.equals(loaded)) {
            return false;
        }
        Log.accountSwitch("自动切号🔄️ 看门狗：实际登录账号与已加载账号不一致（实际 "
                + label(actual) + "，已加载 " + label(loaded) + "），按实际账号重新加载");
        RunGeneration.advanceAccountEpoch();
        if (!currentHost.initialize(actual, true)) {
            // 宿主侧 uid 缓存可能还没跟上，下一轮对账再试
            Log.accountSwitch("自动切号🔄️ 看门狗：按实际账号加载未完成，等待下一轮对账");
        }
        publishStatus(Phase.RUNNING, actual, "", "看门狗按实际账号重载");
        return true;
    }

    // ------------------------------------------------------------------
    // 定时切号
    // ------------------------------------------------------------------

    /**
     * 判断是否有定时时间点落在（上次检查, 现在] 之间。
     * 检查基准与已触发记录都落盘：进程重启不重复触发；停机错过的时间点
     * 在 {@link #SCHEDULE_CATCHUP_MILLIS} 内补触发，超过则跳过。
     */
    private static boolean checkScheduleTrigger(AccountSwitchConfig.Snapshot config, long now) {
        AccountSwitchState st = state();
        long previous = st.getLastScheduleCheckAt();
        st.setLastScheduleCheckAt(now);
        long todayStart = TimeUtil.getToday().getTimeInMillis();
        st.pruneSchedulePoints(String.valueOf(todayStart));
        boolean fired = false;
        List<String> times = config.getAccountSwitchScheduleTimes();
        if (previous > 0 && now > previous && !times.isEmpty()) {
            for (String time : times) {
                Calendar calendar = TimeUtil.getCalendarByTimeStr(now, time);
                if (calendar == null) {
                    continue;
                }
                long scheduledAt = calendar.getTimeInMillis();
                if (scheduledAt <= previous || scheduledAt > now) {
                    continue;
                }
                String key = todayStart + "|" + time;
                if (st.hasFiredSchedulePoint(key)) {
                    continue;
                }
                st.markSchedulePointFired(key);
                if (now - scheduledAt <= SCHEDULE_CATCHUP_MILLIS) {
                    fired = true;
                } else {
                    Log.accountSwitch("自动切号🔄️ 定时点 " + time + " 已错过超过 "
                            + (SCHEDULE_CATCHUP_MILLIS / 60_000L) + " 分钟，跳过本次");
                }
            }
        }
        if (fired || now - lastStatePersistAt >= STATE_PERSIST_INTERVAL_MILLIS) {
            st.persist();
            lastStatePersistAt = now;
        }
        return fired;
    }

    /** 定时点到达：流程进行中就让它在步骤边界让路，否则立即停任务、20 秒后执行 */
    private static void onScheduleFired(Host currentHost, long now) {
        if (flowActive || phase == Phase.CONFIRMING) {
            scheduledPreempt = true;
            scheduledPending = true;
            // 任务本来就停着，接管不需要再等 20 秒
            scheduledFireAt = now;
            Log.accountSwitch("自动切号🔄️ 定时切号时间到，当前切换收尾后立即接管");
            return;
        }
        if (phase == Phase.ROUND_WAIT) {
            phase = Phase.RUNNING;
            resumeAt = 0;
            pendingTargetUid = null;
        }
        if (AccountSwitchConfig.isAccountSwitchDryRun()) {
            // 演练模式：到点只记计划，不终止当前账号的任务
            scheduledRetryCount = 0;
            scheduledPending = true;
            scheduledFireAt = now + SCHEDULE_SWITCH_DELAY_MILLIS;
            Log.accountSwitch("自动切号🔄️【演练】定时切号时间到，20 秒后记录计划（不终止当前任务）");
            return;
        }
        tasksHeld = true;
        try {
            currentHost.pause();
        } catch (Throwable t) {
            Log.printStackTrace(TAG + "#定时切号终止任务失败", t);
        }
        scheduledRetryCount = 0;
        scheduledPending = true;
        scheduledFireAt = now + SCHEDULE_SWITCH_DELAY_MILLIS;
        Log.accountSwitch("自动切号🔄️ 定时切号时间到，已终止当前账号任务，20 秒后切换到保持账号");
    }

    // ------------------------------------------------------------------
    // 未知态对账（CONFIRMING）
    // ------------------------------------------------------------------

    /** 未知态下每秒对账一次：落地到目标就加载，稳定地落在别处就按事实恢复 */
    private static void resolveConfirming(Host currentHost, long now) {
        if (confirmHandling || flowActive) {
            return;
        }
        String target = confirmTargetUid;
        String actual = AlipayAccountSwitchBridge.currentUid();
        if (!StringUtil.isEmpty(target) && target.equals(actual)) {
            final String fromUid = confirmFromUid;
            final boolean arrivingHold = confirmArrivingHold;
            confirmHandling = true;
            Log.accountSwitch("自动切号🔄️ 未知态对账：账号已落地到目标 " + label(target));
            if (!submitFlow(() -> {
                confirmTargetUid = null;
                confirmHandling = false;
                loadAccount(currentHost, fromUid, target, arrivingHold,
                        isHoldUid(fromUid));
            })) {
                confirmHandling = false;
            }
            return;
        }
        if (!StringUtil.isEmpty(actual) && !actual.equals(confirmFromUid)
                && actual.equals(lastConfirmActual)) {
            // 连续两次读到同一个非目标账号：切换落到了别处（或被人工干预），按事实恢复
            final String landed = actual;
            confirmHandling = true;
            Log.accountSwitch("自动切号🔄️ 未知态对账：账号落在 " + label(landed) + "，按实际账号恢复");
            if (!submitFlow(() -> {
                confirmTargetUid = null;
                confirmHandling = false;
                recoverToActual(currentHost, FAILED_RETRY_MILLIS);
            })) {
                confirmHandling = false;
            }
            return;
        }
        lastConfirmActual = actual;
        if (now >= confirmDeadline) {
            confirmHandling = true;
            Log.accountSwitch("自动切号🔄️ 未知态对账超时，按实际登录账号恢复");
            if (!submitFlow(() -> {
                confirmTargetUid = null;
                confirmHandling = false;
                recoverToActual(currentHost, FAILED_RETRY_MILLIS);
            })) {
                confirmHandling = false;
            }
        }
    }

    // ------------------------------------------------------------------
    // 流程提交
    // ------------------------------------------------------------------

    /** 提交轮换流程：先把任务停住再提交，避免提交到执行之间拉起新一轮任务 */
    private static void submitRotate() {
        tasksHeld = true;
        phase = Phase.SWITCHING;
        publishStatus(Phase.SWITCHING, null, null, "开始切换");
        if (!submitFlow(AccountSwitchController::flowRotate)) {
            tasksHeld = false;
            phase = Phase.RUNNING;
            nextAttemptAt = System.currentTimeMillis() + FAILED_RETRY_MILLIS;
        }
    }

    /** 流程串行提交：同一时刻最多一个流程在跑 */
    private static boolean submitFlow(Runnable flow) {
        ExecutorService executor = switchExecutor;
        if (executor == null || flowActive) {
            return false;
        }
        flowActive = true;
        try {
            executor.execute(() -> {
                try {
                    flow.run();
                } finally {
                    flowActive = false;
                }
            });
            return true;
        } catch (Throwable t) {
            flowActive = false;
            Log.printStackTrace(TAG + "#提交切换流程失败", t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 切换流程（在 switchExecutor 上串行执行）
    // ------------------------------------------------------------------

    /**
     * 常规轮换：规划目标 → （轮次等待）→ 静默 → 请求切号 → 确认落地 → 加载。
     * 任何失败都走分类处理并按事实恢复当前账号运行，不让任务永久停住。
     */
    private static void flowRotate() {
        Host currentHost = host;
        if (currentHost == null) {
            tasksHeld = false;
            phase = Phase.RUNNING;
            return;
        }
        String fromUid = null;
        try {
            // 用磁盘上的最新配置，避免切号过程中用户在设置里改了账号/顺序仍按旧快照执行
            AccountSwitchConfig.Snapshot config = AccountSwitchConfig.readFresh();
            if (!config.isAccountSwitchEnabled()) {
                releaseToCurrent(currentHost, "自动切换登录账号已关闭，取消本次切号");
                return;
            }
            if (scheduledPreempt) {
                abortForScheduled();
                return;
            }
            String currentUid = AlipayAccountSwitchBridge.currentUid();
            if (StringUtil.isEmpty(currentUid)) {
                // 读不到当前账号时不能盲目切号，避免切到错误账号或把登录态切丢
                Log.accountSwitch("自动切号🔄️ 读取当前登录账号失败，稍后重试");
                failRecover(currentHost, FAILED_RETRY_MILLIS);
                return;
            }
            fromUid = currentUid;
            String realUid = currentUid;
            Map<String, AlipayAccountSwitchBridge.HostAccount> hostMap = hostMap();
            AccountSwitchState st = state();
            long now = System.currentTimeMillis();

            // 合格账号：有登录记录且不在隔离期内
            Set<String> eligible = new HashSet<>();
            List<String> uids = new ArrayList<>();
            for (AccountSwitchConfig.Account account : config.getAccountSwitchAccounts()) {
                if (account == null || StringUtil.isEmpty(account.getUid())) {
                    continue;
                }
                uids.add(account.getUid());
                if (!StringUtil.isEmpty(resolveLoginId(account.getUid(), config, hostMap))
                        && !st.isQuarantined(account.getUid(), now)) {
                    eligible.add(account.getUid());
                }
            }
            if (uids.size() < 2) {
                failRecover(currentHost, FAILED_RETRY_MILLIS);
                return;
            }
            // 演练模式：规划从虚拟当前账号出发推进，真实登录账号不动；
            // 关掉演练后清掉虚拟游标，真实规划从实际账号重新开始
            boolean dryRun = config.isAccountSwitchDryRun();
            if (dryRun) {
                String virtual = st.getDryRunVirtualUid();
                if (!StringUtil.isEmpty(virtual) && uids.contains(virtual)) {
                    currentUid = virtual;
                }
            } else if (!StringUtil.isEmpty(st.getDryRunVirtualUid())) {
                st.setDryRunVirtualUid("");
                st.persist();
            }
            String holdUid = config.getAccountSwitchFinalAccountUid();
            List<String> effective = SwitchPlanner.effectiveOrder(uids, holdUid);
            boolean holdUsable = SwitchPlanner.isHoldUsable(effective, holdUid, eligible);
            boolean currentIsHold = holdUsable && currentUid.equals(holdUid);
            // 本次是不是「作为一轮收尾」进入的常态保持账号（进入时刻已落盘，重启后依然成立）
            boolean holdRoundEnd = currentIsHold && st.getHoldEnteredAt() > 0;
            SwitchPlanner.Target target = SwitchPlanner.nextTarget(
                    effective, currentUid, holdRoundEnd, eligible, holdUid, holdUsable);
            if (target == null) {
                Log.accountSwitch("自动切号🔄️ 没有可切换的账号（无登录记录或全部隔离中），稍后重试");
                failRecover(currentHost, FAILED_RETRY_MILLIS);
                return;
            }

            /*
             * 轮次等待：只在「正坐在作为一轮收尾进入的常态保持账号上」时才等。
             * 等待从切入那一刻起算（已落盘），期间它继续按自己的执行间隔跑任务；
             * 时间到了之后由 tick 提交新的轮换流程切走。
             */
            long roundIntervalMinutes = config.getAccountSwitchRoundIntervalMinutes();
            if (holdRoundEnd && roundIntervalMinutes > 0) {
                long waitRemainMillis = st.getHoldEnteredAt() + roundIntervalMinutes * 60_000L - now;
                if (waitRemainMillis > 0) {
                    phase = Phase.ROUND_WAIT;
                    resumeAt = now + waitRemainMillis;
                    pendingTargetUid = target.uid;
                    roundWaitLastLogAt = now;
                    tasksHeld = false;
                    // 演练模式下当前账号一栏仍显示真实在跑的账号，虚拟进度看状态里的游标
                    publishStatus(Phase.ROUND_WAIT, dryRun ? realUid : currentUid,
                            target.uid, "进入轮次等待");
                    currentHost.resume();
                    Log.accountSwitch("自动切号🔄️ 常态保持账号 " + label(currentUid)
                            + " 进入轮次等待，期间按执行间隔继续运行，约 "
                            + ((waitRemainMillis + 59_999L) / 60_000L) + " 分钟后切换到 "
                            + label(target.uid));
                    return;
                }
                Log.accountSwitch("自动切号🔄️ 常态保持账号 " + label(currentUid)
                        + " 的轮次等待时间已到，直接切换");
            }

            if (dryRun) {
                // 演练：只记计划并推进虚拟游标（到达保持账号时同步模拟轮次等待的起点），
                // 不停任务、不调宿主切换、不记失败；计划节奏由 tick 的单轮一计划闸门控制
                st.setDryRunVirtualUid(target.uid);
                if (target.arrivingHold) {
                    st.setHoldEnteredAt(now);
                } else if (currentIsHold) {
                    st.setHoldEnteredAt(0);
                }
                st.persist();
                dryRunAwaitingRun = true;
                tasksHeld = false;
                publishStatus(Phase.RUNNING, realUid, target.uid,
                        "演练：计划切换到 " + label(target.uid));
                Log.accountSwitch("自动切号🔄️【演练】计划切换: " + label(currentUid)
                        + " -> " + label(target.uid)
                        + (target.roundEnd ? "（本轮结束）" : "")
                        + "，演练模式不真正执行");
                return;
            }

            String loginId = resolveLoginId(target.uid, config, hostMap);
            Log.accountSwitch("自动切号🔄️ 开始切换: " + label(currentUid) + " -> " + label(target.uid)
                    + (target.roundEnd ? "（本轮结束）" : ""));
            quiesce(currentHost);
            if (!AccountSwitchConfig.read().isAccountSwitchEnabled()) {
                releaseToCurrent(currentHost, "自动切换登录账号已关闭，取消本次切号");
                return;
            }
            if (scheduledPreempt) {
                abortForScheduled();
                return;
            }
            AlipayAccountSwitchBridge.SwitchResult result =
                    AlipayAccountSwitchBridge.switchToAccount(target.uid, loginId);
            if (!result.isAccepted()) {
                long delay = handleSwitchFailure(st, fromUid, target.uid, result);
                failRecover(currentHost, delay);
                return;
            }
            // 已受理：进入确认阶段。确认期间即使被定时切号抢占也不中断——
            // 登录态正在迁移，硬打断只会把状态搞得更乱，定时流程会在落地后接管
            phase = Phase.CONFIRMING;
            confirmFromUid = fromUid;
            confirmTargetUid = target.uid;
            confirmArrivingHold = target.arrivingHold;
            lastConfirmActual = null;
            publishStatus(Phase.CONFIRMING, fromUid, target.uid, "等待切换落地");
            if (waitForAccount(target.uid, CONFIRM_TIMEOUT_MILLIS)) {
                confirmTargetUid = null;
                loadAccount(currentHost, fromUid, target.uid, target.arrivingHold, currentIsHold);
                return;
            }
            // 确认超时 ≠ 失败：进入未知态，任务保持暂停，tick 持续对账到截止时刻
            confirmDeadline = System.currentTimeMillis() + CONFIRM_UNKNOWN_MILLIS;
            publishStatus(Phase.CONFIRMING, fromUid, target.uid, "确认超时，未知态对账中");
            Log.accountSwitch("自动切号🔄️ 等待账号切换超时，进入未知态继续对账（最长 "
                    + (CONFIRM_UNKNOWN_MILLIS / 60_000L) + " 分钟），期间任务保持暂停");
        } catch (Throwable t) {
            Log.accountSwitch("自动切号🔄️ 切换流程异常（堆栈见异常日志）: " + t);
            Log.printStackTrace(TAG + "#切换账号失败", t);
            failRecover(currentHost, FAILED_RETRY_MILLIS);
        } finally {
            lastSwitchEndAt = System.currentTimeMillis();
            if (phase == Phase.SWITCHING) {
                phase = Phase.RUNNING;
            }
        }
    }

    /**
     * 定时切号：切换到常态保持账号并立即执行一轮；
     * 保持账号不可用（未配置/无登录记录/隔离中）时退回生效顺序里第一个合格账号。
     */
    private static void flowScheduled() {
        Host currentHost = host;
        if (currentHost == null) {
            tasksHeld = false;
            phase = Phase.RUNNING;
            return;
        }
        try {
            AccountSwitchConfig.Snapshot config = AccountSwitchConfig.readFresh();
            if (!config.isAccountSwitchEnabled()) {
                releaseToCurrent(currentHost, "自动切换登录账号已关闭，取消定时切号");
                return;
            }
            String currentUid = AlipayAccountSwitchBridge.currentUid();
            if (StringUtil.isEmpty(currentUid)) {
                Log.accountSwitch("自动切号🔄️ 读取当前登录账号失败，取消定时切号");
                scheduleRetryOrGiveUp(currentHost, "读取当前登录账号失败");
                return;
            }
            Map<String, AlipayAccountSwitchBridge.HostAccount> hostMap = hostMap();
            AccountSwitchState st = state();
            long now = System.currentTimeMillis();
            Set<String> eligible = new HashSet<>();
            List<String> uids = new ArrayList<>();
            for (AccountSwitchConfig.Account account : config.getAccountSwitchAccounts()) {
                if (account == null || StringUtil.isEmpty(account.getUid())) {
                    continue;
                }
                uids.add(account.getUid());
                if (!StringUtil.isEmpty(resolveLoginId(account.getUid(), config, hostMap))
                        && !st.isQuarantined(account.getUid(), now)) {
                    eligible.add(account.getUid());
                }
            }
            // 演练模式：规划同样从虚拟当前账号出发，真实登录账号不动
            boolean dryRun = config.isAccountSwitchDryRun();
            String realUid = currentUid;
            if (dryRun) {
                String virtual = st.getDryRunVirtualUid();
                if (!StringUtil.isEmpty(virtual) && uids.contains(virtual)) {
                    currentUid = virtual;
                }
            } else if (!StringUtil.isEmpty(st.getDryRunVirtualUid())) {
                st.setDryRunVirtualUid("");
                st.persist();
            }
            String holdUid = config.getAccountSwitchFinalAccountUid();
            List<String> effective = SwitchPlanner.effectiveOrder(uids, holdUid);
            String targetUid = null;
            if (SwitchPlanner.isHoldUsable(effective, holdUid, eligible)) {
                targetUid = holdUid;
            } else {
                for (String uid : effective) {
                    if (eligible.contains(uid)) {
                        targetUid = uid;
                        break;
                    }
                }
            }
            if (targetUid == null) {
                Log.accountSwitch("自动切号🔄️ 定时切号未找到可切换的账号");
                failRecover(currentHost, FAILED_RETRY_MILLIS);
                return;
            }
            if (dryRun) {
                // 演练：只记计划并推进虚拟游标（与真实成功一样记下保持账号进入时刻），
                // 不停任务、不调宿主切换、不占用定时重试预算
                st.setDryRunVirtualUid(targetUid);
                st.setHoldEnteredAt(now);
                st.persist();
                dryRunAwaitingRun = true;
                tasksHeld = false;
                scheduledRetryCount = 0;
                publishStatus(Phase.RUNNING, realUid, targetUid,
                        "演练：定时计划切到 " + label(targetUid));
                Log.accountSwitch("自动切号🔄️【演练】定时切号计划: " + label(currentUid)
                        + " -> " + label(targetUid) + "，演练模式不真正执行");
                phase = Phase.RUNNING;
                return;
            }
            String loginId = resolveLoginId(targetUid, config, hostMap);
            boolean sameAccount = targetUid.equals(currentUid);
            Log.accountSwitch("自动切号🔄️ 定时切号开始: " + label(currentUid) + " -> " + label(targetUid));
            publishStatus(Phase.SWITCHING, currentUid, targetUid, "定时切号开始");
            quiesce(currentHost);
            if (!sameAccount) {
                AlipayAccountSwitchBridge.SwitchResult result =
                        AlipayAccountSwitchBridge.switchToAccount(targetUid, loginId);
                if (!result.isAccepted()) {
                    long delay = handleSwitchFailure(st, currentUid, targetUid, result);
                    // 定时切号是硬需求：普通失败按定时重试预算再试，分类处理已隔离/熔断的不硬试
                    if (result.status == AlipayAccountSwitchBridge.SwitchStatus.REJECTED
                            || result.status == AlipayAccountSwitchBridge.SwitchStatus.ERROR) {
                        scheduleRetryOrGiveUp(currentHost, "切号被拒 " + result.status);
                    } else {
                        failRecover(currentHost, delay);
                    }
                    return;
                }
                if (!waitForAccount(targetUid, CONFIRM_TIMEOUT_MILLIS)) {
                    Log.accountSwitch("自动切号🔄️ 定时切号等待账号切换超时");
                    scheduleRetryOrGiveUp(currentHost, "等待账号切换超时");
                    return;
                }
            }
            if (scheduledPreempt) {
                // 执行期间又有新的定时点到达：放弃本次，等新的定时流程重新执行
                Log.accountSwitch("自动切号🔄️ 新的定时切号已接管，取消本次切换");
                abortForScheduled();
                return;
            }
            tasksHeld = false;
            if (currentHost.initialize(targetUid, true)) {
                scheduledRetryCount = 0;
                st.setHoldEnteredAt(System.currentTimeMillis());
                st.clearFailures(targetUid);
                st.clearGlobalFailures();
                st.recordSwitch(currentUid, targetUid, System.currentTimeMillis(), "scheduled_ok");
                st.persist();
                nextAttemptAt = 0;
                publishStatus(Phase.RUNNING, targetUid, "", "定时切号完成");
                Log.accountSwitch("自动切号🔄️ 定时切号完成，已切换到 " + label(targetUid) + " 并开始执行");
            } else {
                Log.accountSwitch("自动切号🔄️ 切换后加载账号失败");
                scheduleRetryOrGiveUp(currentHost, "切换后加载账号失败");
                return;
            }
            phase = Phase.RUNNING;
        } catch (Throwable t) {
            Log.accountSwitch("自动切号🔄️ 定时切号异常（堆栈见异常日志）: " + t);
            Log.printStackTrace(TAG + "#定时切号失败", t);
            scheduleRetryOrGiveUp(currentHost, "定时切号异常");
        } finally {
            lastSwitchEndAt = System.currentTimeMillis();
            if (phase == Phase.SWITCHING) {
                phase = Phase.RUNNING;
            }
        }
    }

    /** 静默：先推进账号纪元作废旧线程，再停任务并等它们退出（等不到也不硬卡） */
    private static void quiesce(Host currentHost) {
        tasksHeld = true;
        RunGeneration.advanceAccountEpoch();
        currentHost.pause();
        if (!ModelTask.waitForAllTaskStopped(WAIT_TASK_STOPPED_MILLIS)) {
            Log.accountSwitch("自动切号🔄️ 旧账号任务未在 " + (WAIT_TASK_STOPPED_MILLIS / 1000)
                    + " 秒内完全退出；账号纪元已推进，旧线程将在检查点作废，继续切换");
        }
    }

    /**
     * 切号请求被拒的分类处理（两个流程共用）：只更新落盘状态并记录日志，
     * 恢复运行由调用方决定（普通轮换走 failRecover，定时切号走重试预算）。
     * 需手动登录 → 隔离该账号（看门狗检测到手动登录后自动解除）；
     * 方法缺失 → 全局熔断；其余 → 失败计数、单账号预算隔离、全局预算熔断。
     *
     * @return 建议的下次重试间隔
     */
    private static long handleSwitchFailure(AccountSwitchState st,
                                            String fromUid, String targetUid,
                                            AlipayAccountSwitchBridge.SwitchResult result) {
        long now = System.currentTimeMillis();
        switch (result.status) {
            case NEED_MANUAL_LOGIN:
                st.quarantine(targetUid, now + QUARANTINE_MANUAL_MILLIS,
                        AccountSwitchState.REASON_MANUAL_LOGIN);
                st.recordSwitch(fromUid, targetUid, now, "need_manual_login");
                st.persist();
                Log.accountSwitch("自动切号🔄️ 目标账号 " + label(targetUid)
                        + " 需要先手动登录一次，已隔离该账号（手动登录后自动解除），本次跳过");
                return FAILED_RETRY_MILLIS;
            case METHOD_MISSING:
                st.setSwitchDisabledUntil(now + GLOBAL_DISABLE_MILLIS);
                st.recordSwitch(fromUid, targetUid, now, "method_missing");
                st.persist();
                Log.accountSwitch("自动切号🔄️ 当前支付宝版本未找到切号方法，已熔断切号 "
                        + (GLOBAL_DISABLE_MILLIS / 60_000L) + " 分钟，请关注模块更新");
                return FAILED_RETRY_MILLIS;
            default:
                int failures = st.recordFailure(targetUid);
                int global = st.recordGlobalFailure();
                if (failures >= ACCOUNT_FAILURE_BUDGET) {
                    st.quarantine(targetUid, now + QUARANTINE_REPEATED_MILLIS,
                            AccountSwitchState.REASON_REPEATED_FAILURES);
                    Log.accountSwitch("自动切号🔄️ 目标账号 " + label(targetUid) + " 连续失败 "
                            + failures + " 次，已隔离 " + (QUARANTINE_REPEATED_MILLIS / 60_000L)
                            + " 分钟");
                }
                if (global >= GLOBAL_FAILURE_BUDGET) {
                    st.setSwitchDisabledUntil(now + GLOBAL_DISABLE_MILLIS);
                    Log.accountSwitch("自动切号🔄️ 全局连续失败达 " + global + " 次，已熔断切号 "
                            + (GLOBAL_DISABLE_MILLIS / 60_000L) + " 分钟");
                }
                st.recordSwitch(fromUid, targetUid, now, "failed:" + result.status);
                st.persist();
                Log.accountSwitch("自动切号🔄️ 切号被拒（" + result.status + "：" + result.detail
                        + "），目标 " + label(targetUid));
                return backoffMillis(failures);
        }
    }

    /** 指数退避：60s 起，每多失败一次翻倍，上限 30 分钟 */
    private static long backoffMillis(int consecutiveFailures) {
        int shift = Math.min(Math.max(consecutiveFailures - 1, 0), 5);
        return Math.min(FAILED_RETRY_MILLIS << shift, BACKOFF_CAP_MILLIS);
    }

    /** 加载目标账号并收尾：成功清失败计数、维护轮次标记；失败按实际账号恢复 */
    private static void loadAccount(Host currentHost, String fromUid, String targetUid,
                                    boolean arrivingHold, boolean leavingHold) {
        tasksHeld = false;
        if (currentHost.initialize(targetUid, true)) {
            AccountSwitchState st = state();
            long now = System.currentTimeMillis();
            st.clearFailures(targetUid);
            st.clearGlobalFailures();
            if (arrivingHold) {
                // 切入常态保持账号：一轮到此结束，轮次等待从这一刻起算
                st.setHoldEnteredAt(now);
            } else if (leavingHold) {
                // 离开保持账号开始新一轮：清掉「作为一轮收尾」的标记
                st.setHoldEnteredAt(0L);
            }
            st.recordSwitch(fromUid, targetUid, now, "ok");
            st.persist();
            nextAttemptAt = 0;
            publishStatus(Phase.RUNNING, targetUid, "",
                    "已切换到 " + label(targetUid) + (arrivingHold ? "（本轮结束）" : ""));
            Log.accountSwitch("自动切号🔄️ 已切换到账号: " + label(targetUid)
                    + (arrivingHold ? "（本轮结束）" : ""));
        } else {
            Log.accountSwitch("自动切号🔄️ 切换后加载账号失败，按实际登录账号恢复");
            recoverToActual(currentHost, FAILED_RETRY_MILLIS);
            return;
        }
        phase = Phase.RUNNING;
    }

    /** 按事实恢复：实际登录哪个账号就加载哪个；加载不了就走 resume 的对账重载 */
    private static void recoverToActual(Host currentHost, long retryDelayMillis) {
        String actual = AlipayAccountSwitchBridge.currentUid();
        if (StringUtil.isEmpty(actual)) {
            actual = currentHost.currentUid();
        }
        tasksHeld = false;
        boolean loaded = false;
        if (!StringUtil.isEmpty(actual) && !actual.equals(currentHost.currentUid())) {
            loaded = currentHost.initialize(actual, true);
        }
        if (!loaded) {
            try {
                currentHost.resume();
            } catch (Throwable t) {
                Log.printStackTrace(TAG + "#恢复任务失败", t);
            }
        }
        // resume 会触发 onAccountRunStarted，这里在它之后设置退避才不会被覆盖
        nextAttemptAt = System.currentTimeMillis() + retryDelayMillis;
        publishStatus(Phase.RUNNING, actual, "", "按实际账号恢复运行");
        phase = Phase.RUNNING;
    }

    /** 切号未发生（被拒/取消/异常）：恢复当前账号运行并设置退避 */
    private static void failRecover(Host currentHost, long retryDelayMillis) {
        if (scheduledPreempt) {
            abortForScheduled();
            return;
        }
        tasksHeld = false;
        try {
            // resume 内部会核对登录账号与内存配置，登录态其实已变时按实际账号重载
            currentHost.resume();
        } catch (Throwable t) {
            Log.printStackTrace(TAG + "#恢复任务失败", t);
        }
        nextAttemptAt = System.currentTimeMillis() + retryDelayMillis;
        publishStatus(Phase.RUNNING, currentHost.currentUid(), "", null);
        phase = Phase.RUNNING;
    }

    /** 开关被关掉时取消本次切换并恢复运行 */
    private static void releaseToCurrent(Host currentHost, String message) {
        Log.accountSwitch("自动切号🔄️ " + message);
        tasksHeld = false;
        try {
            currentHost.resume();
        } catch (Throwable t) {
            Log.printStackTrace(TAG + "#恢复任务失败", t);
        }
        publishStatus(Phase.RUNNING, currentHost.currentUid(), "", "已取消切换，恢复运行");
        phase = Phase.RUNNING;
    }

    /** 定时切号已接管：任务保持停止，本流程直接退出，tick 随即提交定时流程 */
    private static void abortForScheduled() {
        Log.accountSwitch("自动切号🔄️ 定时切号已接管，取消本次切号");
        tasksHeld = true;
        phase = Phase.RUNNING;
    }

    /** 定时切号失败：预算内先按实际账号恢复运行、60 秒后重试；预算耗尽则放弃本次 */
    private static void scheduleRetryOrGiveUp(Host currentHost, String reason) {
        if (scheduledRetryCount < SCHEDULED_MAX_RETRY) {
            scheduledRetryCount++;
            scheduledPending = true;
            scheduledFireAt = System.currentTimeMillis() + FAILED_RETRY_MILLIS;
            // 等待确认超时这类失败里，登录态可能正在迁移：按事实恢复，
            // 避免用旧账号的配置在新登录态下跑任务（迟到落地由看门狗兜底）
            recoverToActual(currentHost, 0L);
            Log.accountSwitch("自动切号🔄️ 定时切号失败（" + reason + "），60 秒后重试（第 "
                    + scheduledRetryCount + " 次）");
        } else {
            scheduledRetryCount = 0;
            Log.accountSwitch("自动切号🔄️ 定时切号重试次数已达上限，放弃本次（" + reason + "）");
            failRecover(currentHost, FAILED_RETRY_MILLIS);
            return;
        }
        phase = Phase.RUNNING;
    }

    /** 轮询等待登录态落到目标账号；中途读到目标即成功 */
    private static boolean waitForAccount(String targetUid, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(SWITCH_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            String currentUid = AlipayAccountSwitchBridge.currentUid();
            if (targetUid.equals(currentUid)) {
                return true;
            }
        }
        return targetUid.equals(AlipayAccountSwitchBridge.currentUid());
    }

    // ------------------------------------------------------------------
    // 账号信息工具
    // ------------------------------------------------------------------

    private static Map<String, AlipayAccountSwitchBridge.HostAccount> hostMap() throws Throwable {
        Map<String, AlipayAccountSwitchBridge.HostAccount> map = new LinkedHashMap<>();
        for (AlipayAccountSwitchBridge.HostAccount account : AlipayAccountSwitchBridge.accounts()) {
            if (account != null) {
                map.put(account.uid, account);
            }
        }
        return map;
    }

    /** 切号用的登录标识：优先支付宝本地登录记录，其次配置里存的 */
    private static String resolveLoginId(String uid, AccountSwitchConfig.Snapshot config,
                                         Map<String, AlipayAccountSwitchBridge.HostAccount> hostMap) {
        AlipayAccountSwitchBridge.HostAccount hostAccount = hostMap.get(uid);
        if (hostAccount != null && !StringUtil.isEmpty(hostAccount.loginId)) {
            return hostAccount.loginId;
        }
        for (AccountSwitchConfig.Account account : config.getAccountSwitchAccounts()) {
            if (account != null && uid.equals(account.getUid())) {
                return account.getLoginId();
            }
        }
        return null;
    }

    private static boolean isHoldUid(String uid) {
        return !StringUtil.isEmpty(uid)
                && uid.equals(AccountSwitchConfig.getAccountSwitchFinalAccountUid());
    }

    /** 日志统一打码显示账号：登录名打码后展示，查不到再退回昵称/uid */
    private static String label(String uid) {
        if (StringUtil.isEmpty(uid)) {
            return "未知";
        }
        for (AccountSwitchConfig.Account account : AccountSwitchConfig.getAccountSwitchAccounts()) {
            if (account != null && uid.equals(account.getUid())) {
                String masked = AlipayAccountSwitchBridge.maskLoginId(account.getLoginId());
                if (!StringUtil.isEmpty(masked)) {
                    return masked;
                }
                if (!StringUtil.isEmpty(account.getName())) {
                    return account.getName();
                }
                break;
            }
        }
        return uid;
    }

    private static final class DaemonThreadFactory implements ThreadFactory {

        private int index;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "AccountSwitch-" + (++index));
            thread.setDaemon(true);
            return thread;
        }
    }
}
