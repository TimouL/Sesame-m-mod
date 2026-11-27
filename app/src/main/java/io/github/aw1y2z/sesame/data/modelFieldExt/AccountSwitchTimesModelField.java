package io.github.aw1y2z.sesame.data.modelFieldExt;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

import io.github.aw1y2z.sesame.data.AccountSwitchConfig;
import io.github.aw1y2z.sesame.data.GlobalConfigField;

/**
 * 切换账号定时执行时间点（全局配置）。
 * <p>界面上是逗号分隔的时间列表（如 0800,2000），实际读写 config/config_common.json，
 * 不区分登录账号，所有账号共用同一份。
 */
public class AccountSwitchTimesModelField extends ListModelField.ListJoinCommaToStringModelField
        implements GlobalConfigField {

    public AccountSwitchTimesModelField(String code, String name, List<String> value) {
        super(code, name, value);
    }

    @Override
    public List<String> getValue() {
        return AccountSwitchConfig.getAccountSwitchScheduleTimes();
    }

    @Override
    @JsonIgnore
    public String getConfigValue() {
        return String.join(",", AccountSwitchConfig.getAccountSwitchScheduleTimes());
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
        value = splitTimes(objectValue);
    }

    @Override
    public void applyGlobalValue(Object value) {
        AccountSwitchConfig.setAccountSwitchScheduleTimes(splitTimes(value));
        this.value = new ArrayList<>(AccountSwitchConfig.getAccountSwitchScheduleTimes());
    }

    /** 把逗号分隔的时间文本或列表拆成时间点数组 */
    private static List<String> splitTimes(Object source) {
        List<String> times = new ArrayList<>();
        if (source == null) {
            return times;
        }
        String text = source instanceof List
                ? String.join(",", toStringList((List<?>) source))
                : String.valueOf(source);
        for (String item : text.split(",")) {
            if (item != null && !item.trim().isEmpty()) {
                times.add(item.trim());
            }
        }
        return times;
    }

    private static List<String> toStringList(List<?> source) {
        List<String> result = new ArrayList<>();
        for (Object item : source) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }
}
