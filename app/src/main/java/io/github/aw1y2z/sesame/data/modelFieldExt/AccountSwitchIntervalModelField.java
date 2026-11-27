package io.github.aw1y2z.sesame.data.modelFieldExt;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.github.aw1y2z.sesame.data.AccountSwitchConfig;
import io.github.aw1y2z.sesame.data.GlobalConfigField;

/**
 * 切号等待间隔 / 切号轮次间隔（分钟）。
 * <p>值统一读写 config/config_common.json，不跟随当前账号的 config_v2 保存。
 */
public class AccountSwitchIntervalModelField extends IntegerModelField implements GlobalConfigField {

    /** 当前账号任务跑完后等待多久才切号 */
    public static final int KIND_SWITCH = 0;
    /** 全部账号轮完一圈后的等待时间 */
    public static final int KIND_ROUND = 1;

    private final int kind;

    /**
     * @param kind 取值见 {@link #KIND_SWITCH} / {@link #KIND_ROUND}
     */
    public AccountSwitchIntervalModelField(String code, String name, Integer value,
                                           Integer minLimit, Integer maxLimit, int kind) {
        super(code, name, value, minLimit, maxLimit);
        this.kind = kind;
    }

    @Override
    public Integer getValue() {
        return kind == KIND_ROUND
                ? AccountSwitchConfig.getAccountSwitchRoundIntervalMinutes()
                : AccountSwitchConfig.getAccountSwitchIntervalMinutes();
    }

    @Override
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
        super.setObjectValue(parseMinutes(objectValue));
    }

    @Override
    public void applyGlobalValue(Object value) {
        int minutes = parseMinutes(value);
        if (kind == KIND_ROUND) {
            AccountSwitchConfig.setAccountSwitchRoundIntervalMinutes(minutes);
        } else {
            AccountSwitchConfig.setAccountSwitchIntervalMinutes(minutes);
        }
        super.setObjectValue(minutes);
    }

    private static int parseMinutes(Object value) {
        int minutes = 0;
        if (value != null) {
            try {
                minutes = Integer.parseInt(String.valueOf(value).trim());
            } catch (Exception ignored) {
                minutes = 0;
            }
        }
        return Math.max(0, Math.min(minutes, 24 * 60));
    }
}
