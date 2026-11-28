package io.github.aw1y2z.sesame.model.task.magicbox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.aw1y2z.sesame.data.ConfigV2;
import io.github.aw1y2z.sesame.data.MagicBoxP2eStats;
import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectModelField;
import io.github.aw1y2z.sesame.entity.AlipayMagicBoxP2eTaskList;
import io.github.aw1y2z.sesame.util.idMap.MagicBoxP2eTaskListMap;
import io.github.aw1y2z.sesame.data.task.ExecContext;
import io.github.aw1y2z.sesame.data.task.ExecUnit;
import io.github.aw1y2z.sesame.data.task.ModelTask;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy;
import io.github.aw1y2z.sesame.model.base.TaskCommon;
import io.github.aw1y2z.sesame.util.FileUtil;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.TimeUtil;

/**
 * 魔盒：承载外部 HTTP 型活动（业务不经 mobilegw RPC 的活动）。
 * 各功能独立成静态方法，后期云任务下发只需扩展 run() 的调度。
 */
public class MagicBox extends ModelTask {

    private static final String TAG = "MagicBox";

    private BooleanModelField gameCenterP2ESignIn;
    private BooleanModelField gameCenterP2ETask;
    private BooleanModelField gameCenterP2EGameBrowse;
    private BooleanModelField AutoMagicBoxP2eTaskList;
    private SelectModelField MagicBoxP2eTaskList;
    private BooleanModelField chongyaSignIn;

