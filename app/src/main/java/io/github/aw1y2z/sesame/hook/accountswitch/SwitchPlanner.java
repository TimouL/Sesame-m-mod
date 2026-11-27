package io.github.aw1y2z.sesame.hook.accountswitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 切号规划器：纯逻辑，不碰线程、不碰反射、不读配置，只回答两个问题——
 * 「生效顺序是什么」与「下一个目标是谁」。
 *
 * 规则（与配置项说明一致）：
 *   - 常态保持账号可用时，把它挪到列表末尾，一轮必然以它收尾，之后从第一个账号开始下一轮；
 *   - 保持账号不可用（未配置、不在列表里、没有登录记录或被隔离）时退化为原始顺序，
 *     一轮以「列表走完回到开头」结束；
 *   - 没有登录记录或被隔离的账号直接跳过，不作为目标。
 *
 * 本类只依赖 java.util，可在普通 JDK 上单测。
 */
public final class SwitchPlanner {

    private SwitchPlanner() {
    }

    /** 一次规划出的目标 */
    public static final class Target {

        /** 目标账号 uid */
        public final String uid;
        /** 目标在生效顺序中的下标 */
        public final int index;
        /** 目标就是常态保持账号（本轮到此结束） */
        public final boolean arrivingHold;
        /** 本次选择发生了回绕（目标下标不大于当前下标，或当前账号不在列表里） */
        public final boolean wrapped;
        /** 本次切换意味着一轮结束 */
        public final boolean roundEnd;

        Target(String uid, int index, boolean arrivingHold, boolean wrapped, boolean roundEnd) {
            this.uid = uid;
            this.index = index;
            this.arrivingHold = arrivingHold;
            this.wrapped = wrapped;
            this.roundEnd = roundEnd;
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /**
     * 构造生效顺序：过滤空 uid；常态保持账号在列表里时挪到末尾，其余保持原相对顺序。
     *
     * @param orderedUids 配置中的账号顺序
     * @param holdUid     常态保持账号 uid，可为空
     */
    public static List<String> effectiveOrder(List<String> orderedUids, String holdUid) {
        List<String> result = new ArrayList<>();
        if (orderedUids == null) {
            return result;
        }
        String hold = null;
        for (String uid : orderedUids) {
            if (isEmpty(uid)) {
                continue;
            }
            if (!isEmpty(holdUid) && uid.equals(holdUid)) {
                hold = uid;
                continue;
            }
            if (!result.contains(uid)) {
                result.add(uid);
            }
        }
        if (hold != null) {
            result.add(hold);
        }
        return result;
    }

    /**
     * 保持账号是否可用：已配置、在生效顺序里、且在合格集合中（有登录记录且未被隔离）。
     */
    public static boolean isHoldUsable(List<String> effective, String holdUid, Set<String> eligible) {
        return !isEmpty(holdUid)
                && effective != null && effective.contains(holdUid)
                && eligible != null && eligible.contains(holdUid);
    }

    /**
     * 选下一个目标。
     *
     * @param effective         生效顺序（{@link #effectiveOrder} 的输出）
     * @param currentUid        当前登录账号 uid；不在列表里时从第一个开始找
     * @param startFromBeginning true 表示从列表第一个账号开始找
     *                          （用在「当前正坐在作为一轮收尾进入的常态保持账号上」时）
     * @param eligible          合格账号集合：有登录记录且未被隔离；不在集合里的账号跳过
     * @param holdUid           常态保持账号 uid，可为空
     * @param holdUsable        保持账号是否可用（{@link #isHoldUsable}）
     * @return 目标；没有可切换的账号时返回 null
     */
    public static Target nextTarget(List<String> effective, String currentUid,
                                    boolean startFromBeginning, Set<String> eligible,
                                    String holdUid, boolean holdUsable) {
        if (effective == null || effective.size() < 2 || eligible == null) {
            return null;
        }
        int currentIndex = isEmpty(currentUid) ? -1 : effective.indexOf(currentUid);
        int start = startFromBeginning ? 0 : (currentIndex >= 0 ? currentIndex + 1 : 0);
        for (int step = 0; step < effective.size(); step++) {
            int index = (start + step) % effective.size();
            String candidate = effective.get(index);
            if (isEmpty(candidate) || candidate.equals(currentUid)) {
                continue;
            }
            if (!eligible.contains(candidate)) {
                continue;
            }
            boolean arrivingHold = holdUsable && candidate.equals(holdUid);
            boolean wrapped = !startFromBeginning && (currentIndex < 0 || index <= currentIndex);
            boolean roundEnd = arrivingHold || (!holdUsable && wrapped);
            return new Target(candidate, index, arrivingHold, wrapped, roundEnd);
        }
        return null;
    }
}
