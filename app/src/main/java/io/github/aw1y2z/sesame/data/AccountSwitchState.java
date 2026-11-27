package io.github.aw1y2z.sesame.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import io.github.aw1y2z.sesame.util.FileUtil;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.StringUtil;

/**
 * 自动切换登录账号的「运行时状态」，与用户配置（{@link AccountSwitchConfig}）分开保存：
 * 配置是用户意图，状态是机器事实。状态单独落盘（config/account_switch_state.json，
 * 与 config_common.json 同目录），宿主进程被杀重启后按事实恢复，而不是靠猜测：
 *
 * holdEnteredAt        进入常态保持账号（作为一轮收尾）的时刻；进程重启后轮次等待按它继续计时
 * lastScheduleCheckAt  上次检查定时时间点的时刻；配合 firedSchedulePoints 保证重启不重复触发、
 *                      也不漏掉停机期间错过的时间点（由控制器按追赶窗口决定是否补触发）
 * firedSchedulePoints  已触发过的定时点，元素形如 "20261127|0800"，跨天自动失效
 * consecutiveFailures  每个账号的连续切换失败次数（成功或手动登录后清零）
 * quarantinedUntil     每个账号的隔离截止时刻：隔离期内规划器跳过该账号
 * quarantineReasons    隔离原因（need_manual_login / repeated_failures）
 * globalFailures       全局连续失败次数（任意一次切换成功后清零）
 * switchDisabledUntil  全局熔断截止时刻：失败预算耗尽后在此之前不再尝试切换
 * lastSwitch           最近一次切换记录 {from, to, at, result}，供日志与状态展示
 *
 * 读写都在类锁内串行，写入走 {@link FileUtil#write2File} 的临时文件替换，进程被杀不会写坏。
 */
public final class AccountSwitchState {

    public static final String FILE_NAME = "account_switch_state.json";

    /** 隔离原因：该账号需要用户手动登录一次 */
    public static final String REASON_MANUAL_LOGIN = "need_manual_login";
    /** 隔离原因：该账号连续切换失败达到预算 */
    public static final String REASON_REPEATED_FAILURES = "repeated_failures";

    private static final String TAG = AccountSwitchState.class.getSimpleName();

    private static AccountSwitchState instance;

    private long holdEnteredAt;
    private long lastScheduleCheckAt;
    private final Set<String> firedSchedulePoints = new HashSet<>();
    private final Map<String, Integer> consecutiveFailures = new HashMap<>();
    private final Map<String, Long> quarantinedUntil = new HashMap<>();
    private final Map<String, String> quarantineReasons = new HashMap<>();
    private int globalFailures;
    private long switchDisabledUntil;
    private String lastSwitchFrom = "";
    private String lastSwitchTo = "";
    private long lastSwitchAt;
    private String lastSwitchResult = "";
    // 状态卡片展示用：调度器发布的当前状态（阶段/当前账号/下一目标/最近事件/更新时间）
    private String statusPhase = "";
    private String statusUid = "";
    private String statusNextUid = "";
    private String statusEvent = "";
    private long statusUpdatedAt;

    private AccountSwitchState() {
    }

    public static File getFile() {
        return new File(FileUtil.MAIN_DIRECTORY_FILE, FILE_NAME);
    }

    /** 取单例（首次从磁盘加载）；加载失败时返回空白状态，绝不抛异常 */
    public static synchronized AccountSwitchState get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    // ------------------------------------------------------------------
    // 字段读写：getter/setter 只改内存，调用方在有意义的节点调 persist()
    // ------------------------------------------------------------------

    public synchronized long getHoldEnteredAt() {
        return holdEnteredAt;
    }

    public synchronized void setHoldEnteredAt(long holdEnteredAt) {
        this.holdEnteredAt = holdEnteredAt;
    }

    public synchronized long getLastScheduleCheckAt() {
        return lastScheduleCheckAt;
    }

    public synchronized void setLastScheduleCheckAt(long lastScheduleCheckAt) {
        this.lastScheduleCheckAt = lastScheduleCheckAt;
    }

    public synchronized boolean hasFiredSchedulePoint(String key) {
        return key != null && firedSchedulePoints.contains(key);
    }

    public synchronized void markSchedulePointFired(String key) {
        if (key != null) {
            firedSchedulePoints.add(key);
        }
    }

    /** 清掉非当天的已触发记录，避免集合无限增长；dayPrefix 形如 "20261127" */
    public synchronized void pruneSchedulePoints(String dayPrefix) {
        firedSchedulePoints.removeIf(key -> key == null || !key.startsWith(dayPrefix));
    }

    public synchronized int getConsecutiveFailures(String uid) {
        Integer value = uid == null ? null : consecutiveFailures.get(uid);
        return value == null ? 0 : value;
    }

