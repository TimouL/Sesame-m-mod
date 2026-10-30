package io.github.aw1y2z.sesame.data.task;

/**
 * 可执行功能单元：执行弹窗里的一行。
 * <p>key 用于执行广播的选中回传；模块未覆盖 {@code getExecUnits()} 时，
 * 默认单元的 key 为模块类名（表示整个模块）。
 */
public final class ExecUnit {

    private final String key;
    private final String title;
    private final String desc;

    public ExecUnit(String key, String title, String desc) {
        this.key = key;
        this.title = title;
        this.desc = desc == null ? "" : desc;
    }

    public String getKey() {
        return key;
    }

    public String getTitle() {
        return title;
    }

    public String getDesc() {
        return desc;
    }
}
