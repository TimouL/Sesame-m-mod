package io.github.aw1y2z.sesame.model.task.magicbox;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.aw1y2z.sesame.hook.ApplicationHook;
import io.github.aw1y2z.sesame.hook.AuthCodeHelper;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.Status;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Random;

/**
 * 冲鸭攒话费签到（小程序 appId 2021004113642010，业务走 webgw HTTP 直连，不经 mobilegw RPC）：
 * authCode → getToken → query/key → signin/query 判定 → signin/execute/v3 签到。
 * 接口规格经真机抓包与直连实测验证。
 */
public final class ChongYaSign {

    private static final String TAG = "ChongYaSign";

    private static final String APP_ID = "2021004113642010";
    private static final String BASE = "https://gdbizweb.alipay-eco.com";
    /** 通用 query：实测缺失时服务端报 30001 参数有误 */
    private static final String COMMON_QUERY = "channelSource=self&version=5";

    private ChongYaSign() {
    }

    public static void checkAndSignIn(boolean force) {
        if (!force && Status.hasFlagToday("chongyaSignIn")) {
            return;
        }
        try {
            String authCode = AuthCodeHelper.getAuthCode(APP_ID);
            if (authCode == null || authCode.isEmpty()) {
                Log.magicBox("魔盒🦆冲鸭签到⚠️获取authCode失败");
                return;
            }

            JSONObject tokenJo = httpGetJson("/goduck/getToken?authCode=" + authCode + "&" + COMMON_QUERY);
            if (failed(tokenJo)) {
                return;
            }
            JSONObject tokenData = tokenJo.optJSONObject("data");
            String token = tokenData == null ? null : tokenData.optString("token", "");
            String uid = tokenData == null ? "" : tokenData.optString("userId", "");
            if (token == null || token.isEmpty()) {
                Log.magicBox("魔盒🦆冲鸭签到⚠️获取token失败");
                return;
            }

            JSONObject keyJo = httpGetJson("/gdbizweb/query/key?authCode=" + authCode);
            if (failed(keyJo)) {
                return;
            }
            Object keyData = keyJo.opt("data");
            String key = keyData instanceof JSONObject ? ((JSONObject) keyData).optString("key", "") : String.valueOf(keyData);
            if (key == null || key.isEmpty() || "null".equals(key)) {
                Log.magicBox("魔盒🦆冲鸭签到⚠️获取签名key失败");
                return;
            }

            JSONObject queryJo = httpPostJson("/gdbizweb/task/signin/query?" + COMMON_QUERY, new JSONObject(), uid, token, key);
            if (failed(queryJo)) {
                return;
            }
            JSONObject queryData = queryJo.optJSONObject("data");
            if (queryData == null) {
                return;
            }
            if (queryData.optBoolean("signinToday", false)) {
                Status.flagToday("chongyaSignIn");
                return;
            }

            JSONObject signJo = httpPostJson("/gdbizweb/task/signin/execute/v3?" + COMMON_QUERY, new JSONObject(), uid, token, key);
            if (failed(signJo)) {
                return;
            }
            JSONObject signData = signJo.optJSONObject("data");
            String prize = "";
            if (signData != null) {
                JSONArray list = signData.optJSONArray("signInResultVOList");
                if (list != null && list.length() > 0) {
                    prize = list.optJSONObject(0).optString("price", "");
                }
            }
            Log.magicBox("魔盒🦆冲鸭签到#获得[" + (prize.isEmpty() ? "?" : prize) + "充值金]");
            Status.flagToday("chongyaSignIn");
        }
        catch (Throwable t) {
            Log.err(TAG, "checkAndSignIn err:", t);
        }
    }

    private static boolean failed(JSONObject jo) {
        if (jo == null || !jo.optBoolean("success", false)) {
            Log.magicBox("魔盒🦆冲鸭签到⚠️接口失败：" + jo);
            return true;
        }
        return false;
    }

    /** 签名头五件套：userId + authorization + timestamp + nonce + sign（md5 拼串） */
    private static HttpURLConnection signHeaders(HttpURLConnection conn, String uid, String token, String key) {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = randomStr(16);
        String sign = md5("timestamp=" + ts + "&nonce=" + nonce + "&" + key);
        conn.setRequestProperty("userId", uid);
        conn.setRequestProperty("authorization", token);
        conn.setRequestProperty("timestamp", ts);
        conn.setRequestProperty("nonce", nonce);
        conn.setRequestProperty("sign", sign);
        return conn;
    }

    private static JSONObject httpGetJson(String path) throws Exception {
        return new JSONObject(httpRequest("GET", path, null, null, null, null));
    }

    private static JSONObject httpPostJson(String path, JSONObject body, String uid, String token, String key) throws Exception {
        return new JSONObject(httpRequest("POST", path, body, uid, token, key));
    }

    private static String httpRequest(String method, String path, JSONObject body,
                                      String uid, String token, String key) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + path).openConnection();
        conn.setRequestMethod(method);
        // 防外部网关挂起冻结任务线程（GameTask 同款约束）
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", getUA());
        if (uid != null) {
            signHeaders(conn, uid, token, key);
        }
        if (body != null) {
            conn.setDoOutput(true);
            try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(body.toString());
            }
        }
        int respCode = conn.getResponseCode();
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                respCode >= 200 && respCode <= 299 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line);
            }
        } finally {
            conn.disconnect();
        }
        return text.toString();
    }

    private static String randomStr(int len) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String getUA() {
        String systemUa = System.getProperty("http.agent");
        if (systemUa == null || systemUa.isEmpty()) {
            systemUa = "Mozilla/5.0 (Linux; Android 13)";
        }
        String alipayVer = String.valueOf(ApplicationHook.getAlipayVersion());
        return systemUa + " NebulaSDK/1.8.100112 Nebula AliApp(AP/" + alipayVer + ") AlipayClient/" + alipayVer;
    }
}
