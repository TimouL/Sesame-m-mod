package io.github.aw1y2z.sesame.ui.miuix

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.aw1y2z.sesame.data.AccountSwitchConfig
import io.github.aw1y2z.sesame.data.AccountSwitchState
import io.github.aw1y2z.sesame.util.ToastUtil
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 账号切换顺序编辑页：上下移动调整顺序，保存后写回公共配置文件 config/config_common.json。
 *
 * 账号列表由支付宝侧读取并合并进配置文件（模块启动时自动同步），这里只负责调整顺序。
 */
class MiuixAccountOrderActivity : MiuixBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppContent {
            AccountOrderScreen(this)
        }
    }
}

@Composable
fun AccountOrderScreen(activity: MiuixAccountOrderActivity) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 进入页面时的账号快照；顺序调整在本地做，确定后一次性写回
    var accounts by remember {
        mutableStateOf(
            AccountSwitchConfig.getAccountSwitchAccounts().map { it.copy() }
        )
    }

    Scaffold(
        topBar = {
            LogTopBar(
                title = "账号切换顺序",
                onBack = { activity.finish() }
            )
        },
        containerColor = MiuixTheme.colorScheme.surface
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            AccountSwitchStatusCard()
            Spacer(Modifier.height(12.dp))
            if (accounts.isEmpty()) {
                SmallTitle(text = "暂无账号")
                Text(
                    text = "启动模块后会自动读取支付宝已登录账号；也可先在支付宝登录多个账号再回到本页。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(12.dp))
            } else {
                SmallTitle(text = "切号顺序（第 1 个先执行，循环执行）")
                CardColumn {
                    accounts.forEachIndexed { index, account ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}. ${account.displayName}",
                                style = MiuixTheme.textStyles.headline1,
                                color = MiuixTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(text = "↑", onClick = {
                                if (index <= 0) return@TextButton
                                val tmp = accounts[index - 1]
                                accounts = accounts.toMutableList().also {
                                    it[index - 1] = accounts[index]
                                    it[index] = tmp
                                }
                            })
                            Spacer(Modifier.width(4.dp))
                            TextButton(text = "↓", onClick = {
                                if (index >= accounts.size - 1) return@TextButton
                                val tmp = accounts[index + 1]
                                accounts = accounts.toMutableList().also {
                                    it[index + 1] = accounts[index]
                                    it[index] = tmp
                                }
                            })
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(text = "保存", onClick = {
                        val saved = AccountSwitchConfig.setAccountSwitchAccounts(accounts)
                        if (saved) {
                            ToastUtil.show(context, "保存成功！")
                            activity.finish()
                        } else {
                            ToastUtil.show(context, "保存失败，请检查存储权限")
                        }
                    })
                }
            }
        }
    }
}

private fun formatClockTime(millis: Long): String =
    if (millis <= 0) "—" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

@Composable
private fun SwitchStatusRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(76.dp)
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 切号状态卡片：读调度器落盘的运行状态（account_switch_state.json），每 3 秒刷新一次。
 * 宿主进程不在时状态时间戳会停住，卡片据此提示「调度器可能未运行」，不会假装实时。
 */
@Composable
fun AccountSwitchStatusCard() {
    var config by remember { mutableStateOf(AccountSwitchConfig.read()) }
    var state by remember { mutableStateOf(AccountSwitchState.readFresh()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            config = AccountSwitchConfig.read()
            state = AccountSwitchState.readFresh()
        }
    }
    val now = System.currentTimeMillis()
    val accountsByUid = config.accountSwitchAccounts.associateBy { it.uid }
    fun display(uid: String?): String {
        if (uid.isNullOrEmpty()) return "—"
        return accountsByUid[uid]?.displayName ?: uid
    }

    SmallTitle(text = "切号状态")
    CardColumn {
        if (!config.isAccountSwitchEnabled) {
            SwitchStatusRow("状态", "自动切换登录账号未开启，在设置中开启后这里显示调度状态")
        } else {
            val phaseText = when (state.statusPhase) {
                "RUNNING" -> "运行中"
                "SWITCHING" -> "切换中"
                "CONFIRMING" -> "等待切换落地（对账中）"
                "ROUND_WAIT" -> "轮次等待中"
                else -> "未知"
            }
            val ageSec = if (state.statusUpdatedAt > 0) (now - state.statusUpdatedAt) / 1000 else -1L
            val ageText = when {
                ageSec < 0 -> ""
                ageSec < 10 -> "（刚刚更新）"
                ageSec <= 90 -> "（${ageSec} 秒前更新）"
                else -> "（已 ${ageSec / 60} 分钟未更新，调度器可能未运行）"
            }
            SwitchStatusRow("调度状态", phaseText + ageText)
            SwitchStatusRow("当前账号", display(state.statusUid))
            if (state.statusNextUid.isNotEmpty()) {
                SwitchStatusRow("下一目标", display(state.statusNextUid))
            }
            val roundInterval = config.accountSwitchRoundIntervalMinutes
            if (state.holdEnteredAt > 0 && roundInterval > 0
                && state.statusUid == config.accountSwitchFinalAccountUid
            ) {
                val remain = state.holdEnteredAt + roundInterval * 60_000L - now
                if (remain > 0) {
                    SwitchStatusRow("轮次等待", "保持账号等待中，还剩约 ${(remain + 59_999) / 60_000} 分钟")
                }
            }
            if (state.switchDisabledUntil > now) {
                SwitchStatusRow("切号熔断", "连续失败熔断中，${formatClockTime(state.switchDisabledUntil)} 后恢复")
            }
            config.accountSwitchAccounts
                .filter { state.isQuarantined(it.uid, now) }
                .forEach { account ->
                    val reasonText =
                        if (state.getQuarantineReason(account.uid) == AccountSwitchState.REASON_MANUAL_LOGIN) {
                            "需在支付宝手动登录一次，登录后自动解除"
                        } else {
                            "连续切换失败，${formatClockTime(state.getQuarantinedUntil(account.uid))} 后解除"
                        }
                    SwitchStatusRow("已隔离", "${account.displayName}（$reasonText）")
                }
            if (state.lastSwitchAt > 0) {
                val resultText = when {
                    state.lastSwitchResult == "ok" -> "成功"
                    state.lastSwitchResult == "scheduled_ok" -> "定时切换成功"
                    state.lastSwitchResult == "need_manual_login" -> "需手动登录"
                    state.lastSwitchResult == "method_missing" -> "切换方法缺失"
                    state.lastSwitchResult.startsWith("failed") -> "失败"
                    else -> state.lastSwitchResult
                }
                SwitchStatusRow(
                    "最近切换",
                    "${formatClockTime(state.lastSwitchAt)} " +
                        "${display(state.lastSwitchFrom)} → ${display(state.lastSwitchTo)}（$resultText）"
                )
            }
            if (state.statusEvent.isNotEmpty()) {
                SwitchStatusRow("最近事件", state.statusEvent)
            }
        }
    }
}
