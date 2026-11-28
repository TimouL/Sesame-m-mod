package io.github.aw1y2z.sesame.util.idMap;

import java.util.Map;

import io.github.aw1y2z.sesame.util.FileUtil;

/**
 * 赚现金任务标题收集表（与 AntFarmDrawMachineTaskListMap 同构）：
 * 模块运行同步当日任务标题进来，供黑名单列表的选项来源（entity.AlipayMagicBoxP2eTaskList）。
 */
public class MagicBoxP2eTaskListMap {

    private static final StringMapStore STORE = new StringMapStore(ignoredUserId -> FileUtil.getMagicBoxP2eTaskListMapFile());

    public static Map<String, String> getMap() {
        return STORE.getMap();
    }

    public static String get(String key) {
        return STORE.get(key);
    }

    public static void add(String key, String value) {
        STORE.add(key, value);
    }

    public static void remove(String key) {
        STORE.remove(key);
    }

    public static void load() {
        STORE.load(null);
    }

    public static boolean save() {
        return STORE.save(null);
    }

    public static void clear() {
        STORE.clear();
    }

}
