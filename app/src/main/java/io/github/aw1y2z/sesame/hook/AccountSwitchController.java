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
import io.github.aw1y2z.sesame.data.task.ModelTask;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.StringUtil;
import io.github.aw1y2z.sesame.util.TimeUtil;

/**
 * 自动切换登录账号调度器。
 *
 * 触发条件：开关开启 + 当前账号任务全部结束（不含未来到点的定时子任务）
 *          + 当前账号运行时长达到「切号等待间隔」。
 * 切号顺序：按 config_common.json 中的账号列表顺序循环；
 *          列表走完（回到开头）时，如果配置了「常态保持账号」，先切到该账号执行一次，作为一轮结束；
 *          轮次等待从切入常态保持账号时开始计时，期间该账号继续按自己的执行间隔跑任务但不切号，
 *          等待时间到且本轮任务跑完后切到下一轮账号；重启后首次运行时视为等待时间已过，跑完直接切；
 *          未配置常态保持账号时，回到列表第一个账号等待轮次间隔。
 * 定时切号：config_common.json 中配置的「切换账号定时执行」到点后，立即终止当前账号任务，
 *          固定等待 20 秒后切换到常态保持账号并执行一轮（设置方式与定时执行相同：HH/HHmm/HHmmss，逗号分隔）。
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

    private static final String TAG = "AccountSwitch";

    /** 任务刚结束时留一点缓冲，避免任务链切换间隙误判为全部完成 */
    private static final long IDLE_GRACE_MILLIS = 5_000L;
    private static final long SWITCH_POLL_INTERVAL_MILLIS = 500L;
    private static final int SWITCH_POLL_COUNT = 40;
    private static final long FAILED_RETRY_MILLIS = 60_000L;
    /** 目标账号需要重新登录时，重试没有意义，拉长到 10 分钟再试一次 */
    private static final long MANUAL_LOGIN_RETRY_MILLIS = 10 * 60_000L;
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
     * <p>不等待就会让旧账号的收尾动作与新账号的任务重叠（两个账号同时处理同一个任务）。
     */
    private static final long WAIT_TASK_STOPPED_MILLIS = 30_000L;
    /** 定时切号：终止当前任务后到切换之间的固定间隔（20 秒） */
    private static final long SCHEDULE_SWITCH_DELAY_MILLIS = 20_000L;
    /** 定时切号失败后的最大重试次数（每次间隔 FAILED_RETRY_MILLIS） */
    private static final int SCHEDULED_MAX_RETRY = 3;

    private static final Object LOCK = new Object();

    private static volatile Host host;
    private static volatile boolean paused;
    private static volatile boolean switching;
    private static volatile boolean awaitingResume;
    private static volatile long resumeAt;
    /** 轮次等待结束后要切入的账号；为空表示等待结束直接恢复当前账号 */
    private static volatile String pendingSwitchUid;
    private static volatile long runStartTime;
    private static volatile long tasksStartedAt;
    private static volatile long idleSince;
    private static volatile long nextAttemptAt;
    /** 本账号是否已经输出过「等待切号间隔」的提示，避免重复刷日志 */
    private static volatile boolean intervalWaitLogged;
    /** 上一次输出轮次等待状态的时间 */
    private static volatile long roundWaitLastLogAt;
    private static volatile long lastAccountSync;
    private static volatile long lastSwitchEndAt;
    /** 切入常态保持账号的时间点，轮次等待从这个时间开始计算；0 表示等待时间视为已过（如重启后首次运行） */
    private static volatile long holdEnteredAt;
    /** 定时切号已触发并终止任务，到该时刻后切换到保持账号；0 表示没有待执行的定时切号 */
    private static volatile long scheduledSwitchAt;
    /** 上一次检查定时切号时间点的时刻，用于判断时间点是否落在两次检查之间 */
    private static volatile long lastScheduleCheckAt;
    /** 定时切号失败后的重试计数，成功后清零 */
    private static volatile int scheduledRetryCount;
    /** 已触发过的定时切号时间点（日期+时间），避免同一个时间点重复触发 */
    private static final Set<String> firedSchedulePoints = new HashSet<>();
    private static volatile long scheduleDayStart;

    private static volatile long processStartAt;

    private static final AtomicBoolean syncRequested = new AtomicBoolean();

    private static ScheduledExecutorService scheduler;
    private static ExecutorService switchExecutor;
    private static ExecutorService syncExecutor;

    private AccountSwitchController() {
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

    /** 主任务被暂停（切号中/轮次等待中）时，ApplicationHook 不应执行新一轮任务 */
    public static boolean isPaused() {
        return paused;
    }

    /** 当前账号开始运行：记录运行时长起点 */
    public static void onAccountRunStarted(String uid) {
        runStartTime = System.currentTimeMillis();
        tasksStartedAt = 0;
        idleSince = 0;
        nextAttemptAt = 0;
        awaitingResume = false;
        resumeAt = 0;
        pendingSwitchUid = null;
        intervalWaitLogged = false;
        roundWaitLastLogAt = 0;
        Log.record("自动切号🔄️ 账号开始运行: " + label(uid));
    }

    /** 一轮任务已下发，允许开始判断任务是否全部结束 */
    public static void onTasksStarted() {
        tasksStartedAt = System.currentTimeMillis();
        idleSince = 0;
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

    /** 模块启动时把支付宝登录账号合并到公共配置，已有顺序不变，新账号追加 */
    public static void syncAccountsFromHost() {
        lastAccountSync = System.currentTimeMillis();
        try {
            List<AccountSwitchConfig.Account> accounts =
                    AlipayAccountSwitchBridge.fetchAccountsForConfig();
            if (accounts.isEmpty()) {
                Log.record("自动切号🔄️ 未读取到支付宝登录账号");
                return;
            }
            AccountSwitchConfig.mergeHostAccounts(accounts);
            Log.record("自动切号🔄️ 账号列表已同步，共 "
                    + AccountSwitchConfig.getAccountSwitchAccounts().size() + " 个账号");
        } catch (Throwable t) {
            Log.printStackTrace(TAG + "#同步登录账号失败", t);
        }
    }

    private static void tick() {
        try {
            Host currentHost = host;
            if (currentHost == null) {
                return;
            }
            AccountSwitchConfig.Snapshot config = AccountSwitchConfig.read();
            if (!config.isAccountSwitchEnabled()) {
                scheduledSwitchAt = 0;
                // 开关关闭期间不参与时间点判定，重新开启后也不补触发关闭期间错过的时间点
                lastScheduleCheckAt = 0;
                if (paused || awaitingResume) {
                    paused = false;
                    awaitingResume = false;
                    pendingSwitchUid = null;
                    roundWaitLastLogAt = 0;
                    holdEnteredAt = 0;
                    currentHost.resume();
                }
                return;
            }
            long now = System.currentTimeMillis();
            // 定时切号优先：不管当前处于什么状态，到点立即终止任务，20 秒后切到保持账号
            if (checkScheduleTrigger(config, now)) {
                beginScheduledSwitch(currentHost);
                return;
            }
            if (switching) {
                return;
            }
            // 定时切号：任务已终止，固定等待 20 秒后切到保持账号
            if (scheduledSwitchAt > 0) {
                if (now < scheduledSwitchAt) {
                    return;
                }
                scheduledSwitchAt = 0;
                paused = true;
                switching = true;
                try {
                    switchExecutor.execute(AccountSwitchController::switchToHoldAndRun);
                } catch (Throwable t) {
                    Log.printStackTrace(TAG + "#定时切号提交失败", t);
                    switching = false;
                    paused = false;
                }
                return;
            }
            if (awaitingResume) {
                if (now >= resumeAt) {
                    String pendingUid = pendingSwitchUid;
                    if (!ModelTask.isAllTaskFinished()) {
                        // 保持账号还在按自己的执行间隔跑任务，等这一轮跑完再切号，避免中途打断
                        resumeAt = now + SWITCH_POLL_INTERVAL_MILLIS;
                        return;
                    }
                    pendingSwitchUid = null;
                    awaitingResume = false;
                    roundWaitLastLogAt = 0;
                    if (!StringUtil.isEmpty(pendingUid) && !pendingUid.equals(currentHost.currentUid())) {
                        // 轮次等待结束：从保持账号切入下一轮账号并立即开始执行。
                        // 先暂停再提交切号，避免提交到真正执行之间又拉起新一轮任务
                        final String targetUid = pendingUid;
                        try {
                            paused = true;
                            switching = true;
                            switchExecutor.execute(() -> resumeSwitch(targetUid));
                            return;
                        } catch (Throwable t) {
                            Log.printStackTrace(TAG + "#轮次切号提交失败", t);
                            switching = false;
                            paused = false;
                        }
                    }
                    paused = false;
                    onAccountRunStarted(currentHost.currentUid());
                    currentHost.resume();
                    Log.record("自动切号🔄️ 轮次等待结束，开始运行");
                } else if (now - roundWaitLastLogAt >= ROUND_WAIT_LOG_INTERVAL_MILLIS) {
                    roundWaitLastLogAt = now;
                    long remainMinutes = (resumeAt - now + 59_999L) / 60_000L;
                    Log.record("自动切号🔄️ 轮次等待中，当前保持账号: "
                            + label(currentHost.currentUid())
                            + "，预计还需 " + remainMinutes + " 分钟");
                }
                return;
            }
            // 账号列表不足（例如先在设置界面建了配置文件）时定期从支付宝补全，新登录的账号会追加到末尾
            if (config.getAccountSwitchAccounts().size() < 2) {
                if (processStartAt > 0 && now - processStartAt < STARTUP_SYNC_DELAY_MILLIS) {
                    return;
                }
                if (now - lastAccountSync >= ACCOUNT_SYNC_INTERVAL_MILLIS) {
                    syncAccountsFromHost();
                }
                return;
            }
            // 列表够用时也定期复核：新登录的账号会自动追加到末尾（顺序不变）
            if (now - lastAccountSync >= ACCOUNT_RESYNC_INTERVAL_MILLIS) {
                syncAccountsFromHost();
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
                return;
            }
            if (idleSince <= 0) {
                idleSince = now;
            }
            if (now - idleSince < IDLE_GRACE_MILLIS) {
                return;
            }
            if (nextAttemptAt > now) {
                return;
            }
            if (lastSwitchEndAt > 0 && now - lastSwitchEndAt < MIN_SWITCH_GAP_MILLIS) {
                return;
            }
            long intervalMillis = config.getAccountSwitchIntervalMinutes() * 60_000L;
            /*
             * 「切号等待间隔」从**任务跑完那一刻**起算，而不是从账号开始运行起算。
             * 原实现用 runStartTime：本轮任务跑了 40 分钟、切号间隔设 10 分钟的话，
             * 任务一结束就立刻切走 —— 间隔形同虚设，切号看起来非常频繁。
             * 改成从 idleSince（任务全部完成）起算后，语义就是
             * 「这个账号的任务跑完，再等 N 分钟才切走」，与配置项说明一致。
             */
            long waitedMillis = now - idleSince;
            if (waitedMillis < intervalMillis) {
                // 任务已全部完成，但要等切号间隔到，这里只提示一次
                if (!intervalWaitLogged) {
                    intervalWaitLogged = true;
                    long remainMinutes = (intervalMillis - waitedMillis + 59_999L) / 60_000L;
                    Log.record("自动切号🔄️ 当前账号任务已全部完成，距离切号间隔还需 "
                            + remainMinutes + " 分钟，等待中");
                }
                return;
            }
            intervalWaitLogged = false;
            switching = true;
            try {
                switchExecutor.execute(() -> doSwitch(config));
            } catch (Throwable t) {
                // 提交失败必须复位，否则 switching 会一直为 true，后续所有切号检查都会被挡住
                Log.printStackTrace(TAG + "#提交切号失败", t);
                switching = false;
                nextAttemptAt = System.currentTimeMillis() + FAILED_RETRY_MILLIS;
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG + "#自动切号检查失败", t);
        }
    }

    private static void doSwitch(AccountSwitchConfig.Snapshot config) {
        Host currentHost = host;
        boolean completed = false;
        boolean stoppedTasks = false;
        try {
            if (currentHost == null) {
                return;
            }
            // 用磁盘上的最新配置，避免切号过程中用户在设置里改了账号/顺序仍按旧快照执行
            config = AccountSwitchConfig.readFresh();
            if (!config.isAccountSwitchEnabled()) {
                Log.record("自动切号🔄️ 自动切换登录账号已关闭，取消本次切号");
                return;
            }
            if (scheduledSwitchAt > 0) {
                // 定时切号已接管，当前任务已终止，直接交给定时流程
                Log.record("自动切号🔄️ 定时切号已接管，取消本次切号");
                return;
            }
            String currentUid = AlipayAccountSwitchBridge.currentUid();
            if (StringUtil.isEmpty(currentUid)) {
                // 读不到当前账号时不能盲目切号，避免切到错误账号或把登录态切丢
                Log.record("自动切号🔄️ 读取当前登录账号失败，稍后重试");
                nextAttemptAt = System.currentTimeMillis() + FAILED_RETRY_MILLIS;
                return;
            }
            List<AlipayAccountSwitchBridge.HostAccount> hostAccounts =
                    AlipayAccountSwitchBridge.accounts();
            LinkedHashMap<String, AlipayAccountSwitchBridge.HostAccount> hostMap =
                    new LinkedHashMap<>();
            for (AlipayAccountSwitchBridge.HostAccount account : hostAccounts) {
                hostMap.put(account.uid, account);
            }
            List<AccountSwitchConfig.Account> ordered = config.getAccountSwitchAccounts();
            if (ordered.size() < 2) {
                nextAttemptAt = System.currentTimeMillis() + FAILED_RETRY_MILLIS;
                return;
            }

            int holdIndex = indexOf(ordered, config.getAccountSwitchFinalAccountUid());
            AccountSwitchConfig.Account holdAccount = holdIndex >= 0 ? ordered.get(holdIndex) : null;
            // 常态保持账号必须有登录记录，否则退回"回到列表第一个账号"的逻辑
            boolean holdUsable = holdIndex >= 0 && holdAccount != null
                    && !StringUtil.isEmpty(resolveLoginId(holdAccount, hostMap));

            boolean currentIsHold = holdUsable && currentUid.equals(holdAccount.getUid());

            /*
             * 切号目标的选取规则（用户期望）：
             *   账号A 跑完本轮全部任务 → 切到账号B → 账号B 跑完 → 切回预设账号（常态保持账号）。
             *
             * 原实现按「列表原始顺序 + 下标+1」取下一个，但常态保持账号在列表里的位置是任意的：
             * 若它排在列表中间，走到它时会被当成一轮的收尾，导致后面的账号一轮里跑不到；
             * 若它排在列表前面，则要绕回去才能再到它，一轮里它会被访问两次。
             * 两种情况都会表现为「切号来回横跳、频繁切换」。
             *
             * 现在改为构造「生效顺序」：把常态保持账号挪到列表末尾，
             *   [账号1, 账号2, ..., 常态保持账号]
             * 一轮必然以常态保持账号收尾，之后从第一个账号开始下一轮，
             * 与上面的期望完全一致。保持账号不可用时退化为原始顺序。
             */
            List<AccountSwitchConfig.Account> effective =
                    buildEffectiveOrder(ordered, holdAccount, holdUsable);
            if (effective.size() < 2) {
                nextAttemptAt = System.currentTimeMillis() + FAILED_RETRY_MILLIS;
                return;
            }
            int currentIndex = indexOf(effective, currentUid);
            // 本次是不是「作为一轮收尾」进入的常态保持账号（>0 表示是）
            boolean holdRoundEnd = currentIsHold && holdEnteredAt > 0;
            // 目标起点：保持账号作为一轮收尾后，从列表第一个账号开始下一轮；否则取下一个
            int start = holdRoundEnd ? 0 : (currentIndex >= 0 ? currentIndex + 1 : 0);

            AccountSwitchConfig.Account target = null;
            int targetIndex = -1;
            for (int step = 0; step < effective.size(); step++) {
                int index = (start + step) % effective.size();
                AccountSwitchConfig.Account candidate = effective.get(index);
                if (candidate == null || StringUtil.isEmpty(candidate.getUid())) {
                    continue;
                }
                if (candidate.getUid().equals(currentUid)) {
                    continue;
                }
                if (StringUtil.isEmpty(resolveLoginId(candidate, hostMap))) {
                    // 该账号在支付宝本地已无登录记录，跳过
                    continue;
                }
                target = candidate;
                targetIndex = index;
                break;
            }

            if (target == null) {
                Log.record("自动切号🔄️ 没有可切换的账号，稍后重试");
                nextAttemptAt = System.currentTimeMillis() + FAILED_RETRY_MILLIS;
                return;
            }

            /*
             * 一轮的边界：
             *   - 有常态保持账号：一轮以「切入常态保持账号」结束；
             *   - 没有常态保持账号（未配置/本地无登录记录）：一轮以「列表走完回到开头」结束。
             *
             * 注意区分：只有「切过去的目标**就是**常态保持账号」才算 arrivingHold。
             * 列表回绕时目标可能只是列表的第一个账号，那不是保持账号，
             * 不能给它打上「一轮收尾」的标记（否则会被误当成保持账号进入轮次等待）。
             */
            boolean arrivingHold = holdUsable && isSameAccount(target, holdAccount);
            boolean wrapped = !holdRoundEnd && (currentIndex < 0 || targetIndex <= currentIndex);
            boolean roundEnd = arrivingHold || (!holdUsable && wrapped);

            String loginId = resolveLoginId(target, hostMap);
            boolean sameAccount = target.getUid().equals(currentUid);
            if (!sameAccount && StringUtil.isEmpty(loginId)) {
                Log.record("自动切号🔄️ 没有可切换的账号，稍后重试");
                nextAttemptAt = System.currentTimeMillis() + FAILED_RETRY_MILLIS;
                return;
            }

            /*
             * 轮次等待。
             * 只在「当前正坐在作为一轮收尾进入的常态保持账号上」时才等待：
             * 等待从切入该账号那一刻起算，期间它继续按自己的执行间隔跑任务但不切号；
             * 等时间到了、且它当前这轮任务跑完，再切到下一轮的第一个账号。
             *
             * 注意不能写成「目标是保持账号就等待」——那会把"正要切过去"和
             * "已经切过去了"混为一谈，导致刚切到保持账号就被判定为等待结束。
             */
            long roundIntervalMinutes = config.getAccountSwitchRoundIntervalMinutes();
            if (holdRoundEnd && roundIntervalMinutes > 0) {
                long waitRemainMillis = holdEnteredAt + roundIntervalMinutes * 60_000L
                        - System.currentTimeMillis();
                if (waitRemainMillis > 0) {
                    awaitingResume = true;
                    pendingSwitchUid = target.getUid();
                    roundWaitLastLogAt = System.currentTimeMillis();
                    resumeAt = System.currentTimeMillis() + waitRemainMillis;
                    completed = true;
                    Log.record("自动切号🔄️ 常态保持账号 " + label(currentUid)
                            + " 进入轮次等待，期间按执行间隔继续运行，约 "
                            + ((waitRemainMillis + 59_999L) / 60_000L) + " 分钟后切换到 "
                            + label(target));
                    return;
                }
                Log.record("自动切号🔄️ 常态保持账号 " + label(currentUid)
                        + " 的轮次等待时间已到，直接切换");
            }

            Log.record("自动切号🔄️ 开始切换: " + label(currentUid) + " -> " + label(target)
                    + (roundEnd ? "（本轮结束）" : ""));
            paused = true;
            stoppedTasks = true;
            currentHost.pause();
            // 等旧账号的任务线程真正退出再换登录态：
            // pause() 只是通知线程作废，线程要到下一个检查点才结束，
            // 不等它退出会让旧账号的收尾动作与新账号的任务重叠（两个账号同时处理同一个任务）
            if (!ModelTask.waitForAllTaskStopped(WAIT_TASK_STOPPED_MILLIS)) {
                Log.record("自动切号🔄️ 旧账号任务未在 " + (WAIT_TASK_STOPPED_MILLIS / 1000)
                        + " 秒内完全退出，仍继续切换");
            }

            if (!sameAccount) {
                if (!AlipayAccountSwitchBridge.switchTo(target.getUid(), loginId)) {
                    logSwitchRejected();
                    return;
                }
                if (!waitForAccount(target.getUid())) {
                    Log.record("自动切号🔄️ 等待账号切换超时");
                    return;
                }
            }

            if (arrivingHold) {
                /*
                 * 切到了常态保持账号：这一轮到此结束。
                 * 记录切入时间（轮次等待从这一刻起算），保持账号继续按自己的执行间隔跑任务。
                 */
                holdEnteredAt = System.currentTimeMillis();
                if (scheduledSwitchAt <= 0) {
                    paused = false;
                }
                completed = currentHost.initialize(target.getUid(), true);
                if (!completed) {
                    Log.record("自动切号🔄️ 切换到常态保持账号后加载账号失败");
                    return;
                }
                Log.record("自动切号🔄️ 已切换到常态保持账号 " + label(target) + "，本轮结束");
            } else {
                if (scheduledSwitchAt <= 0) {
                    paused = false;
                }
                completed = currentHost.initialize(target.getUid(), true);
                if (!completed) {
                    Log.record("自动切号🔄️ 切换后加载账号失败");
                }
                Log.record("自动切号🔄️ 已切换到账号: " + label(target));
                if (currentIsHold) {
                    // 离开保持账号开始新一轮：清掉「作为一轮收尾」的标记
                    holdEnteredAt = 0L;
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG + "#切换账号失败", t);
        } finally {
            if (!completed && stoppedTasks && host != null) {
                if (scheduledSwitchAt > 0) {
                    // 定时切号已接管，保持任务停止，等定时流程处理
                    paused = true;
                } else {
                    // 切号失败：恢复当前账号运行，避免任务一直停着
                    awaitingResume = false;
                    pendingSwitchUid = null;
                    paused = false;
                    try {
                        host.resume();
                    } catch (Throwable t) {
                        Log.printStackTrace(TAG + "#恢复任务失败", t);
                    }
                    // resume 会重置 nextAttemptAt，这里要放在后面设置才能生效
                    nextAttemptAt = System.currentTimeMillis() + failedRetryMillis();
                }
            }
            if (stoppedTasks) {
                lastSwitchEndAt = System.currentTimeMillis();
            }
            switching = false;
        }
    }

    /**
     * 轮次等待结束后执行：从保持账号切换到下一轮账号，并立即开始执行任务。
     */
    private static void resumeSwitch(String targetUid) {
        Host currentHost = host;
        boolean completed = false;
        boolean stoppedTasks = false;
        try {
            if (currentHost == null) {
                return;
            }
            // 等待期间配置可能已变，按磁盘最新内容确认开关与登录记录
            AccountSwitchConfig.Snapshot config = AccountSwitchConfig.readFresh();
            if (!config.isAccountSwitchEnabled()) {
                Log.record("自动切号🔄️ 自动切换登录账号已关闭，取消本次切号");
                return;
            }
            if (scheduledSwitchAt > 0) {
                Log.record("自动切号🔄️ 定时切号已接管，取消本次轮次切号");
                return;
            }
            String loginId = null;
            for (AlipayAccountSwitchBridge.HostAccount account : AlipayAccountSwitchBridge.accounts()) {
                if (account != null && targetUid.equals(account.uid)) {
                    loginId = account.loginId;
                    break;
                }
            }
            if (StringUtil.isEmpty(loginId)) {
                Log.record("自动切号🔄️ 目标账号已无登录记录，取消本次切号");
                return;
            }
            Log.record("自动切号🔄️ 轮次等待结束，开始切换: "
                    + label(currentHost.currentUid()) + " -> " + label(targetUid));
            paused = true;
            stoppedTasks = true;
            currentHost.pause();
            // 等旧账号任务真正退出，避免两个账号的任务重叠
            if (!ModelTask.waitForAllTaskStopped(WAIT_TASK_STOPPED_MILLIS)) {
                Log.record("自动切号🔄️ 旧账号任务未在 " + (WAIT_TASK_STOPPED_MILLIS / 1000)
                        + " 秒内完全退出，仍继续切换");
            }
            if (!AlipayAccountSwitchBridge.switchTo(targetUid, loginId)) {
                logSwitchRejected();
                return;
            }
            if (!waitForAccount(targetUid)) {
                Log.record("自动切号🔄️ 等待账号切换超时");
                return;
            }
            if (scheduledSwitchAt <= 0) {
                paused = false;
            }
            completed = currentHost.initialize(targetUid, true);
            if (completed) {
                holdEnteredAt = 0L;
                Log.record("自动切号🔄️ 已切换到账号: " + label(targetUid));
            } else {
                Log.record("自动切号🔄️ 切换后加载账号失败");
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG + "#轮次切号失败", t);
        } finally {
            if (!completed && host != null) {
                if (scheduledSwitchAt > 0) {
                    // 定时切号已接管，保持任务停止
                    paused = true;
                } else {
                    // 切号失败/取消：恢复当前保持账号运行，避免任务一直停着
                    paused = false;
                    try {
                        host.resume();
                    } catch (Throwable t) {
                        Log.printStackTrace(TAG + "#恢复任务失败", t);
                    }
                    nextAttemptAt = System.currentTimeMillis() + failedRetryMillis();
                }
            }
            if (stoppedTasks) {
                lastSwitchEndAt = System.currentTimeMillis();
            }
            switching = false;
        }
    }

    /**
     * 定时切号第一步：不管当前处于什么状态，立即终止当前账号任务，
     * 终止完成后固定等待 20 秒，再切到保持账号重新执行。
     */
    private static void beginScheduledSwitch(Host currentHost) {
        paused = true;
        awaitingResume = false;
        pendingSwitchUid = null;
        roundWaitLastLogAt = 0;
        if (currentHost != null) {
            try {
                currentHost.pause();
            } catch (Throwable t) {
                Log.printStackTrace(TAG + "#定时切号终止任务失败", t);
            }
        }
        // 每次到点都重新计时，若上一次定时切号还在执行，直接以本次为准
        scheduledRetryCount = 0;
        scheduledSwitchAt = System.currentTimeMillis() + SCHEDULE_SWITCH_DELAY_MILLIS;
        Log.record("自动切号🔄️ 定时切号时间到，已终止当前账号任务，20 秒后切换到保持账号");
    }

    /**
     * 判断是否有「切换账号定时执行」时间点落在上一次检查到现在之间。
     * 命中后记录该时间点，避免同一天同一时间重复触发；进程刚启动时只记录起点，不补触发历史时间点。
     */
    private static boolean checkScheduleTrigger(AccountSwitchConfig.Snapshot config, long now) {
        long previous = lastScheduleCheckAt;
        lastScheduleCheckAt = now;
        List<String> times = config.getAccountSwitchScheduleTimes();
        if (times.isEmpty() || previous <= 0 || now <= previous) {
            return false;
        }
        long todayStart = TimeUtil.getToday().getTimeInMillis();
        if (todayStart != scheduleDayStart) {
            scheduleDayStart = todayStart;
            firedSchedulePoints.clear();
        }
        for (String time : times) {
            Calendar calendar = TimeUtil.getCalendarByTimeStr(now, time);
            if (calendar == null) {
                continue;
            }
            long scheduledAt = calendar.getTimeInMillis();
            if (scheduledAt <= previous || scheduledAt > now) {
                continue;
            }
            if (firedSchedulePoints.add(todayStart + "|" + time)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 定时切号：任务已终止且等待结束后，切换到常态保持账号并立即执行一轮；
     * 未配置可用的保持账号时，退回列表第一个可用账号。
     */
    private static void switchToHoldAndRun() {
        Host currentHost = host;
        boolean completed = false;
        boolean retryable = false;
        try {
            if (currentHost == null) {
                return;
            }
            AccountSwitchConfig.Snapshot config = AccountSwitchConfig.readFresh();
            if (!config.isAccountSwitchEnabled()) {
                Log.record("自动切号🔄️ 自动切换登录账号已关闭，取消定时切号");
                return;
            }
            String currentUid = AlipayAccountSwitchBridge.currentUid();
            if (StringUtil.isEmpty(currentUid)) {
                Log.record("自动切号🔄️ 读取当前登录账号失败，取消定时切号");
                return;
            }
            LinkedHashMap<String, AlipayAccountSwitchBridge.HostAccount> hostMap =
                    new LinkedHashMap<>();
            for (AlipayAccountSwitchBridge.HostAccount account : AlipayAccountSwitchBridge.accounts()) {
                if (account != null) {
                    hostMap.put(account.uid, account);
                }
            }
            List<AccountSwitchConfig.Account> ordered = config.getAccountSwitchAccounts();
            AccountSwitchConfig.Account target = null;
            int holdIndex = indexOf(ordered, config.getAccountSwitchFinalAccountUid());
            if (holdIndex >= 0
                    && !StringUtil.isEmpty(resolveLoginId(ordered.get(holdIndex), hostMap))) {
                target = ordered.get(holdIndex);
            } else {
                for (AccountSwitchConfig.Account account : ordered) {
                    if (!StringUtil.isEmpty(resolveLoginId(account, hostMap))) {
                        target = account;
                        break;
                    }
                }
            }
            if (target == null) {
                Log.record("自动切号🔄️ 定时切号未找到可切换的账号");
                return;
            }
            final String targetUid = target.getUid();
            final String loginId = resolveLoginId(target, hostMap);
            boolean sameAccount = targetUid.equals(currentUid);
            retryable = true;
            Log.record("自动切号🔄️ 定时切号开始: " + label(currentUid) + " -> " + label(targetUid));
            if (!sameAccount) {
                paused = true;
                currentHost.pause();
                // 等旧账号任务真正退出，避免两个账号的任务重叠
                if (!ModelTask.waitForAllTaskStopped(WAIT_TASK_STOPPED_MILLIS)) {
                    Log.record("自动切号🔄️ 旧账号任务未在 " + (WAIT_TASK_STOPPED_MILLIS / 1000)
                            + " 秒内完全退出，仍继续切换");
                }
                if (!AlipayAccountSwitchBridge.switchTo(targetUid, loginId)) {
                    logSwitchRejected();
                    return;
                }
                if (!waitForAccount(targetUid)) {
                    Log.record("自动切号🔄️ 等待账号切换超时");
                    return;
                }
            }
            if (scheduledSwitchAt > 0) {
                // 执行期间又有新的定时点到达：放弃本次，等新的定时流程重新执行
                Log.record("自动切号🔄️ 新的定时切号已接管，取消本次切换");
                return;
            }
            holdEnteredAt = System.currentTimeMillis();
            paused = false;
            completed = currentHost.initialize(targetUid, true);
            if (completed) {
                Log.record("自动切号🔄️ 定时切号完成，已切换到 " + label(targetUid) + " 并开始执行");
            } else {
                Log.record("自动切号🔄️ 切换后加载账号失败");
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG + "#定时切号失败", t);
        } finally {
            if (!completed && host != null) {
                if (scheduledSwitchAt > 0) {
                    // 新的定时切号已接管，保持任务停止
                    paused = true;
                } else {
                    // 取消/失败：先恢复当前账号运行，避免任务一直停着
                    paused = false;
                    try {
                        host.resume();
                    } catch (Throwable t) {
                        Log.printStackTrace(TAG + "#恢复任务失败", t);
                    }
                    nextAttemptAt = System.currentTimeMillis() + failedRetryMillis();
                    // 定时切号是硬需求：失败后自动重试，避免当天这个时间点直接作废
                    if (retryable && scheduledRetryCount < SCHEDULED_MAX_RETRY) {
                        scheduledRetryCount++;
                        scheduledSwitchAt = System.currentTimeMillis() + FAILED_RETRY_MILLIS;
                        Log.record("自动切号🔄️ 定时切号失败，60 秒后重试（第 "
                                + scheduledRetryCount + " 次）");
                    } else if (retryable) {
                        scheduledRetryCount = 0;
                        Log.record("自动切号🔄️ 定时切号重试次数已达上限，放弃本次");
                    }
                }
            } else if (completed) {
                scheduledRetryCount = 0;
            }
            lastSwitchEndAt = System.currentTimeMillis();
            switching = false;
        }
    }

    private static boolean waitForAccount(String targetUid) {
        for (int i = 0; i < SWITCH_POLL_COUNT; i++) {
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
        return false;
    }

    private static String resolveLoginId(AccountSwitchConfig.Account account,
                                         Map<String, AlipayAccountSwitchBridge.HostAccount> hostMap) {
        if (account == null) {
            return null;
        }
        AlipayAccountSwitchBridge.HostAccount hostAccount = hostMap.get(account.getUid());
        if (hostAccount != null && !StringUtil.isEmpty(hostAccount.loginId)) {
            return hostAccount.loginId;
        }
        return account.getLoginId();
    }

    private static int indexOf(List<AccountSwitchConfig.Account> accounts, String uid) {
        if (StringUtil.isEmpty(uid)) {
            return -1;
        }
        for (int i = 0; i < accounts.size(); i++) {
            AccountSwitchConfig.Account account = accounts.get(i);
            if (account != null && uid.equals(account.getUid())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 构造「生效的切号顺序」。
     * <p>常态保持账号可用时把它挪到末尾，其余账号保持原相对顺序：
     * 一轮必然以常态保持账号收尾，之后从第一个账号开始下一轮。
     * 保持账号不可用（未配置或本地无登录记录）时退化为原始顺序。
     */
    private static List<AccountSwitchConfig.Account> buildEffectiveOrder(
            List<AccountSwitchConfig.Account> ordered,
            AccountSwitchConfig.Account holdAccount,
            boolean holdUsable) {
        List<AccountSwitchConfig.Account> result = new ArrayList<>();
        if (ordered == null || ordered.isEmpty()) {
            return result;
        }
        if (!holdUsable || holdAccount == null) {
            result.addAll(ordered);
            return result;
        }
        for (AccountSwitchConfig.Account account : ordered) {
            if (account == null || isSameAccount(account, holdAccount)) {
                continue;
            }
            result.add(account);
        }
        result.add(holdAccount);
        return result;
    }

    /** 切号被拒时的日志：1014 说明目标账号凭证失效，需要用户手动登录一次 */
    private static void logSwitchRejected() {
        if (AlipayAccountSwitchBridge.isManualLoginRequired()) {
            Log.record("自动切号🔄️ 支付宝要求重新输入账号密码，本次切换跳过，请手动登录该账号一次");
        } else {
            Log.record("自动切号🔄️ 支付宝未受理切号请求");
        }
    }

    /** 失败重试间隔：需要重新登录时拉长间隔，避免无意义地反复尝试 */
    private static long failedRetryMillis() {
        return AlipayAccountSwitchBridge.isManualLoginRequired()
                ? MANUAL_LOGIN_RETRY_MILLIS : FAILED_RETRY_MILLIS;
    }

    private static boolean isSameAccount(AccountSwitchConfig.Account left,
                                         AccountSwitchConfig.Account right) {
        return left != null && right != null
                && !StringUtil.isEmpty(left.getUid())
                && left.getUid().equals(right.getUid());
    }

    /** 日志统一用登录名(loginId)显示账号，查不到再退回 uid */
    private static String label(String uid) {
        if (StringUtil.isEmpty(uid)) {
            return "未知";
        }
        for (AccountSwitchConfig.Account account : AccountSwitchConfig.getAccountSwitchAccounts()) {
            if (account != null && uid.equals(account.getUid())) {
                if (!StringUtil.isEmpty(account.getLoginId())) {
                    return account.getLoginId();
                }
                break;
            }
        }
        return uid;
    }

    private static String label(AccountSwitchConfig.Account account) {
        if (account == null) {
            return "未知";
        }
        if (!StringUtil.isEmpty(account.getLoginId())) {
            return account.getLoginId();
        }
        if (!StringUtil.isEmpty(account.getName())) {
            return account.getName();
        }
        return StringUtil.isEmpty(account.getUid()) ? "未知" : account.getUid();
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
