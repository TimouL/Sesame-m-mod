package io.github.aw1y2z.sesame.data.task;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「执行任务」弹窗的选择结果传递。
 * <p>执行广播线程 {@code set}，模块线程读；每次执行覆盖前值。
 * selectedKeys 为 null 表示未经过弹窗（定时整轮/旧入口），此时视为全部执行。
 * <p>已知边界：分组执行进行中再触发一次整轮执行会覆盖前一次的选择，
 * 弹窗流程下属罕见操作，接受该限制。
 */
public final class ExecContext {

    private static final ExecContext INSTANCE = new ExecContext();

    private volatile Set<String> selectedKeys;
    private volatile boolean force;

    private ExecContext() {
    }

    public static ExecContext get() {
        return INSTANCE;
    }

    public static void set(Set<String> selectedKeys, boolean force) {
        if (selectedKeys == null) {
            INSTANCE.selectedKeys = null;
        } else {
            Set<String> copy = Collections.newSetFromMap(new ConcurrentHashMap<>());
            copy.addAll(selectedKeys);
            INSTANCE.selectedKeys = copy;
        }
        INSTANCE.force = force;
    }

    public static void clear() {
        INSTANCE.selectedKeys = null;
        INSTANCE.force = false;
    }

    /**
     * 当前功能是否被选中执行。
     * <p>未经过弹窗（{@code selectedKeys == null}）时全部视为选中；
     * 经过弹窗时按 key 匹配（模块级默认单元的 key 为模块类名）。
     */
    public static boolean isSelected(String key) {
        Set<String> keys = INSTANCE.selectedKeys;
        return keys == null || keys.contains(key);
    }

    /** 弹窗的「强制执行」：跳过当日已完成标记与状态检查 */
    public static boolean isForce() {
        return INSTANCE.force;
    }
}
