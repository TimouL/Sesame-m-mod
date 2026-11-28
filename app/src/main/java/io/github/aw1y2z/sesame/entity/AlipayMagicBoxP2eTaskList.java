package io.github.aw1y2z.sesame.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.aw1y2z.sesame.util.idMap.MagicBoxP2eTaskListMap;

/**
 * 赚现金黑名单列表的选项来源（与 AlipayAntFarmDrawMachineTaskList 同构）：
 * 条目就是模块运行同步进 MagicBoxP2eTaskListMap 的当日任务标题（id 与 name 均为标题）。
 */
public class AlipayMagicBoxP2eTaskList extends IdAndName {
    private static List<AlipayMagicBoxP2eTaskList> list;

    public AlipayMagicBoxP2eTaskList(String i, String n) {
        id = i;
        name = n;
    }

    public static List<AlipayMagicBoxP2eTaskList> getList() {
        if (list == null) {
            list = new ArrayList<>();
            for (Map.Entry<String, String> entry : MagicBoxP2eTaskListMap.getMap().entrySet()) {
                list.add(new AlipayMagicBoxP2eTaskList(entry.getKey(), entry.getValue()));
            }
        }
        return list;
    }

    public static void remove(String id) {
        getList();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                break;
            }
        }
    }

}
