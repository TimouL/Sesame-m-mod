package io.github.aw1y2z.sesame.model.task.magicbox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.data.task.ExecContext;
import io.github.aw1y2z.sesame.data.task.ExecUnit;
import io.github.aw1y2z.sesame.data.task.ModelTask;
import io.github.aw1y2z.sesame.model.base.TaskCommon;
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

    /**
     * 赚现金（P2E）「逛一逛/浏览15s」平台任务：先领已完成（COMPLETED）未领的奖励，再报名未报名任务；
     * 浏览依赖目标小程序真实访问，服务端无对应上报接口可代调，
     * 报名后等待浏览窗口重查一次，服务端判完成则领取，仍未完成按真实浏览类当日跳过
     */
    public static void p2eDoViewTasks(boolean force) {
        try {
            JSONArray taskList = p2ePlatformTaskList(MagicBoxRpcCall.p2eQueryTaskList());
            if (taskList == null) {
                return;
            }
            //领取已完成未领的奖励
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = taskList.optJSONObject(i);
                if (task != null && "COMPLETED".equals(task.optString("taskStatus", ""))) {
                    p2eReceiveTask(task);
                }
            }
            //报名未报名的任务，每任务每日一次尝试
            List<JSONObject> justSigned = new ArrayList<>();
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = taskList.optJSONObject(i);
                if (task == null || !"UN_SIGNUP".equals(task.optString("taskStatus", ""))) {
                    continue;
                }
                String taskId = task.optString("taskId", "").trim();
                if (taskId.isEmpty() || (!force && Status.hasFlagToday("gameCenterP2eTaskTried::" + taskId))) {
                    continue;
                }
                Status.flagToday("gameCenterP2eTaskTried::" + taskId);
                String resp = MagicBoxRpcCall.p2ePlatformTaskSignUp(taskId, task.optString("taskToken", ""));
                if (resp == null || resp.isEmpty() || !MessageUtil.checkSuccess(TAG, new JSONObject(resp))) {
                    continue;
                }
                Log.magicBox("游戏中心🪙逛一逛[" + task.optString("title", taskId) + "]#已报名");
                justSigned.add(task);
                TimeUtil.sleep(500);
            }
            if (justSigned.isEmpty()) {
                return;
            }
            //浏览窗口：时长取任务下发的 floatingBall.timeSeconds（仿新版保护地读 timeCount），
            //取最大值再加 1s 余量；异常值按缺省 15s 处理，不阻塞本轮
            int waitSeconds = 15;
            for (JSONObject task : justSigned) {
                JSONObject ball = task.optJSONObject("floatingBall");
                int s = ball == null ? 0 : ball.optInt("timeSeconds", 0);
                if (s > 0 && s <= 300 && s > waitSeconds) {
                    waitSeconds = s;
                }
            }
            TimeUtil.sleep((waitSeconds + 1) * 1000L);
            JSONArray afterList = p2ePlatformTaskList(MagicBoxRpcCall.p2eQueryTaskList());
            if (afterList == null) {
                return;
            }
            for (JSONObject task : justSigned) {
                String taskId = task.optString("taskId", "").trim();
                JSONObject now = findPlatformTaskById(afterList, taskId);
                if (now != null && "COMPLETED".equals(now.optString("taskStatus", ""))) {
                    p2eReceiveTask(now);
                }
                else if (now != null) {
                    Log.magicBox("游戏中心🪙逛一逛[" + task.optString("title", taskId) + "]#需真实浏览目标页，无法自动完成，今日跳过");
                }
            }
        }
        catch (Throwable t) {
            Log.err(TAG, "p2eDoViewTasks err:", t);
        }
    }

    /** 领取单个平台任务奖励，成功返回 true */
    private static boolean p2eReceiveTask(JSONObject task) {
        String taskId = task.optString("taskId", "").trim();
        String title = task.optString("title", taskId);
        try {
            String resp = MagicBoxRpcCall.p2eGameTaskReceive(taskId, task.optString("taskToken", ""),
                    task.optString("taskType", "PLATFORM_TRAN_TASK"));
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
