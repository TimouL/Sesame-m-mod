package io.github.aw1y2z.sesame.data.modelFieldExt;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.github.aw1y2z.sesame.data.AccountSwitchConfig;
import io.github.aw1y2z.sesame.data.GlobalConfigField;

/**
 * 切号演练模式开关（全局配置）。
 * <p>值统一读写 config/config_common.json，不跟随当前账号的 config_v2 保存。
 * 开启后调度器照常规划、照常写切号日志，但不真正切换账号。
 */
public class AccountSwitchDryRunModelField extends BooleanModelField implements GlobalConfigField {

    public AccountSwitchDryRunModelField(String code, String name, Boolean value) {
        super(code, name, value);
    }

    @Override
    public Boolean getValue() {
        return AccountSwitchConfig.isAccountSwitchDryRun();
    }

    @Override
    @JsonIgnore
    public String getConfigValue() {
        return String.valueOf(getValue());
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
        super.setObjectValue(Boolean.TRUE.equals(objectValue)
                || "true".equalsIgnoreCase(String.valueOf(objectValue)));
    }

    @Override
    public void applyGlobalValue(Object value) {
        boolean enabled = Boolean.TRUE.equals(value)
                || "true".equalsIgnoreCase(String.valueOf(value));
        AccountSwitchConfig.setAccountSwitchDryRun(enabled);
        super.setObjectValue(enabled);
    }
}
