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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.aw1y2z.sesame.data.AccountSwitchConfig
import io.github.aw1y2z.sesame.util.ToastUtil
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
