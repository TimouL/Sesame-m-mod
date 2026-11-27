package io.github.aw1y2z.sesame.hook;

import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import io.github.aw1y2z.sesame.data.AccountSwitchConfig;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.StringUtil;
import io.github.aw1y2z.sesame.util.XHelpers;

/**
 * 直接调用支付宝账号服务读取/切换登录账号。
 *
 * 读取走 AccountService.getLoginedAlipayUser，不经过账号管理页面，
 * 因此不受「登录账号超过 5 个就清除」页面逻辑影响；
 * 切换走 LoginService 的 7 参 login(type=switchAccount)。
 */
public final class AlipayAccountSwitchBridge {

    private static final String TAG = "AccountSwitchBridge";

    private static final String SERVICE_PREFIX =
            "com.alipay.mobile.framework.service.ext.security.";

    private static final int STATUS_SUCCESS = 1000;

    /** 目标账号登录凭证失效，支付宝要求重新输入账号密码 */
    private static final int STATUS_NEED_RELOGIN = 1014;

    /** 选中的 login 方法签名只打一次日志，便于支付宝改版时定位断点 */
    private static volatile boolean loginMethodLogged;

    private AlipayAccountSwitchBridge() {
    }

    /** 切号请求的分类结果：上层按类别决定隔离、熔断还是普通重试，不再靠猜 */
    public enum SwitchStatus {
        /** 请求已被支付宝受理（最终结果仍需轮询 currentUid 确认落地） */
        ACCEPTED,
        /** 目标账号凭证失效（1014），需要用户先手动登录一次 */
        NEED_MANUAL_LOGIN,
        /** 支付宝明确拒绝（返回了非成功状态码或 false） */
        REJECTED,
        /** 当前支付宝版本找不到可用的切换方法（版本不兼容） */
        METHOD_MISSING,
        /** 入参不合法（uid/登录标识缺失） */
        INVALID,
        /** 调用本身抛异常或返回值无法识别 */
        ERROR
    }

    /** 一次切号请求的结果 */
    public static final class SwitchResult {

        public final SwitchStatus status;
        public final String detail;

        SwitchResult(SwitchStatus status, String detail) {
            this.status = status;
            this.detail = detail == null ? "" : detail;
        }

        public boolean isAccepted() {
            return status == SwitchStatus.ACCEPTED;
        }
    }

    /** 供全局配置反射调用；模块 App 进程调用会失败并返回空列表 */
    public static List<AccountSwitchConfig.Account> fetchAccountsForConfig() {
        List<AccountSwitchConfig.Account> result = new ArrayList<>();
        try {
            for (HostAccount account : accounts()) {
                result.add(new AccountSwitchConfig.Account(
                        account.uid, account.loginId, account.label));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG + "#读取登录账号失败", t);
        }
        return result;
    }

