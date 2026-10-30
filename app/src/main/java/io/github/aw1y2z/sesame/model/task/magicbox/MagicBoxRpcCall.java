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
