package io.github.aw1y2z.sesame.data.modelFieldExt;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.github.aw1y2z.sesame.data.AccountSwitchConfig;
import io.github.aw1y2z.sesame.data.GlobalConfigField;

/**
 * 全局功能开关（布尔）。
 * <p>值统一读写 config/config_common.json，不跟随当前账号的 config_v2 保存。
 */
public class FeatureFlagBooleanModelField extends BooleanModelField implements GlobalConfigField {

    /** 可选的功能开关 */
    public enum Flag {
        /** 自动处理滑动/拼图验证 */
        AUTO_SLIDE_CAPTCHA,
        /** 解除登录账号数量限制 */
        UNLOCK_ACCOUNT_LIMIT
    }

    private final Flag flag;

    public FeatureFlagBooleanModelField(String code, String name, Boolean value, Flag flag) {
        super(code, name, value);
        this.flag = flag;
    }

    @Override
    public Boolean getValue() {
        switch (flag) {
            case AUTO_SLIDE_CAPTCHA:
                return AccountSwitchConfig.isAutoSlideCaptchaEnabled();
            case UNLOCK_ACCOUNT_LIMIT:
                return AccountSwitchConfig.isUnlockAccountLimitEnabled();
            default:
                return false;
        }
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
        switch (flag) {
            case AUTO_SLIDE_CAPTCHA:
                AccountSwitchConfig.setAutoSlideCaptchaEnabled(enabled);
                break;
            case UNLOCK_ACCOUNT_LIMIT:
                AccountSwitchConfig.setUnlockAccountLimitEnabled(enabled);
                break;
            default:
                break;
        }
        super.setObjectValue(enabled);
    }
}