    /** 当前登录账号 uid，读取失败返回 null */
    public static String currentUid() {
        try {
            Object userInfo = XHelpers.callMethod(service("AuthService"), "getUserInfo");
            if (userInfo != null) {
                String uid = readString(userInfo, "getUserId");
                if (!StringUtil.isEmpty(uid)) {
                    return uid;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "读取当前账号失败: " + t);
        }
        return ApplicationHook.getUserId();
    }

    /** 支付宝本地保存的全部登录账号 */
    public static List<HostAccount> accounts() throws Throwable {
        LinkedHashMap<String, HostAccount> result = new LinkedHashMap<>();
        Object accountService = service("AccountService");
        Object raw = XHelpers.callMethod(accountService, "getLoginedAlipayUser");
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (item == null) {
                    continue;
                }
                String uid = readString(item, "getUserId");
                if (!isValidUid(uid)) {
                    continue;
                }
                String loginId = readString(item, "getLogonId");
                String displayName = readString(item, "getDisplayName");
                HostAccount account = new HostAccount(uid, loginId, buildLabel(displayName, loginId));
                HostAccount existing = result.get(uid);
                if (existing == null) {
                    result.put(uid, account);
                } else if (!StringUtil.isEmpty(account.loginId)
                        && !account.loginId.equals(existing.loginId)) {
                    // 同一 uid 存在多个登录记录时保留第一条可用记录
                    Log.i(TAG, "账号存在多条登录记录，已保留首条: " + uid);
                }
            }
        }
        // 当前账号兜底（部分版本 getLoginedAlipayUser 不含当前账号）
        try {
            String uid = currentUid();
            if (isValidUid(uid) && !result.containsKey(uid)) {
                String loginId = readString(
                        XHelpers.callMethod(service("AuthService"), "getUserInfo"),
                        "getLogonId");
                result.put(uid, new HostAccount(uid, loginId, buildLabel(null, loginId)));
            }
        } catch (Throwable ignored) {
            // 当前账号读取失败不影响其它账号
        }
        return new ArrayList<>(result.values());
    }

    /**
     * 发起切号，返回分类结果（见 {@link SwitchStatus}）。
     * ACCEPTED 只表示请求已被支付宝受理，最终结果仍需轮询 currentUid 确认落地。
     * 本方法不抛异常：调用异常归类为 ERROR 返回，细节在 detail 里。
     */
    public static SwitchResult switchToAccount(String uid, String loginId) {
        if (!isValidUid(uid) || StringUtil.isEmpty(loginId)) {
            Log.accountSwitch("自动切号🔄️ 缺少账号登录标识，无法切换: " + uid);
            return new SwitchResult(SwitchStatus.INVALID, "missing uid/loginId");
        }
        Object loginService;
        Method login;
        try {
            loginService = service("LoginService");
            login = findLoginMethod(loginService.getClass());
        } catch (Throwable t) {
            Log.printStackTrace(TAG + "#获取 LoginService 失败", t);
            return new SwitchResult(SwitchStatus.ERROR, "service: " + t);
        }
        if (login == null) {
            Log.accountSwitch("自动切号🔄️ 当前支付宝版本未找到切换账号方法");
            return new SwitchResult(SwitchStatus.METHOD_MISSING, "login method not found");
        }
        Bundle bundle = new Bundle();
        bundle.putString("targetUid", uid);
        Object result;
        try {
            result = login.invoke(loginService, loginId, null, "switchAccount", null, null, true, bundle);
        } catch (Throwable t) {
            Log.accountSwitch("自动切号🔄️ 调用切号接口异常: " + t);
            return new SwitchResult(SwitchStatus.ERROR, "invoke: " + t);
        }
        // 切号被拒时没有更多信息，把支付宝返回的状态打出来便于定位（尤其是从主账号切出失败时）
        Log.accountSwitch("自动切号🔄️ 切号接口返回: " + describeResult(result)
                + "，当前=" + currentUid() + "，目标=" + maskLoginId(loginId) + "(" + uid + ")");
        if (result == null) {
            return new SwitchResult(SwitchStatus.REJECTED, "null result");
        }
        if (result instanceof Boolean) {
            return (Boolean) result
                    ? new SwitchResult(SwitchStatus.ACCEPTED, "boolean true")
                    : new SwitchResult(SwitchStatus.REJECTED, "boolean false");
        }
        Integer status = readStatus(result);
        if (status != null) {
            if (status == STATUS_NEED_RELOGIN) {
                Log.accountSwitch("自动切号🔄️ 目标账号需要先手动登录一次"
                        + "（支付宝返回 1014：请输入账号密码重新登录）: " + maskLoginId(loginId));
                return new SwitchResult(SwitchStatus.NEED_MANUAL_LOGIN, "status 1014");
            }
            return status == STATUS_SUCCESS
                    ? new SwitchResult(SwitchStatus.ACCEPTED, "status 1000")
                    : new SwitchResult(SwitchStatus.REJECTED, "status " + status);
        }
        try {
            Object loginFlag = XHelpers.callMethod(result, "getLoginFlag");
            if (loginFlag != null) {
                return Boolean.TRUE.equals(loginFlag)
                        ? new SwitchResult(SwitchStatus.ACCEPTED, "loginFlag true")
                        : new SwitchResult(SwitchStatus.REJECTED, "loginFlag false");
            }
        } catch (Throwable ignored) {
            // 继续尝试字段
        }
        try {
            Object loginFlag = XHelpers.getObjectField(result, "loginFlag");
            if (loginFlag != null) {
                return Boolean.TRUE.equals(loginFlag)
                        ? new SwitchResult(SwitchStatus.ACCEPTED, "loginFlag true")
                        : new SwitchResult(SwitchStatus.REJECTED, "loginFlag false");
            }
        } catch (Throwable ignored) {
            // 无法识别的返回值按已受理处理，交给 currentUid 轮询确认
        }
        return new SwitchResult(SwitchStatus.ACCEPTED, "unrecognized result, assume accepted");
    }

    /** 读取返回对象里的结果状态码，取不到返回 null */
    private static Integer readStatus(Object result) {
        try {
            Object status = XHelpers.callMethod(result, "getResultStatus");
            if (status instanceof Number) {
                return ((Number) status).intValue();
            }
        } catch (Throwable ignored) {
            // 尝试字段
        }
        try {
            Object status = XHelpers.getObjectField(result, "resultStatus");
            if (status instanceof Number) {
                return ((Number) status).intValue();
            }
        } catch (Throwable ignored) {
            // 取不到状态码
        }
        return null;
    }

    /** 把切号接口返回值整理成一行日志，未知结构时至少打印类名 */
    private static String describeResult(Object result) {
        if (result == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(result.getClass().getName());
        appendValue(sb, result, "getResultStatus");
        appendValue(sb, result, "getMemo");
        appendValue(sb, result, "getLoginFlag");
        appendValue(sb, result, "getSuccess");
        if (result instanceof Boolean) {
            sb.append('=').append(result);
        }
        return sb.toString();
    }

    private static void appendValue(StringBuilder sb, Object target, String methodName) {
        try {
            Object value = XHelpers.callMethod(target, methodName);
            if (value != null) {
                sb.append(' ').append(methodName.replace("get", "")).append('=').append(value);
            }
        } catch (Throwable ignored) {
            // 返回对象没有该字段时忽略
        }
    }

    /**
     * 找 LoginService 的 7 参 login 方法。调用约定是
     * login(loginId, password, type, scene, ext, saveLoginInfo, bundle)，
     * 已知位置的参数类型全部校验：[0][1][2] 为 String、[5] 为 boolean、[6] 为 Bundle；
     * [3][4] 在不同版本里类型不稳定（String/Map 都有），只记录不强校验。
     * 严格匹配不到时退回宽松匹配（只校验 [0][2][5][6]），并把最终选中的签名打进日志，
     * 支付宝改版导致切号失效时能第一时间看出断在哪。
     */
    private static Method findLoginMethod(Class<?> clazz) {
        Method strict = null;
        Method loose = null;
        int candidates = 0;
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!"login".equals(method.getName()) || types.length != 7) {
                    continue;
                }
                if (types[0] != String.class || types[2] != String.class
                        || types[5] != Boolean.TYPE || types[6] != Bundle.class) {
                    continue;
                }
                candidates++;
                if (types[1] == String.class) {
                    if (strict == null) {
                        strict = method;
                    }
                } else if (loose == null) {
                    loose = method;
                }
            }
            current = current.getSuperclass();
        }
        if (strict == null && loose == null) {
            for (Method method : clazz.getMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if ("login".equals(method.getName()) && types.length == 7
                        && types[0] == String.class && types[2] == String.class
                        && types[5] == Boolean.TYPE && types[6] == Bundle.class) {
                    candidates++;
                    if (types[1] == String.class) {
                        if (strict == null) {
                            strict = method;
                        }
                    } else if (loose == null) {
                        loose = method;
                    }
                }
            }
        }
        Method chosen = strict != null ? strict : loose;
        if (chosen == null) {
            return null;
        }
        try {
            chosen.setAccessible(true);
        } catch (Throwable ignored) {
            // 公开方法不需要 setAccessible；失败也先返回，invoke 时再暴露问题
        }
        if (!loginMethodLogged) {
            loginMethodLogged = true;
            Log.accountSwitch("自动切号🔄️ 切号方法: " + describeSignature(chosen)
                    + (strict == null ? "（宽松匹配）" : "")
                    + (candidates > 1 ? "（共 " + candidates + " 个候选）" : ""));
        }
        return chosen;
    }

    private static String describeSignature(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getDeclaringClass().getName()).append('#').append(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(types[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    private static Object service(String name) throws Throwable {
        Object service = ApplicationHook.getServiceObject(SERVICE_PREFIX + name);
        if (service == null) {
            throw new IllegalStateException("service not found: " + name);
        }
        return service;
    }

    private static String readString(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Object value = XHelpers.callMethod(target, methodName);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String buildLabel(String displayName, String loginId) {
        String maskedLoginId = maskLoginId(loginId);
        if (!StringUtil.isEmpty(displayName)) {
            return StringUtil.isEmpty(maskedLoginId)
                    ? displayName : displayName + "(" + maskedLoginId + ")";
        }
        return maskedLoginId;
    }

    /** 登录标识打码：全项目统一走这里，日志里不出现明文手机号/邮箱 */
    public static String maskLoginId(String loginId) {
        if (StringUtil.isEmpty(loginId)) {
            return null;
        }
        if (loginId.length() <= 4) {
            return loginId;
        }
        if (loginId.length() <= 8) {
            return loginId.substring(0, 2) + "****" + loginId.substring(loginId.length() - 2);
        }
        return loginId.substring(0, 3) + "****" + loginId.substring(loginId.length() - 4);
    }

    private static boolean isValidUid(String uid) {
        if (StringUtil.isEmpty(uid) || uid.length() > 128) {
            return false;
        }
        for (int i = 0; i < uid.length(); i++) {
            char c = uid.charAt(i);
            boolean valid = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || c == '_' || c == '@' || c == '.' || c == '+' || c == ':' || c == '-';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    /** 支付宝侧账号：uid 为账号唯一标识，loginId 为登录名（切号与展示都要用） */
    public static final class HostAccount {

        public final String uid;
        public final String loginId;
        public final String label;

        public HostAccount(String uid, String loginId, String label) {
            this.uid = uid;
            this.loginId = loginId;
            this.label = label;
        }
    }
}
