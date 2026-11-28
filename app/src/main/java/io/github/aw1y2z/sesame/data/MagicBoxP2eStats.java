package io.github.aw1y2z.sesame.data;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.aw1y2z.sesame.util.FileUtil;
import io.github.aw1y2z.sesame.util.StringUtil;

/**
 * 魔盒赚现金当日统计：签到状态/签到金币/任务与游戏领奖金币。
 * 宿主进程在入账点更新（自带日期，跨日自动清零），设置页字段行用 readFresh 读来显示灰色小字摘要；
 * 与 AccountSwitchState 同为「配置主目录 + write2File 原子写」的文件共享模式。
 */
public class MagicBoxP2eStats {

    private static final String FILE_NAME = "magicbox_p2e_stats.json";

    public final String date;
    public final boolean signInDone;
    public final int signInCoins;
    public final int taskCoins;
    public final int gameCoins;

    public MagicBoxP2eStats(String date, boolean signInDone, int signInCoins, int taskCoins, int gameCoins) {
        this.date = date;
        this.signInDone = signInDone;
        this.signInCoins = signInCoins;
        this.taskCoins = taskCoins;
        this.gameCoins = gameCoins;
    }

    private static File getFile() {
        return new File(FileUtil.MAIN_DIRECTORY_FILE, FILE_NAME);
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    public boolean isToday() {
        return today().equals(date);
    }

    /** 读盘快照：无文件/损坏返回 null（UI 据此不显示摘要） */
    public static MagicBoxP2eStats readFresh() {
        try {
            File file = getFile();
            if (!file.exists() || !file.isFile()) {
                return null;
            }
            String text = FileUtil.readFromFile(file);
            if (StringUtil.isEmpty(text)) {
                return null;
            }
            JSONObject json = new JSONObject(text);
            return new MagicBoxP2eStats(
                    json.optString("date", ""),
                    json.optBoolean("signInDone", false),
                    json.optInt("signInCoins", 0),
                    json.optInt("taskCoins", 0),
                    json.optInt("gameCoins", 0));
        }
        catch (Throwable t) {
            return null;
        }
    }

    /** 记录签到成功（coinText 为今日签到记录里的金额文本，解析失败按 0） */
    public static synchronized void markSignIn(String coinText) {
        MagicBoxP2eStats cur = freshForUpdate();
        persist(new MagicBoxP2eStats(cur.date, true, parseCoin(coinText), cur.taskCoins, cur.gameCoins));
    }

    /** 记录一次领奖入账：kind 为「玩一玩」计游戏，其余计任务 */
    public static synchronized void addReceive(String kind, int coinAmount) {
        if (coinAmount <= 0) {
            return;
        }
        MagicBoxP2eStats cur = freshForUpdate();
        if ("玩一玩".equals(kind)) {
            persist(new MagicBoxP2eStats(cur.date, cur.signInDone, cur.signInCoins, cur.taskCoins, cur.gameCoins + coinAmount));
        }
        else {
            persist(new MagicBoxP2eStats(cur.date, cur.signInDone, cur.signInCoins, cur.taskCoins + coinAmount, cur.gameCoins));
        }
    }

    /** 更新前的当前值：不是今天的归零重开 */
    private static MagicBoxP2eStats freshForUpdate() {
        MagicBoxP2eStats cur = readFresh();
        if (cur == null || !cur.isToday()) {
            return new MagicBoxP2eStats(today(), false, 0, 0, 0);
        }
        return cur;
    }

    private static void persist(MagicBoxP2eStats stats) {
        try {
            JSONObject json = new JSONObject();
            json.put("date", stats.date);
            json.put("signInDone", stats.signInDone);
            json.put("signInCoins", stats.signInCoins);
            json.put("taskCoins", stats.taskCoins);
            json.put("gameCoins", stats.gameCoins);
            FileUtil.write2File(json.toString(2), getFile());
        }
        catch (Throwable ignored) {
        }
    }

    private static int parseCoin(String text) {
        if (text == null) {
            return 0;
        }
        try {
            return Integer.parseInt(text.trim());
        }
        catch (Throwable t) {
            return 0;
        }
    }
}
