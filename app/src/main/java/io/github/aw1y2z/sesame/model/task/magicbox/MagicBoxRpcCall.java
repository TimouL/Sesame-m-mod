package io.github.aw1y2z.sesame.model.task.magicbox;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.aw1y2z.sesame.hook.ApplicationHook;

public class MagicBoxRpcCall {

    /**
     * 游戏中心赚现金（P2E）首页：累计签到进度与金币资产
     */
    public static String p2eQueryHomePage() {
        return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.p2e.queryHomePage",
                "[{\"__git\":\"9e159d58cce04c13a\",\"appId\":\"2021003125685383\",\"canAddHome\":false,\"deviceLevel\":\"high\",\"frontEndVersion\":\"20260820\",\"p2eVersion\":\"\",\"screenType\":10,\"source\":\"ch_appcenter__chsub_9patch\",\"subscribePanelCheck\":false,\"unityDeviceLevel\":\"high\"}]");
    }

    /**
     * 游戏中心赚现金（P2E）签到：date/index/signSequenceId 取自 queryHomePage 的 signUpModuleVO，
     * 响应无数据字段，入账以重拉首页的今日记录为准
     */
    public static String p2eSignIn(String date, int index, String signSequenceId) {
        try {
            JSONObject args = new JSONObject();
            args.put("__git", "9e159d58cce04c13a");
            args.put("appId", "2021003125685383");
            args.put("date", date);
            args.put("index", index);
            args.put("p2eVersion", "");
            args.put("signSequenceId", signSequenceId);
            args.put("source", "ch_appcenter__chsub_9patch");
            return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.p2e.signIn",
                    new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 游戏中心赚现金（P2E）任务列表：platformTaskList 为「逛一逛/浏览15s」类平台任务，
     * taskStatus 流转 UN_SIGNUP → SIGNUP_COMPLETED → COMPLETED（可领）→ 领后从列表消失
     */
    public static String p2eQueryTaskList() {
        return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.p2e.queryTaskList",
                "[{\"__git\":\"9e159d58cce04c13a\",\"appId\":\"2021003125685383\",\"deviceLevel\":\"high\",\"frontEndVersion\":\"20260820\",\"p2eVersion\":\"\",\"panelLaunchableCheckMap\":{\"SET_HEAD_TASK\":false},\"sessionId\":\"" + System.currentTimeMillis()
                        + "\",\"setHeadPanelCheck\":false,\"source\":\"ch_appcenter__chsub_9patch\",\"subscribePanelCheck\":false,\"unityDeviceLevel\":\"high\"}]");
    }

    /**
     * 金币流水明细（分页，最新在前）：当日统计与服务器对齐用（2026-11-28 真机抓包实证）
     */
    public static String p2eQueryGoldRecord(int pageNum, int pageSize) {
        return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.p2e.queryGoldRecord",
                "[{\"pageNum\":" + pageNum + ",\"pageSize\":" + pageSize + "}]");
    }

    /**
     * 平台任务报名（UN_SIGNUP 时先报名）
     */
    public static String p2ePlatformTaskSignUp(String taskId, String taskToken) {
        try {
            JSONObject args = new JSONObject();
            args.put("actionChannel", "taskList");
            args.put("activityId", "P2E_PLATFORM_TASK");
            args.put("source", "ch_appcenter__chsub_9patch");
            args.put("taskId", taskId);
            args.put("taskToken", taskToken);
            return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.platformTaskSignUp",
                    new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 平台任务完成回传：参数与报名同构（actionChannel/activityId/source/taskId/taskToken）。
     * 2026-11-28 真机抓包实证：客户端悬浮球计时结束后正是调这个接口完成浏览任务，
     * 响应 {"success":true}，随后任务在列表里翻为 COMPLETED。
     */
    public static String p2ePlatformTaskComplete(String taskId, String taskToken) {
        try {
            JSONObject args = new JSONObject();
            args.put("actionChannel", "taskList");
            args.put("activityId", "P2E_PLATFORM_TASK");
            args.put("source", "ch_appcenter__chsub_9patch");
            args.put("taskId", taskId);
            args.put("taskToken", taskToken);
            return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.platformTaskComplete",
                    new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 游戏浏览任务悬浮球登记：客户端打开目标游戏时先调它登记计时，参数 appId 为游戏中心本体、
     * gameId / gameModuleId 取自任务列表（2026-11-28 抓包实证，几何王国 gameId=jhwg）；
     * 响应 data.floatingBallVO.timeSeconds 为要求时长（几何王国 60 秒）。
     * 完成判定是否由服务端自登记起计时未经证实，模块侧仅作实验性调用，结果以重拉列表复核为准。
     */
    public static String p2eFloatingBallConsult(String gameId, String gameModuleId) {
        try {
            JSONObject args = new JSONObject();
            args.put("__git", "9e159d58cce04c13a");
            args.put("appId", "2021003125685383");
            args.put("gameId", gameId);
            args.put("gameModuleId", gameModuleId);
            args.put("p2eVersion", "");
            args.put("source", "ch_appcenter__chsub_9patch");
            args.put("trafficDriverId", "");
            return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.p2e.gameP2eFloatingBallConsult",
                    new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 领取平台任务奖励：响应 data.coinAmount 为到账金币数、receiveSuccess 为领取结果
     */
    public static String p2eGameTaskReceive(String taskId, String taskToken, String taskType) {
        try {
            JSONObject args = new JSONObject();
            args.put("__git", "9e159d58cce04c13a");
            args.put("actionChannel", "taskList");
            args.put("activityId", "P2E_PLATFORM_TASK");
            args.put("appId", "2021003125685383");
            args.put("oriChInfo", "ch_appcenter__chsub_9patch");
            args.put("p2eVersion", "");
            args.put("source", "ch_appcenter__chsub_9patch");
            args.put("taskId", taskId);
            args.put("taskToken", taskToken);
            args.put("taskType", taskType);
            return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.p2e.gameP2eTaskReceive",
                    new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }
}
