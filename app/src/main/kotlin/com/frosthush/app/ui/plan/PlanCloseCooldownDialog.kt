package com.frosthush.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.frosthush.app.R
import com.frosthush.app.data.FocusStore
import com.frosthush.app.focus.PlanCloseGuard
import com.frosthush.app.ui.theme.LocalUiMode
import com.frosthush.app.ui.theme.UiMode
import kotlin.random.Random
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 冷静期期间励志语录的切换间隔（毫秒） */
private const val COOLDOWN_QUOTE_SWITCH_MS = 6_000L

/**
 * 关闭 / 删除计划前的「冷静期」对话框（第二道软闸）：
 * 倒计时归零前「确认」按钮保持禁用；冷静期期间上方文案滚动展示随机励志语录
 * （不显示"还剩多少秒"以外的催促文案，用正向激励替代冷冰冰的提示）。
 * material 版与 miuix 版业务逻辑逐字一致。
 *
 * @param session 每次发起守卫时自增：作为倒计时与随机语录的重置 key
 *                （同一批计划被取消后重新发起时，倒计时必须重新开始）
 */
@Composable
internal fun PlanCloseCooldownDialog(
    show: Boolean,
    session: Int,
    plans: List<FocusStore.FocusPlan>,
    action: PlanGuardAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> PlanCloseCooldownDialogMiuix(show, session, plans, action, onDismiss, onConfirm)
        UiMode.Material -> PlanCloseCooldownDialogMaterial(show, session, plans, action, onDismiss, onConfirm)
    }
}

/**
 * 冷静期倒计时：以 session 为 key，重新发起守卫会重新计时。
 * [show] 门控必不可少——对话框关闭后本组件仍被组合（为了退场动画），
 * 不加门控会留下一个永不结束的每秒循环。
 */
@Composable
private fun rememberCooldownLeft(show: Boolean, session: Int): Int {
    var left by remember(session) { mutableIntStateOf(PlanCloseGuard.COOLDOWN_SECONDS) }
    LaunchedEffect(show, session) {
        if (!show) return@LaunchedEffect
        left = PlanCloseGuard.COOLDOWN_SECONDS
        while (left > 0) {
            delay(1000)
            left--
        }
    }
    return left
}

/** 冷静期期间滚动的随机励志语录：换一条时避免与上一条重复（相邻项不撞车） */
@Composable
private fun rememberCooldownQuote(show: Boolean, session: Int): String {
    val quotes = stringArrayResource(R.array.plan_cooldown_quotes)
    var index by remember(session) {
        mutableIntStateOf(if (quotes.isEmpty()) 0 else Random.nextInt(quotes.size))
    }
    LaunchedEffect(show, session) {
        if (!show || quotes.size <= 1) return@LaunchedEffect
        while (true) {
            delay(COOLDOWN_QUOTE_SWITCH_MS)
            index = (index + 1 + Random.nextInt(quotes.size - 1)) % quotes.size
        }
    }
    return quotes.getOrElse(index) { "" }
}

/** 对话框标题：关闭 / 删除用不同措辞，其余逻辑完全共用 */
@Composable
private fun cooldownTitle(action: PlanGuardAction): String = stringResource(
    when (action) {
        PlanGuardAction.CLOSE -> R.string.plan_close_cooldown_title
        PlanGuardAction.DELETE -> R.string.plan_delete_cooldown_title
    }
)

/** 倒计时归零后的就绪文案 */
@Composable
private fun cooldownReadyText(action: PlanGuardAction): String = stringResource(
    when (action) {
        PlanGuardAction.CLOSE -> R.string.plan_close_cooldown_ready
        PlanGuardAction.DELETE -> R.string.plan_delete_cooldown_ready
    }
)

/** 确认按钮文案 */
@Composable
private fun cooldownConfirmText(action: PlanGuardAction): String = stringResource(
    when (action) {
        PlanGuardAction.CLOSE -> R.string.plan_close_cooldown_confirm
        PlanGuardAction.DELETE -> R.string.plan_delete_cooldown_confirm
    }
)

/** 操作目标（计划名）展示文案 */
private fun targetsText(plans: List<FocusStore.FocusPlan>): String =
    plans.joinToString("、") { it.name }

@Composable
private fun PlanCloseCooldownDialogMiuix(
    show: Boolean,
    session: Int,
    plans: List<FocusStore.FocusPlan>,
    action: PlanGuardAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val left = rememberCooldownLeft(show, session)
    val quote = rememberCooldownQuote(show, session)
    OverlayDialog(
        show = show,
        title = cooldownTitle(action),
        onDismissRequest = onDismiss,
    ) {
        Column {
            top.yukonga.miuix.kmp.basic.Text(
                text = if (left > 0) quote else cooldownReadyText(action),
                style = MiuixTheme.textStyles.body1,
            )
            Spacer(Modifier.height(6.dp))
            top.yukonga.miuix.kmp.basic.Text(
                text = stringResource(R.string.plan_cooldown_targets, targetsText(plans)),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                top.yukonga.miuix.kmp.basic.TextButton(
                    text = stringResource(R.string.plan_close_cooldown_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                top.yukonga.miuix.kmp.basic.TextButton(
                    text = if (left > 0) stringResource(R.string.plan_close_cooldown_wait_button, left)
                    else cooldownConfirmText(action),
                    onClick = onConfirm,
                    enabled = left <= 0,
                    modifier = Modifier.weight(1f),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun PlanCloseCooldownDialogMaterial(
    show: Boolean,
    session: Int,
    plans: List<FocusStore.FocusPlan>,
    action: PlanGuardAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!show) return
    val left = rememberCooldownLeft(show, session)
    val quote = rememberCooldownQuote(show, session)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(cooldownTitle(action)) },
        text = {
            Column {
                Text(if (left > 0) quote else cooldownReadyText(action))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.plan_cooldown_targets, targetsText(plans)),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = left <= 0) {
                Text(
                    if (left > 0) stringResource(R.string.plan_close_cooldown_wait_button, left)
                    else cooldownConfirmText(action)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.plan_close_cooldown_cancel)) }
        },
    )
}