    /** 记一次失败，返回该账号的连续失败次数 */
    public synchronized int recordFailure(String uid) {
        if (StringUtil.isEmpty(uid)) {
            return 0;
        }
        int next = getConsecutiveFailures(uid) + 1;
        consecutiveFailures.put(uid, next);
        return next;
    }

    public synchronized void clearFailures(String uid) {
        if (uid != null) {
            consecutiveFailures.remove(uid);
        }
    }

    /** 该账号是否在隔离期内 */
    public synchronized boolean isQuarantined(String uid, long now) {
        Long until = uid == null ? null : quarantinedUntil.get(uid);
        return until != null && until > now;
    }

    public synchronized long getQuarantinedUntil(String uid) {
        Long until = uid == null ? null : quarantinedUntil.get(uid);
        return until == null ? 0L : until;
    }

    public synchronized String getQuarantineReason(String uid) {
        String reason = uid == null ? null : quarantineReasons.get(uid);
        return reason == null ? "" : reason;
    }

    public synchronized void quarantine(String uid, long until, String reason) {
        if (StringUtil.isEmpty(uid)) {
            return;
        }
        quarantinedUntil.put(uid, until);
        quarantineReasons.put(uid, reason == null ? "" : reason);
    }

    public synchronized void clearQuarantine(String uid) {
        if (uid == null) {
            return;
        }
        quarantinedUntil.remove(uid);
        quarantineReasons.remove(uid);
    }

    public synchronized int getGlobalFailures() {
        return globalFailures;
    }

    /** 记一次全局失败，返回全局连续失败次数 */
    public synchronized int recordGlobalFailure() {
        return ++globalFailures;
    }

    public synchronized void clearGlobalFailures() {
        globalFailures = 0;
    }

    public synchronized long getSwitchDisabledUntil() {
        return switchDisabledUntil;
    }

    public synchronized void setSwitchDisabledUntil(long switchDisabledUntil) {
        this.switchDisabledUntil = switchDisabledUntil;
    }

    public synchronized void recordSwitch(String fromUid, String toUid, long at, String result) {
        lastSwitchFrom = fromUid == null ? "" : fromUid;
        lastSwitchTo = toUid == null ? "" : toUid;
        lastSwitchAt = at;
        lastSwitchResult = result == null ? "" : result;
    }

    public synchronized String getLastSwitchSummary() {
        if (lastSwitchAt <= 0) {
            return "";
        }
        return lastSwitchResult + " " + lastSwitchFrom + "->" + lastSwitchTo + "@" + lastSwitchAt;
    }

    public synchronized String getLastSwitchFrom() {
        return lastSwitchFrom;
    }

    public synchronized String getLastSwitchTo() {
        return lastSwitchTo;
    }

    public synchronized long getLastSwitchAt() {
        return lastSwitchAt;
    }

    public synchronized String getLastSwitchResult() {
        return lastSwitchResult;
    }

    // ------------------------------------------------------------------
    // 状态发布（状态卡片）：调度器在阶段迁移时调用，UI 进程用 readFresh 读
    // ------------------------------------------------------------------

    /**
     * 发布当前状态并立即落盘。参数为 null 表示保持原值；
     * nextUid 传空串表示明确清空（已无下一目标）。
     */
    public synchronized void publishStatus(String phase, String uid, String nextUid, String event) {
        if (phase != null) {
            statusPhase = phase;
        }
        if (uid != null) {
            statusUid = uid;
        }
        if (nextUid != null) {
            statusNextUid = nextUid;
        }
        if (event != null) {
            statusEvent = event;
        }
        statusUpdatedAt = System.currentTimeMillis();
        persist();
    }

    /** 心跳：只刷新更新时间（及顺带校正阶段/当前账号），让 UI 能判断调度器是否还活着 */
    public synchronized void heartbeat(String phase, String uid) {
        if (!StringUtil.isEmpty(phase)) {
            statusPhase = phase;
        }
        if (!StringUtil.isEmpty(uid)) {
            statusUid = uid;
        }
        statusUpdatedAt = System.currentTimeMillis();
        persist();
    }

    public synchronized String getStatusPhase() {
        return statusPhase;
    }

    public synchronized String getStatusUid() {
        return statusUid;
    }

    public synchronized String getStatusNextUid() {
        return statusNextUid;
    }

    public synchronized String getStatusEvent() {
        return statusEvent;
    }

    public synchronized long getStatusUpdatedAt() {
        return statusUpdatedAt;
    }

    /** 从磁盘新鲜读取一份状态（不走单例缓存），供 UI 进程展示状态卡片 */
    public static AccountSwitchState readFresh() {
        return load();
    }

