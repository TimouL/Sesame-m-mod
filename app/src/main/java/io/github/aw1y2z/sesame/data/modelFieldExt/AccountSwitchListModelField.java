package io.github.aw1y2z.sesame.data.modelFieldExt;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.aw1y2z.sesame.data.AccountSwitchConfig;
import io.github.aw1y2z.sesame.data.GlobalConfigField;
import io.github.aw1y2z.sesame.data.ModelField;
import io.github.aw1y2z.sesame.util.JsonUtil;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.StringUtil;

/**
 * 切号账号顺序列表（全局配置）。
 * <p>列表内容来自 config/config_common.json，顺序即切号顺序；
 * 界面上下移动后按新顺序整体写回该文件。
 */
public class AccountSwitchListModelField extends ModelField<List<String>> implements GlobalConfigField {

    public AccountSwitchListModelField(String code, String name, List<String> value) {
        super(code, name, value);
    }

    @Override
    @JsonIgnore
    public String getType() {
        return "ACCOUNT_LIST";
    }

    @Override
    public List<String> getValue() {
        List<String> uids = new ArrayList<>();
        for (AccountSwitchConfig.Account account : AccountSwitchConfig.getAccountSwitchAccounts()) {
            uids.add(account.getUid());
        }
        return uids;
    }

    @Override
    @JsonIgnore
    public String getConfigValue() {
        return JsonUtil.toJsonString(getValue());
    }

    @Override
    @JsonIgnore
    public void setConfigValue(String configValue) {
        applyGlobalValue(configValue);
    }

    /**
     * 只更新内存副本，不落盘：公共配置文件的写入只发生在 {@link #applyGlobalValue(Object)}，
     * 避免字段构造时（模型初始化）就把默认值写进文件、覆盖用户设置。
     */
    @Override
    public void setObjectValue(Object objectValue) {
        super.setObjectValue(getValue());
    }

    @Override
    public void applyGlobalValue(Object value) {
        if (value == null) {
            super.setObjectValue(getValue());
            return;
        }
        try {
            List<AccountSwitchConfig.Account> current =
                    AccountSwitchConfig.readFresh().getAccountSwitchAccounts();
            Map<String, AccountSwitchConfig.Account> byUid = new LinkedHashMap<>();
            for (AccountSwitchConfig.Account account : current) {
                byUid.put(account.getUid(), account);
            }
            List<AccountSwitchConfig.Account> ordered = new ArrayList<>();
            JSONArray array = new JSONArray(String.valueOf(value));
            for (int i = 0; i < array.length(); i++) {
                String uid = array.optString(i, null);
                if (StringUtil.isEmpty(uid) || containsUid(ordered, uid)) {
                    continue;
                }
                AccountSwitchConfig.Account account = byUid.get(uid);
                ordered.add(account != null
                        ? account : new AccountSwitchConfig.Account(uid, null, null));
            }
            // 兜底：解析结果缺失的账号保持原顺序追加，避免误删配置
            for (AccountSwitchConfig.Account account : current) {
                if (!containsUid(ordered, account.getUid())) {
                    ordered.add(account);
                }
            }
            AccountSwitchConfig.setAccountSwitchAccounts(ordered);
            super.setObjectValue(getValue());
        } catch (Throwable t) {
            Log.printStackTrace("AccountSwitchListModelField#保存账号列表失败", t);
        }
    }

    private static boolean containsUid(List<AccountSwitchConfig.Account> accounts, String uid) {
        for (AccountSwitchConfig.Account account : accounts) {
            if (account.getUid() != null && account.getUid().equals(uid)) {
                return true;
            }
        }
        return false;
    }
}
