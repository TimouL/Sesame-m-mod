package io.github.aw1y2z.sesame.util;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 协作式「代际令牌」：线程起跑时绑定自己那一代，停止时由所属任务递增代际；线程在检查点
 * （{@link TimeUtil#sleep}、RPC 桥入口）判断本代是否作废并结束本轮，避免与新一代并发踩踏共享状态。
 * <p>未绑定的线程一律视为「未作废」，不受影响。
 * <p>除单任务的代际外，还有一个全局「账号纪元」：绑定时一并快照。切号开始时推进纪元，
 * 此前绑定的全部任务线程都会在下一个检查点判定作废——哪怕某个任务的停止通知漏了，
 * 旧账号的线程也发不出 RPC，从根上杜绝两个账号的任务在同一登录态下重叠执行。
 */
public final class RunGeneration {

    private static final ThreadLocal<RunGeneration> CURRENT = new ThreadLocal<>();

    /** 全局账号纪元：每次切号开始时推进，此前绑定的线程全部作废 */
    private static final AtomicLong ACCOUNT_EPOCH = new AtomicLong();

    private final long myGen;
    private final LongSupplier ownerGen;
    private final long epochAtBind;

    private RunGeneration(long myGen, LongSupplier ownerGen, long epochAtBind) {
        this.myGen = myGen;
        this.ownerGen = ownerGen;
        this.epochAtBind = epochAtBind;
    }

    /** 当前账号纪元 */
    public static long currentAccountEpoch() {
        return ACCOUNT_EPOCH.get();
    }

    /** 推进账号纪元（切号开始时调用）：此前绑定的全部任务线程在下一个检查点作废 */
    public static long advanceAccountEpoch() {
        return ACCOUNT_EPOCH.incrementAndGet();
    }

    /** 绑定本代令牌（{@code myGen} 为快照、{@code ownerGen} 取当前代际）；返回此前的令牌供 {@link #restore} 还原 */
    public static RunGeneration bind(long myGen, LongSupplier ownerGen) {
        RunGeneration prev = CURRENT.get();
        CURRENT.set(new RunGeneration(myGen, ownerGen, ACCOUNT_EPOCH.get()));
        return prev;
    }

    /** 还原 {@link #bind} 之前的令牌；嵌套执行（isSync 会在同一线程内联起跑）时不能直接清空 */
    public static void restore(RunGeneration prev) {
        if (prev == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(prev);
        }
    }

    /** 本线程所属的这一代是否已作废；未绑定（非受管任务线程）时恒为 false */
    public static boolean isStale() {
        RunGeneration token = CURRENT.get();
        return token != null && (token.myGen != token.ownerGen.getAsLong()
                || token.epochAtBind != ACCOUNT_EPOCH.get());
    }
}
