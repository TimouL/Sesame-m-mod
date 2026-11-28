package io.github.aw1y2z.sesame.model.task.magicbox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
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
                p2eDoViewTasks(ExecContext.isForce());
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
     * <p>完成尝试（Attempt）目前只做到（按需）报名 + 悬浮球时长停留：2026-11-28 实测样本已确认
     * 浏览的完成判定靠打开任务自带 jumpLink 后目标小程序的回传，跳转实现接入前，
     * 探针复核不过的任务按 UNABLE 当日停试，次日再看——手动在 App 里做完的任务不受影响，
     * 下一轮见 COMPLETED 照常领奖。样本存档（{@link #dumpTaskListSample}）保留，供后续核对字段变化。
     */
    public static void p2eDoViewTasks(boolean force) {
        try {
            String listResp = MagicBoxRpcCall.p2eQueryTaskList();
            dumpTaskListSample(listResp);
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
                //listField 传 null：游戏中心任务未在自动黑名单里登记列表，policy 只做标记与分级，不代拉黑
                TaskAttemptPolicy.Site site = TaskAttemptPolicy.site(null, "赚现金任务",
                        p2eTaskType(task), null, MagicBox::probeP2eTask);
                if ("COMPLETED".equals(status)) {
                    TaskAttemptPolicy.handle(P2E_KEY_PREFIX + taskId, title,
                            () -> p2eReceiveTask(task), null, Log::magicBox, site);
                }
                else if ("UN_SIGNUP".equals(status) || "SIGNUP_COMPLETED".equals(status)) {
                    if (isTransactionTitle(title)) {
                        //policy 的交易识别只认 taskType 里的拼音关键词，中文标题在这里兜一层：永不报名
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
        }
        catch (Throwable t) {
            Log.err(TAG, "p2eDoViewTasks err:", t);
        }
    }

    /**
     * 平台任务完成尝试：报名只在任务自带 needSignUp=true 时才做（与 AntMember 游戏中心任务、
     * AntSports 的既有约定一致，字段可能缺失故用 optBoolean 兜底；2026-11-28 实测样本里
     * 10 条平台任务 needSignUp 全为 false，即正常情况下不报名、直接走完成动作）。
     * 报名失败按临时故障 RETRY（policy 当日最多再试 3 次，不再像旧逻辑那样失败一次烧掉一整天）；
     * 报名后（或本就已报名）按任务下发的 floatingBall.timeSeconds 停留一次。
     * 真正的完成动作（打开 jumpLink 真实浏览）接入前统一返回 UNABLE——
     * policy 仍会重拉列表探一次，真完成了照样认账，未完成则当日停试、次日再看。
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
            return TaskAttemptPolicy.Outcome.UNABLE;
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
                Log.magicBox("游戏中心🪙逛一逛[" + title + "]#获得[" + data.optInt("coinAmount", 0) + "金币]");
                return true;
            }
            Log.magicBox("游戏中心🪙逛一逛[" + title + "]#领取未确认");
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
