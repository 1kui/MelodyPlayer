package com.melody.player.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** 项目自己的信息，出现在「关于」段。 */
object AboutInfo {
    const val REPO_URL = "https://github.com/1kui/MelodyPlayer"
    const val QQ_GROUP = "1106718126"
}

/**
 * 打开外部链接 / 拉起第三方 App。
 *
 * 统一收口是因为「拉起别的 App 失败」必须**有兜底**：`startActivity` 抛
 * [ActivityNotFoundException] 是常态（没装 QQ、没装浏览器），
 * 直接让它冒到界面上就是一个闪退，用户除了看见"应用无响应"什么都不知道。
 *
 * 每种跳转都配一条退化路径，失败时至少把关键信息（群号 / 网址）送到剪贴板，
 * 用户自己粘贴到 QQ 或浏览器里也能到达 —— 这比"未安装该应用"有用得多。
 */
object ExternalLinks {

    /** 仓库地址。能用系统浏览器打开就打开，否则复制到剪贴板。 */
    fun openRepo(context: Context): Boolean {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(AboutInfo.REPO_URL))
        return launch(context, view) {
            context.copyToClipboard(AboutInfo.REPO_URL, "已复制仓库地址")
        }
    }

    /**
     * 加入 QQ 群。
     *
     * 依次尝试两条 QQ 的拉群协议（新版 `qm/manage`、旧版 `dl/manage`），
     * 都不行（多半是没装 QQ）就把**群号复制到剪贴板**——
     * 这不是凑数的兜底，而是这条功能唯一在任何设备上都能到达的方式：
     * 用户粘进 QQ 的「加群」搜索框一样能进。
     */
    fun joinQqGroup(context: Context): Boolean {
        val group = AboutInfo.QQ_GROUP
        val schemes = listOf("mqqapi://qm/manage?groupcode=$group", "mqqapi://dl/manage?groupcode=$group")
        for (scheme in schemes) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(scheme))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch(context, intent, fallback = null)) return true
        }
        return context.copyToClipboard(group, "未安装 QQ，群号已复制")
    }

    /**
     * 尝试拉起 [intent]，失败时执行 [fallback]。
     *
     * 返回是否由 [intent] 成功处理。[fallback] 为 null 时失败即静默返回 false，
     * 供"多条协议逐个尝试"的场景使用 —— 那时真正的兜底在循环结束之后。
     */
    private fun launch(context: Context, intent: Intent, fallback: (() -> Unit)?): Boolean {
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            fallback?.invoke()
            false
        } catch (_: SecurityException) {
            // Android 11+ 包可见性：目标 App 不可见时也会走到这里
            fallback?.invoke()
            false
        } catch (_: IllegalArgumentException) {
            fallback?.invoke()
            false
        }
    }

    /** 复制到剪贴板并提示。系统剪贴板服务被禁用时返回 false。 */
    private fun Context.copyToClipboard(text: String, label: String): Boolean = runCatching {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        manager.setPrimaryClip(ClipData.newPlainText("Melody", text))
        Toast.makeText(this, "$label：$text", Toast.LENGTH_SHORT).show()
        true
    }.getOrDefault(false)
}

/** 「关于」段用的两个动作，供设置页直接接线。 */
@Composable
fun rememberAboutActions(): AboutActions {
    val context = LocalContext.current
    return AboutActions(
        onOpenRepo = { ExternalLinks.openRepo(context) },
        onJoinGroup = { ExternalLinks.joinQqGroup(context) }
    )
}

/** 把「打开仓库」与「加入 QQ 群」两个动作打包，避免设置页到处传 context。 */
data class AboutActions(
    val onOpenRepo: () -> Unit,
    val onJoinGroup: () -> Unit
)