    @Override
    public String getName() {
        return "魔盒";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.MAGIC_BOX;
    }

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(gameCenterP2ESignIn = new BooleanModelField("gameCenterP2ESignIn", "游戏中心 | 赚现金签到", false));
        modelFields.addField(gameCenterP2ETask = new BooleanModelField("gameCenterP2ETask", "游戏中心 | 赚现金任务", false));
        modelFields.addField(gameCenterP2EGameBrowse = new BooleanModelField("gameCenterP2EGameBrowse", "游戏中心 | 赚现金游戏时长任务", true).setDependsOn("gameCenterP2ETask"));
        modelFields.addField(AutoMagicBoxP2eTaskList = new BooleanModelField("AutoMagicBoxP2eTaskList", "游戏中心 | 赚现金自动黑名单", true).setDependsOn("gameCenterP2ETask"));
        modelFields.addField(MagicBoxP2eTaskList = new SelectModelField("MagicBoxP2eTaskList", "游戏中心 | 赚现金黑名单列表", new LinkedHashSet<>(), AlipayMagicBoxP2eTaskList::getList).setDependsOn("AutoMagicBoxP2eTaskList"));
        modelFields.addField(chongyaSignIn = new BooleanModelField("chongyaSignIn", "冲鸭攒话费 | 签到", false));
        return modelFields;
    }

    @Override
    public List<ExecUnit> getExecUnits() {
        List<ExecUnit> units = new ArrayList<>();
        units.add(new ExecUnit("gameCenterP2ESignIn", "赚现金签到", "游戏中心赚现金：每日签到领金币"));
        units.add(new ExecUnit("gameCenterP2ETask", "赚现金任务", "游戏中心赚现金：逛一逛任务报名与领奖"));
        units.add(new ExecUnit("chongyaSignIn", "冲鸭攒话费签到", "冲鸭攒话费小程序：每日签到领充值金"));
        return units;
    }

    @Override
    public Boolean check() {
        if (TaskCommon.IS_ENERGY_TIME) {
            Log.i("任务暂停⏸️魔盒:当前为仅收能量时间");
            return false;
        }
        return true;
    }

    @Override
    public void run() {
        try {
            if (gameCenterP2ESignIn.getValue() && ExecContext.isSelected("gameCenterP2ESignIn")) {
                p2eCheckAndSignIn(ExecContext.isForce());
            }
            if (gameCenterP2ETask.getValue() && ExecContext.isSelected("gameCenterP2ETask")) {
                p2eDoViewTasks(ExecContext.isForce(), gameCenterP2EGameBrowse.getValue(), AutoMagicBoxP2eTaskList.getValue());
            }
            if (chongyaSignIn.getValue() && ExecContext.isSelected("chongyaSignIn")) {
                ChongYaSign.checkAndSignIn(ExecContext.isForce());
            }
        }
        catch (Throwable t) {
            Log.err(TAG, "run err:", t);
        }
    }

    /**
     * 赚现金（P2E）累计签到：首页今日记录 signUpStatus=INIT 才签；
     * signIn 响应无数据，入账以重拉首页的今日记录为准，
     * 今日已签或签后复核通过都置当日标记，复核未确认的留给下轮重查
     */
    public static void p2eCheckAndSignIn(boolean force) {
        if (!force && Status.hasFlagToday("gameCenterP2eSignIn")) {
            return;
        }
        try {
            JSONObject homeJo = new JSONObject(MagicBoxRpcCall.p2eQueryHomePage());
            if (!MessageUtil.checkSuccess(TAG, homeJo)) {
                return;
            }
            JSONObject homeData = homeJo.optJSONObject("data");
            JSONObject signUpModule = homeData == null ? null : homeData.optJSONObject("signUpModuleVO");
            if (signUpModule == null) {
                return;
            }
            JSONObject todayRecord = findTodaySignUpRecord(signUpModule);
            if (todayRecord == null) {
                return;
            }
            if (!"INIT".equals(todayRecord.optString("signUpStatus", ""))) {
                //今日已签，后续轮次不再重查
                Status.flagToday("gameCenterP2eSignIn");
                MagicBoxP2eStats.markSignIn(todayRecord.optString("todayGoldCoinAmount", ""));
                return;
            }
            String todayCoin = todayRecord.optString("todayGoldCoinAmount", "").trim();

            String signInResp = MagicBoxRpcCall.p2eSignIn(
                    signUpModule.optString("date", ""),
                    signUpModule.optInt("index", 0),
                    signUpModule.optString("signSequenceId", ""));
            if (signInResp == null || signInResp.isEmpty()) {
                return;
            }
            JSONObject signInJo = new JSONObject(signInResp);
            if (!MessageUtil.checkSuccess(TAG, signInJo)) {
                return;
            }

            //复核：今日记录翻转为 SIGNED 才计成功，奖励金额取今日记录里的 todayGoldCoinAmount
            JSONObject afterJo = new JSONObject(MagicBoxRpcCall.p2eQueryHomePage());
            if (!MessageUtil.checkSuccess(TAG, afterJo)) {
                Log.magicBox("游戏中心🪙赚现金签到已提交#入账待下轮复核");
                return;
            }
            JSONObject afterData = afterJo.optJSONObject("data");
            JSONObject afterModule = afterData == null ? null : afterData.optJSONObject("signUpModuleVO");
            JSONObject afterToday = afterModule == null ? null : findTodaySignUpRecord(afterModule);
            if (afterToday != null && "SIGNED".equals(afterToday.optString("signUpStatus", ""))) {
                if (todayCoin.isEmpty()) {
                    todayCoin = afterToday.optString("todayGoldCoinAmount", "").trim();
                }
                Log.magicBox("游戏中心🪙赚现金签到#获得[" + (todayCoin.isEmpty() ? "?" : todayCoin) + "金币]");
                MagicBoxP2eStats.markSignIn(todayCoin);
                Status.flagToday("gameCenterP2eSignIn");
            }
            else {
                Log.magicBox("游戏中心🪙赚现金签到未确认#今日记录["
                        + (afterToday == null ? "无" : afterToday.optString("signUpStatus", "")) + "]");
            }
        }
        catch (Throwable t) {
            Log.err(TAG, "p2eCheckAndSignIn err:", t);
        }
    }

    /** 从 signUpModuleVO.signRecordVOList 里取 isToday 的签到记录 */
    private static JSONObject findTodaySignUpRecord(JSONObject signUpModule) {
        JSONArray records = signUpModule.optJSONArray("signRecordVOList");
        if (records == null) {
            return null;
        }
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record != null && record.optBoolean("isToday", false)) {
                return record;
            }
        }
        return null;
    }

    /** 平台任务在 TaskAttemptPolicy 里的定位键前缀，实际键为 {@code gameCenterP2e::<taskId>} */
    private static final String P2E_KEY_PREFIX = "gameCenterP2e::";

    /**
     * 赚现金（P2E）「逛一逛/浏览15s」平台任务：服务端任务列表型，统一走 {@link TaskAttemptPolicy}——
     * 每轮按列表状态逐条分派（COMPLETED 走领奖、UN_SIGNUP / SIGNUP_COMPLETED 走完成尝试），
     * 完成与领奖一律以重拉列表的探针复核为准，不信任动作响应；
     * 当日只试一次、临时故障有限重试、交易类不申报都由 policy 管，本方法不再自设当日标记。
     * <p>注意：{@code force} 只表示「立即跑一轮」，尝试节奏由 policy 的当日标记治理，强制执行不绕过。
     * <p>完成尝试（Attempt）：（按需）报名 → 按悬浮球时长停留 → platformTaskComplete 回传
     * （2026-11-28 真机抓包实证的客户端原生完成接口，与报名同构参数，后台直调、探针复核）。
     * 跳外部 App 的 SWITCH_APP_TASK 不回传（完成判定在外部 App 内），当日跳过；
     * 手动在 App 里做完的任务不受影响，下一轮见 COMPLETED 照常领奖。
     * 样本存档（{@link #dumpTaskListSample}）保留，供后续核对字段变化。
     */
    public static void p2eDoViewTasks(boolean force, boolean gameBrowseEnabled, boolean autoBlackList) {
        try {
            String listResp = MagicBoxRpcCall.p2eQueryTaskList();
            dumpTaskListSample(listResp);
            syncP2eTaskListMap(listResp, autoBlackList);
            JSONArray taskList = p2ePlatformTaskList(listResp);
            if (taskList == null) {
                return;
            }
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = taskList.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                String taskId = task.optString("taskId", "").trim();
                if (taskId.isEmpty()) {
                    continue;
                }
                String title = task.optString("title", taskId);
                String status = task.optString("taskStatus", "");
                //listField 已在 MessageUtil 登记（MagicBoxP2eTaskList）：policy 的自动黑名单记账生效
                TaskAttemptPolicy.Site site = TaskAttemptPolicy.site("MagicBoxP2eTaskList", "赚现金任务",
                        p2eTaskType(task), null, MagicBox::probeP2eTask);
                if ("COMPLETED".equals(status)) {
                    TaskAttemptPolicy.handle(P2E_KEY_PREFIX + taskId, title,
                            () -> p2eReceiveTask(task), null, Log::magicBox, site);
                }
                else if ("UN_SIGNUP".equals(status) || "SIGNUP_COMPLETED".equals(status)) {
                    if (isP2eBlackListed(title)) {
                        //黑名单只挡「做」不挡「领」：与农场一致，命中静默跳过
                        continue;
                    }
                    if ("SWITCH_APP_TASK".equals(task.optString("actionType", ""))) {
                        //跳外部 App 完成（如去快手）：回传接口管不到，当日跳过、次日再看
                        if (!Status.hasFlagToday("gameCenterP2eTaskTried::" + taskId)) {
                            Status.flagToday("gameCenterP2eTaskTried::" + taskId);
                            Log.magicBox("游戏中心🪙逛一逛[" + title + "]#需跳外部App，跳过");
                        }
                        continue;
                    }
                    if (!"VIEW_TASK".equals(task.optString("actionType", "")) && isTransactionTitle(title)) {
                        //policy 的交易识别只认 taskType 里的拼音关键词，中文标题在这里兜一层：永不报名。
                        //浏览类（VIEW_TASK）豁免：标题含「支付/消费」字样的浏览任务（如逛一逛支付有礼）不是交易任务
                        if (!Status.hasFlagToday("gameCenterP2eTaskTried::" + taskId)) {
                            Status.flagToday("gameCenterP2eTaskTried::" + taskId);
                            Log.magicBox("游戏中心🪙逛一逛[" + title + "]#交易/履约类，不报名");
                        }
                        continue;
                    }
                    TaskAttemptPolicy.handle(P2E_KEY_PREFIX + taskId, title,
                            null, () -> p2eAttemptPlatformTask(task, status), Log::magicBox, site);
                }
                //其余状态（领奖后残留等）不处理
            }
            p2eDoGameTasks(listResp, gameBrowseEnabled);
            p2eSyncStatsFromServer();
        }
        catch (Throwable t) {
            Log.err(TAG, "p2eDoViewTasks err:", t);
        }
    }

    /**
     * 当日统计与服务器对齐：分页拉金币流水（最新在前），只取今日 FLOW_IN 并按文案分类——
     * 「每日签到」计签到、「…浏览任务」计任务、「玩…秒」计游戏时长——扫全今日记录后整表覆盖本地累计；
     * 中途失败/今日记录没扫全则保留本地累计不动（2026-11-28 抓包实证流水结构）。
     */
    private static void p2eSyncStatsFromServer() {
        try {
            String today = MagicBoxP2eStats.today();
            boolean signInDone = false;
            int signInCoins = 0;
            int taskCoins = 0;
            int gameCoins = 0;
            boolean complete = false;
            for (int page = 1; page <= 5 && !complete; page++) {
                String resp = MagicBoxRpcCall.p2eQueryGoldRecord(page, 20);
                if (resp == null || resp.isEmpty()) {
                    return;
                }
                JSONObject jo = new JSONObject(resp);
                if (!MessageUtil.checkSuccess(TAG, jo)) {
                    return;
                }
                JSONObject data = jo.optJSONObject("data");
                JSONArray records = data == null ? null : data.optJSONArray("goldRecords");
                if (records == null) {
                    return;
                }
                boolean stop = false;
                for (int i = 0; i < records.length(); i++) {
                    JSONObject record = records.optJSONObject(i);
                    if (record == null) {
                        continue;
                    }
                    String transTime = record.optString("transTime", "");
                    String recordDate = transTime.length() >= 10 ? transTime.substring(0, 10) : "";
                    if (recordDate.compareTo(today) < 0) {
                        //已扫过今日全部记录
                        stop = true;
                        complete = true;
                        break;
                    }
                    if (!recordDate.equals(today) || !"FLOW_IN".equals(record.optString("transType", ""))) {
                        continue;
                    }
                    String desc = record.optString("desc", "");
                    int amount = record.optInt("goldAmount", 0);
                    if ("每日签到".equals(desc)) {
                        signInDone = true;
                        signInCoins += amount;
                    }
                    else if (desc.contains("浏览任务")) {
                        taskCoins += amount;
                    }
                    else if (desc.startsWith("玩") && desc.contains("秒")) {
                        gameCoins += amount;
                    }
                }
                if (stop) {
                    break;
                }
                if (data == null || !data.optBoolean("hasMore", false)) {
                    complete = true;
                }
            }
            if (complete) {
                MagicBoxP2eStats.alignFromServer(signInDone, signInCoins, taskCoins, gameCoins);
            }
        }
        catch (Throwable t) {
            Log.err(TAG, "p2eSyncStatsFromServer err:", t);
        }
    }

    /** 游戏任务在 TaskAttemptPolicy 里的定位键前缀，实际键为 {@code gameCenterP2eGame::<taskId>} */
    private static final String P2E_GAME_KEY_PREFIX = "gameCenterP2eGame::";

    /**
     * 赚现金游戏任务（gameTaskList）：与平台任务分开处理——
     * COMPLETED 的统一走领奖（领奖接口与平台任务共用 gameP2eTaskReceive，taskType 区分）；
     * GAME_BROWSE（LIGHT_TRAN_TASK，如几何王国「玩60秒」）做实验性完成尝试：
     * 悬浮球登记（gameP2eFloatingBallConsult，2026-11-28 抓包实证的客户端原生登记调用）
     * → 按登记响应下发的时长等待 → policy 探针重拉列表复核，翻 COMPLETED 才算成、下一轮领奖。
     * 若服务端实际按游戏引擎上报计时，完成不会翻转，实验自动落空、无副作用（登记本身只是登记）。
     * 其余 GAME_TRAN_TASK / BONUS 是真实游戏进度（过关/订单/推图），不尝试。
     */
    public static void p2eDoGameTasks(String listResp, boolean gameBrowseEnabled) {
        try {
            JSONArray gameList = p2eGameTaskList(listResp);
            if (gameList == null) {
                return;
            }
            String gameModuleId = p2eGameModuleId(listResp);
            for (int i = 0; i < gameList.length(); i++) {
                JSONObject task = gameList.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                String taskId = task.optString("taskId", "").trim();
                if (taskId.isEmpty()) {
                    continue;
                }
                String title = task.optString("title", taskId);
                String status = task.optString("taskStatus", "");
                TaskAttemptPolicy.Site site = TaskAttemptPolicy.site("MagicBoxP2eTaskList", "赚现金游戏任务",
                        p2eTaskType(task), null, MagicBox::probeP2eGameTask);
                if ("COMPLETED".equals(status)) {
                    TaskAttemptPolicy.handle(P2E_GAME_KEY_PREFIX + taskId, title,
                            () -> p2eReceiveTask(task, "玩一玩"), null, Log::magicBox, site);
                }
                else if (gameBrowseEnabled && !isP2eBlackListed(title)
                        && ("SIGNUP_COMPLETED".equals(status) || "UN_SIGNUP".equals(status))
                        && "GAME_BROWSE".equals(task.optString("actionType", ""))) {
                    TaskAttemptPolicy.handle(P2E_GAME_KEY_PREFIX + taskId, title,
                            null, () -> p2eAttemptGameBrowseTask(task, gameModuleId, status), Log::magicBox, site);
                }
                //其余游戏任务为真实进度型，不处理
            }
        }
        catch (Throwable t) {
            Log.err(TAG, "p2eDoGameTasks err:", t);
        }
    }

    /**
     * 游戏浏览任务完成实验：登记悬浮球 → 等满登记响应下发的时长 → 返回 DONE 交 policy 探针复核。
     * UN_SIGNUP 且任务要求报名时不试（游戏任务的报名接口未知）；登记被服务端明确拒绝返回 UNABLE。
     */
    private static TaskAttemptPolicy.Outcome p2eAttemptGameBrowseTask(JSONObject task, String gameModuleId, String statusAtList) {
        String title = task.optString("title", task.optString("taskId", ""));
        try {
            if ("UN_SIGNUP".equals(statusAtList) && task.optBoolean("needSignUp", false)) {
                Log.magicBox("游戏中心🪙玩一玩[" + title + "]#未报名且报名接口未知，跳过（实验）");
                return TaskAttemptPolicy.Outcome.UNABLE;
            }
            String gameId = task.optString("gameId", "");
            if (gameId.isEmpty() || gameModuleId == null || gameModuleId.isEmpty()) {
                Log.magicBox("游戏中心🪙玩一玩[" + title + "]#缺 gameId/gameModuleId，跳过（实验）");
                return TaskAttemptPolicy.Outcome.UNABLE;
            }
            String consultResp = MagicBoxRpcCall.p2eFloatingBallConsult(gameId, gameModuleId);
            if (consultResp == null || consultResp.isEmpty()) {
                return TaskAttemptPolicy.Outcome.RETRY;
            }
            JSONObject jo = new JSONObject(consultResp);
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                Log.magicBox("游戏中心🪙玩一玩[" + title + "]#悬浮球登记被拒（实验）");
                return TaskAttemptPolicy.Outcome.UNABLE;
            }
            int waitSeconds = 60;
            JSONObject data = jo.optJSONObject("data");
            JSONObject ball = data == null ? null : data.optJSONObject("floatingBallVO");
            int s = ball == null ? 0 : ball.optInt("timeSeconds", 0);
            if (s > 0 && s <= 300) {
                waitSeconds = s;
            }
            Log.magicBox("游戏中心🪙玩一玩[" + title + "]#已登记悬浮球，等待" + waitSeconds + "秒（实验）");
            TimeUtil.sleep((waitSeconds + 1) * 1000L);
            //实验二：登记+等待已被证实不够——服务器认的是游戏引擎的时长上报，
            //这里代发一条 submitUserPlayDurationAction（参数与引擎实发一致，拆包实证），再由探针复核
            String gameAppId = task.optString("appId", "");
            if (gameAppId.isEmpty()) {
                Log.magicBox("游戏中心🪙玩一玩[" + title + "]#缺 appId，无法上报时长（实验二）");
                return TaskAttemptPolicy.Outcome.UNABLE;
            }
            String reportResp = MagicBoxRpcCall.p2eSubmitPlayDuration(
                    chInfoOf(task.optString("jumpLink", "")), waitSeconds, gameAppId);
            if (reportResp == null || reportResp.isEmpty()) {
                return TaskAttemptPolicy.Outcome.RETRY;
            }
            JSONObject reportJo = new JSONObject(reportResp);
            if (!MessageUtil.checkSuccess(TAG, reportJo)) {
                Log.magicBox("游戏中心🪙玩一玩[" + title + "]#时长上报被拒（实验二）");
                return TaskAttemptPolicy.Outcome.UNABLE;
            }
            Log.magicBox("游戏中心🪙玩一玩[" + title + "]#已上报游玩时长" + waitSeconds + "秒（实验二）");
            return TaskAttemptPolicy.Outcome.DONE;
        }
        catch (Throwable t) {
            Log.err(TAG, "p2eAttemptGameBrowseTask err:", t);
            return TaskAttemptPolicy.Outcome.RETRY;
        }
    }

    /** 从任务 jumpLink 里取 chInfo（游戏以任务入口渠道启动时引擎上报时长的 source）；取不到用游戏中心默认渠道 */
    private static String chInfoOf(String jumpLink) {
        if (jumpLink != null) {
            int i = jumpLink.indexOf("chInfo=");
            if (i >= 0) {
                String rest = jumpLink.substring(i + "chInfo=".length());
                int amp = rest.indexOf('&');
                String value = (amp >= 0 ? rest.substring(0, amp) : rest).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return "ch_appcenter__chsub_9patch";
    }

    /** 游戏任务列表状态探针：重拉列表在 gameTaskList 里按 taskId 查状态，映射同平台探针 */
    private static TaskAttemptPolicy.ProbeResult probeP2eGameTask(String key) {
        String taskId = key.startsWith(P2E_GAME_KEY_PREFIX) ? key.substring(P2E_GAME_KEY_PREFIX.length()) : key;
        try {
            String resp = MagicBoxRpcCall.p2eQueryTaskList();
            if (resp == null || resp.isEmpty()) {
                return TaskAttemptPolicy.ProbeResult.UNKNOWN;
            }
            JSONArray list = p2eGameTaskList(resp);
            if (list == null) {
                return TaskAttemptPolicy.ProbeResult.UNKNOWN;
            }
            JSONObject task = findPlatformTaskById(list, taskId);
            if (task == null) {
                return TaskAttemptPolicy.ProbeResult.GONE;
            }
            return "COMPLETED".equals(task.optString("taskStatus", ""))
                    ? TaskAttemptPolicy.ProbeResult.FINISHED : TaskAttemptPolicy.ProbeResult.TODO;
        }
        catch (Throwable t) {
            Log.err(TAG, "probeP2eGameTask err:", t);
            return TaskAttemptPolicy.ProbeResult.UNKNOWN;
        }
    }

    /** 从 queryTaskList 响应取 data.platformGameTaskModule.gameTaskList，异常返回 null，无模块返回空列表 */
    private static JSONArray p2eGameTaskList(String resp) {
        if (resp == null || resp.isEmpty()) {
            return null;
        }
        try {
            JSONObject data = new JSONObject(resp).optJSONObject("data");
            JSONObject module = data == null ? null : data.optJSONObject("platformGameTaskModule");
            JSONArray list = module == null ? null : module.optJSONArray("gameTaskList");
            return list == null ? new JSONArray() : list;
        }
        catch (Throwable t) {
            Log.err(TAG, "p2eGameTaskList err:", t);
            return null;
        }
    }

    /** 从 queryTaskList 响应取 platformGameTaskModule.gameModuleId（悬浮球登记入参），取不到返回空串 */
    private static String p2eGameModuleId(String resp) {
        if (resp == null || resp.isEmpty()) {
            return "";
        }
        try {
            JSONObject data = new JSONObject(resp).optJSONObject("data");
            JSONObject module = data == null ? null : data.optJSONObject("platformGameTaskModule");
            return module == null ? "" : module.optString("gameModuleId", "");
        }
        catch (Throwable t) {
            Log.err(TAG, "p2eGameModuleId err:", t);
            return "";
        }
    }

    /**
     * 平台任务完成尝试：报名只在任务自带 needSignUp=true 时才做（与 AntMember 游戏中心任务、
     * AntSports 的既有约定一致，字段可能缺失故用 optBoolean 兜底；2026-11-28 实测样本里
     * 10 条平台任务 needSignUp 全为 false，即正常情况下不报名、直接走完成动作）。
     * 报名失败按临时故障 RETRY（policy 当日最多再试 3 次，不再像旧逻辑那样失败一次烧掉一整天）；
     * 报名后（或本就已报名）先按任务下发的 floatingBall.timeSeconds 停留（模拟悬浮球计时，
     * 服务端可能按报名/曝光后的间隔校验），再调 platformTaskComplete 回传完成——
     * 该接口与参数系 2026-11-28 真机抓包实证（客户端悬浮球走完后原样调用，响应 success）。
     * 回传被服务端明确拒绝返回 UNABLE（policy 探一次认账/停试），网络级失败返回 RETRY。
     */
    private static TaskAttemptPolicy.Outcome p2eAttemptPlatformTask(JSONObject task, String statusAtList) {
        String taskId = task.optString("taskId", "").trim();
        String title = task.optString("title", taskId);
        try {
            if ("UN_SIGNUP".equals(statusAtList) && task.optBoolean("needSignUp", false)) {
                String resp = MagicBoxRpcCall.p2ePlatformTaskSignUp(taskId, task.optString("taskToken", ""));
                if (resp == null || resp.isEmpty() || !MessageUtil.checkSuccess(TAG, new JSONObject(resp))) {
                    return TaskAttemptPolicy.Outcome.RETRY;
                }
                Log.magicBox("游戏中心🪙逛一逛[" + title + "]#已报名");
            }
            if ("UN_SIGNUP".equals(statusAtList) || "SIGNUP_COMPLETED".equals(statusAtList)) {
                int waitSeconds = 15;
                JSONObject ball = task.optJSONObject("floatingBall");
                int s = ball == null ? 0 : ball.optInt("timeSeconds", 0);
                if (s > 0 && s <= 300) {
                    waitSeconds = s;
                }
                TimeUtil.sleep((waitSeconds + 1) * 1000L);
            }
            String completeResp = MagicBoxRpcCall.p2ePlatformTaskComplete(taskId, task.optString("taskToken", ""));
            if (completeResp == null || completeResp.isEmpty()) {
                return TaskAttemptPolicy.Outcome.RETRY;
            }
            if (!MessageUtil.checkSuccess(TAG, new JSONObject(completeResp))) {
                return TaskAttemptPolicy.Outcome.UNABLE;
            }
            Log.magicBox("游戏中心🪙逛一逛[" + title + "]#已回传完成");
            return TaskAttemptPolicy.Outcome.DONE;
        }
        catch (Throwable t) {
            Log.err(TAG, "p2eAttemptPlatformTask err:", t);
            return TaskAttemptPolicy.Outcome.RETRY;
        }
    }

    /**
     * 列表状态探针（供 TaskAttemptPolicy 复核）：重拉任务列表按 taskId 查状态，
     * COMPLETED 映射 FINISHED、查不到映射 GONE（领后从列表消失）、其余映射 TODO；
     * 响应不可用映射 UNKNOWN，调用方据此不做判定。不能用 ProbeResult.of——它不认 COMPLETED。
     */
    private static TaskAttemptPolicy.ProbeResult probeP2eTask(String key) {
        String taskId = key.startsWith(P2E_KEY_PREFIX) ? key.substring(P2E_KEY_PREFIX.length()) : key;
        try {
            String resp = MagicBoxRpcCall.p2eQueryTaskList();
            if (resp == null || resp.isEmpty()) {
                return TaskAttemptPolicy.ProbeResult.UNKNOWN;
            }
            JSONObject data = new JSONObject(resp).optJSONObject("data");
            JSONObject module = data == null ? null : data.optJSONObject("platformGameTaskModule");
            JSONArray list = module == null ? null : module.optJSONArray("platformTaskList");
            if (list == null) {
                return TaskAttemptPolicy.ProbeResult.UNKNOWN;
            }
            JSONObject task = findPlatformTaskById(list, taskId);
            if (task == null) {
                return TaskAttemptPolicy.ProbeResult.GONE;
            }
            return "COMPLETED".equals(task.optString("taskStatus", ""))
                    ? TaskAttemptPolicy.ProbeResult.FINISHED : TaskAttemptPolicy.ProbeResult.TODO;
        }
        catch (Throwable t) {
            Log.err(TAG, "probeP2eTask err:", t);
            return TaskAttemptPolicy.ProbeResult.UNKNOWN;
        }
    }

    /**
     * 任务列表原始响应每日存档一份到日志目录（p2e_tasklist_sample.json），
     * 供确认任务对象字段（taskType / floatingBall / 跳转参数）以定完成动作实现；拉日志时一并取回。
     */
    private static void dumpTaskListSample(String resp) {
        if (resp == null || resp.isEmpty() || Status.hasFlagToday("gameCenterP2eTaskListSample")) {
            return;
        }
        try {
            File file = new File(FileUtil.LOG_DIRECTORY_FILE, "p2e_tasklist_sample.json");
            if (FileUtil.write2File(resp, file)) {
                Status.flagToday("gameCenterP2eTaskListSample");
                Log.magicBox("游戏中心🪙赚现金任务列表样本已存档#[" + resp.length() + "字节]");
            }
        }
        catch (Throwable t) {
            Log.err(TAG, "dumpTaskListSample err:", t);
        }
    }

    /** 任务类型：领奖与 policy 的 bizKey 共用同一取值，缺省与领奖侧一致 */
    private static String p2eTaskType(JSONObject task) {
        String taskType = task.optString("taskType", "").trim();
        return taskType.isEmpty() ? "PLATFORM_TRAN_TASK" : taskType;
    }

    /** 中文标题里的交易/履约类关键词（policy 只识别 taskType 拼音关键词，这里补标题一层） */
    private static boolean isTransactionTitle(String title) {
        return title.contains("充值") || title.contains("支付") || title.contains("购买")
                || title.contains("消费") || title.contains("缴费");
    }

    /** 领取单个平台任务奖励，成功返回 true */
    private static boolean p2eReceiveTask(JSONObject task) {
        return p2eReceiveTask(task, "逛一逛");
    }

    /** 领取单个任务奖励（平台/游戏共用同一领奖接口，kind 只区分日志措辞），成功返回 true */
    private static boolean p2eReceiveTask(JSONObject task, String kind) {
        String taskId = task.optString("taskId", "").trim();
        String title = task.optString("title", taskId);
        try {
            String resp = MagicBoxRpcCall.p2eGameTaskReceive(taskId, task.optString("taskToken", ""),
                    p2eTaskType(task));
            if (resp == null || resp.isEmpty()) {
                return false;
            }
            JSONObject jo = new JSONObject(resp);
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return false;
            }
            JSONObject data = jo.optJSONObject("data");
            if (data != null && data.optBoolean("receiveSuccess", false)) {
                Log.magicBox("游戏中心🪙" + kind + "[" + title + "]#获得[" + data.optInt("coinAmount", 0) + "金币]");
                MagicBoxP2eStats.addReceive(kind, data.optInt("coinAmount", 0));
                return true;
            }
            Log.magicBox("游戏中心🪙" + kind + "[" + title + "]#领取未确认");
            return false;
        }
        catch (Throwable t) {
            Log.err(TAG, "p2eReceiveTask err:", t);
            return false;
        }
    }

    /** 从 queryTaskList 响应取 data.platformGameTaskModule.platformTaskList，异常返回 null，无模块返回空列表 */
    private static JSONArray p2ePlatformTaskList(String resp) {
        if (resp == null || resp.isEmpty()) {
            return null;
        }
        try {
            JSONObject data = new JSONObject(resp).optJSONObject("data");
            JSONObject module = data == null ? null : data.optJSONObject("platformGameTaskModule");
            JSONArray list = module == null ? null : module.optJSONArray("platformTaskList");
            return list == null ? new JSONArray() : list;
        }
        catch (Throwable t) {
            Log.err(TAG, "p2ePlatformTaskList err:", t);
            return null;
        }
    }

    /**
     * 每日一次把当日任务标题（平台 + 游戏两份列表）同步进 MagicBoxP2eTaskListMap，
     * 供黑名单列表的选项来源（与农场 initAntFarmTaskListMap 同构，只是挂在任务运行路径上）；
     * 自动黑名单开启时再把预置项同步进黑名单字段（本模块暂无预置项，登记表为空集）。
     */
    private static void syncP2eTaskListMap(String listResp, boolean autoBlackList) {
        if (Status.hasFlagToday("gameCenterP2eTaskListMapSynced")) {
            return;
        }
        try {
            MagicBoxP2eTaskListMap.load();
            JSONArray[] lists = {p2ePlatformTaskList(listResp), p2eGameTaskList(listResp)};
            for (JSONArray list : lists) {
                if (list == null) {
                    continue;
                }
                for (int i = 0; i < list.length(); i++) {
                    JSONObject task = list.optJSONObject(i);
                    if (task == null) {
                        continue;
                    }
                    String title = task.optString("title", "").trim();
                    if (!title.isEmpty()) {
                        MagicBoxP2eTaskListMap.add(title, title);
                    }
                }
            }
            MagicBoxP2eTaskListMap.save();
            Status.flagToday("gameCenterP2eTaskListMapSynced");
            if (autoBlackList) {
                MessageUtil.syncTaskBlackList("魔盒赚现金任务", "MagicBoxP2eTaskList",
                        MessageUtil.presetBlackList("MagicBox", "MagicBoxP2eTaskList"),
                        new HashSet<>(), p2eBlackListField());
            }
        }
        catch (Throwable t) {
            Log.err(TAG, "syncP2eTaskListMap err:", t);
        }
    }

    /** 从 ConfigV2 取本模块的黑名单列表字段（静态执行路径没有模块实例，与农场 init 的取法一致） */
    private static SelectModelField p2eBlackListField() {
        try {
            ModelFields fields = ConfigV2.INSTANCE.getModelFieldsMap().get("MagicBox");
            if (fields == null) {
                return null;
            }
            Object field = fields.get("MagicBoxP2eTaskList");
            return field instanceof SelectModelField ? (SelectModelField) field : null;
        }
        catch (Throwable t) {
            Log.err(TAG, "p2eBlackListField err:", t);
            return null;
        }
    }

    /** 标题是否在用户/自动黑名单里（字段值即标题集合，与农场的判定方式一致） */
    private static boolean isP2eBlackListed(String title) {
        SelectModelField field = p2eBlackListField();
        if (field == null) {
            return false;
        }
        Set<String> values = field.getValue();
        return values != null && values.contains(title);
    }

    /** 按 taskId 在平台任务列表里找任务 */
    private static JSONObject findPlatformTaskById(JSONArray taskList, String taskId) {
        for (int i = 0; i < taskList.length(); i++) {
            JSONObject task = taskList.optJSONObject(i);
            if (task != null && taskId.equals(task.optString("taskId", "").trim())) {
                return task;
            }
        }
        return null;
    }
}