    // ------------------------------------------------------------------
    // 落盘 / 加载
    // ------------------------------------------------------------------

    /** 把当前状态整体写回磁盘；失败只记日志，不抛异常 */
    public synchronized boolean persist() {
        try {
            JSONObject json = new JSONObject();
            json.put("holdEnteredAt", holdEnteredAt);
            json.put("lastScheduleCheckAt", lastScheduleCheckAt);
            JSONArray fired = new JSONArray();
            for (String key : firedSchedulePoints) {
                fired.put(key);
            }
            json.put("firedSchedulePoints", fired);
            JSONObject failures = new JSONObject();
            for (Map.Entry<String, Integer> entry : consecutiveFailures.entrySet()) {
                failures.put(entry.getKey(), entry.getValue());
            }
            json.put("consecutiveFailures", failures);
            JSONObject quarantine = new JSONObject();
            for (Map.Entry<String, Long> entry : quarantinedUntil.entrySet()) {
                JSONObject item = new JSONObject();
                item.put("until", entry.getValue());
                item.put("reason", quarantineReasons.getOrDefault(entry.getKey(), ""));
                quarantine.put(entry.getKey(), item);
            }
            json.put("quarantine", quarantine);
            json.put("globalFailures", globalFailures);
            json.put("switchDisabledUntil", switchDisabledUntil);
            JSONObject last = new JSONObject();
            last.put("from", lastSwitchFrom);
            last.put("to", lastSwitchTo);
            last.put("at", lastSwitchAt);
            last.put("result", lastSwitchResult);
            json.put("lastSwitch", last);
            JSONObject status = new JSONObject();
            status.put("phase", statusPhase);
            status.put("uid", statusUid);
            status.put("nextUid", statusNextUid);
            status.put("event", statusEvent);
            status.put("updatedAt", statusUpdatedAt);
            json.put("status", status);
            return FileUtil.write2File(json.toString(2), getFile());
        } catch (Throwable t) {
            Log.printStackTrace(TAG + "#保存 account_switch_state.json 失败", t);
            return false;
        }
    }

    private static AccountSwitchState load() {
        AccountSwitchState state = new AccountSwitchState();
        try {
            File file = getFile();
            if (!file.exists() || !file.isFile()) {
                return state;
            }
            String text = FileUtil.readFromFile(file);
            if (StringUtil.isEmpty(text)) {
                return state;
            }
            JSONObject json = new JSONObject(text);
            state.holdEnteredAt = json.optLong("holdEnteredAt", 0L);
            state.lastScheduleCheckAt = json.optLong("lastScheduleCheckAt", 0L);
            JSONArray fired = json.optJSONArray("firedSchedulePoints");
            if (fired != null) {
                for (int i = 0; i < fired.length(); i++) {
                    String key = fired.optString(i, "");
                    if (!key.isEmpty()) {
                        state.firedSchedulePoints.add(key);
                    }
                }
            }
            JSONObject failures = json.optJSONObject("consecutiveFailures");
            if (failures != null) {
                Iterator<String> keys = failures.keys();
                while (keys.hasNext()) {
                    String uid = keys.next();
                    int value = failures.optInt(uid, 0);
                    if (value > 0) {
                        state.consecutiveFailures.put(uid, value);
                    }
                }
            }
            JSONObject quarantine = json.optJSONObject("quarantine");
            if (quarantine != null) {
                Iterator<String> keys = quarantine.keys();
                while (keys.hasNext()) {
                    String uid = keys.next();
                    JSONObject item = quarantine.optJSONObject(uid);
                    if (item != null && item.optLong("until", 0L) > 0L) {
                        state.quarantinedUntil.put(uid, item.optLong("until", 0L));
                        state.quarantineReasons.put(uid, item.optString("reason", ""));
                    }
                }
            }
            state.globalFailures = json.optInt("globalFailures", 0);
            state.switchDisabledUntil = json.optLong("switchDisabledUntil", 0L);
            JSONObject last = json.optJSONObject("lastSwitch");
            if (last != null) {
                state.lastSwitchFrom = last.optString("from", "");
                state.lastSwitchTo = last.optString("to", "");
                state.lastSwitchAt = last.optLong("at", 0L);
                state.lastSwitchResult = last.optString("result", "");
            }
            JSONObject status = json.optJSONObject("status");
            if (status != null) {
                state.statusPhase = status.optString("phase", "");
                state.statusUid = status.optString("uid", "");
                state.statusNextUid = status.optString("nextUid", "");
                state.statusEvent = status.optString("event", "");
                state.statusUpdatedAt = status.optLong("updatedAt", 0L);
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG + "#解析 account_switch_state.json 失败，按空白状态恢复", t);
            return new AccountSwitchState();
        }
        return state;
    }
}
