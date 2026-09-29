package com.frosthush.app.ui.group

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.frosthush.app.R
import com.frosthush.app.data.FocusStore
import com.frosthush.app.focus.AppGroupGuard
import com.frosthush.app.focus.PlanCloseGuard
import com.frosthush.app.ui.theme.LocalUiMode
import com.frosthush.app.ui.theme.UiMode
import com.frosthush.app.util.Format
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 被守卫的应用集改动：移除应用 / 删除整个分类，共用同一套闸门，只有文案不同 */
internal enum class AppGroupGuardAction { REMOVE_APPS, DELETE_GROUP }

/**
 * 应用集（分类）「减法」守卫对话框。
 *
 * 触发条件：该应用集被**今天会执行的启用计划**引用，且本次操作是移除应用或删除分类。
 * 单纯添加应用不走这里（[AppGroupGuard] 里不设闸）。
 *
 * 状态与计划守卫一致：待预约 → 预约中倒计时 → 可操作窗口（25 分钟）→ 过期需重新预约。
 * 窗口内点击确认即执行真正的改动（[onCommit]），预约记录保留，因此窗口内可以反复增删。
 */
@Composable
internal fun AppGroupGuardDialog(
    show: Boolean,
    session: Int,
    group: FocusStore.AppGroup?,
    action: AppGroupGuardAction,
    onDismiss: () -> Unit,
    onCommit: () -> Unit,
) {
    if (group == null) return
    val context = LocalContext.current
    // 每秒重算预约状态；[show] 门控：对话框关闭后本组件仍在组合中（退场动画），
    // 不加门控会留下一个永不结束的每秒循环
    var now by remember(session) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(show, session) {
        if (!show) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val plans = FocusStore.focusPlans()
    val executedToday: (FocusStore.FocusPlan) -> Boolean = {
        FocusStore.planExecutedDay(it.id) == FocusStore.todayCode()
    }
    val appointmentAt = FocusStore.groupAppointment()
    val guarders = AppGroupGuard.guardingPlans(plans, group.id, now, executedToday).map { it.name }
    val verdict = AppGroupGuard.evaluate(plans, group.id, now, executedToday, appointmentAt)
    val windowLeft = AppGroupGuard.windowLeftMillis(appointmentAt, now)

    when (LocalUiMode.current) {
        UiMode.Miuix -> AppGroupGuardDialogMiuix(
            show = show,
            groupName = group.name,
            action = action,
            verdict = verdict,
            windowLeft = windowLeft,
            guarders = guarders,
            onDismiss = onDismiss,
            onReserved = {
                FocusStore.setGroupAppointment(System.currentTimeMillis())
                Toast.makeText(context, R.string.group_guard_reserved, Toast.LENGTH_SHORT).show()
            },
            onCommit = onCommit,
        )

        UiMode.Material -> AppGroupGuardDialogMaterial(
            show = show,
            groupName = group.name,
            action = action,
            verdict = verdict,
            windowLeft = windowLeft,
            guarders = guarders,
            onDismiss = onDismiss,
            onReserved = {
                FocusStore.setGroupAppointment(System.currentTimeMillis())
                Toast.makeText(context, R.string.group_guard_reserved, Toast.LENGTH_SHORT).show()
            },
            onCommit = onCommit,
        )
    }
}

/** 标题：移除应用 / 删除分类用不同措辞 */
@Composable
private fun guardTitle(action: AppGroupGuardAction): String = stringResource(
    when (action) {
        AppGroupGuardAction.REMOVE_APPS -> R.string.group_guard_title_remove
        AppGroupGuardAction.DELETE_GROUP -> R.string.group_guard_title_delete
    }
)

/** 预约状态行：待预约 / 预约中倒计时 / 已过期 / 可操作窗口剩余 */
@Composable
private fun guardStateText(verdict: PlanCloseGuard.Verdict, windowLeft: Long): String =
    when (verdict.state) {
        PlanCloseGuard.State.APPOINTMENT_WAITING ->
            stringResource(R.string.group_guard_state_waiting, Format.countdown(verdict.remainingMs))

        PlanCloseGuard.State.APPOINTMENT_EXPIRED -> stringResource(R.string.group_guard_state_expired)

        PlanCloseGuard.State.ALLOWED -> stringResource(R.string.group_guard_state_ready, Format.countdown(windowLeft))

        else -> stringResource(R.string.group_guard_state_need)
    }

/** 主按钮文案 */
@Composable
private fun guardConfirmText(action: AppGroupGuardAction): String = stringResource(
    when (action) {
        AppGroupGuardAction.REMOVE_APPS -> R.string.group_guard_confirm_remove
        AppGroupGuardAction.DELETE_GROUP -> R.string.group_guard_confirm_delete
    }
)

@Composable
private fun AppGroupGuardDialogMiuix(
    show: Boolean,
    groupName: String,
    action: AppGroupGuardAction,
    verdict: PlanCloseGuard.Verdict,
    windowLeft: Long,
    guarders: List<String>,
    onDismiss: () -> Unit,
    onReserved: () -> Unit,
    onCommit: () -> Unit,
) {
    val waiting = verdict.state == PlanCloseGuard.State.APPOINTMENT_WAITING
    OverlayDialog(
        show = show,
        title = guardTitle(action),
        summary = stringResource(R.string.group_guard_summary, groupName, guarders.joinToString("、")),
        onDismissRequest = onDismiss,
    ) {
        Column {
            top.yukonga.miuix.kmp.basic.Text(
                text = guardStateText(verdict, windowLeft),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                color = if (verdict.allowed) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
            top.yukonga.miuix.kmp.basic.Text(
                text = stringResource(R.string.group_guard_note),
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
                    text = when {
                        waiting -> stringResource(R.string.group_guard_wait_button, Format.countdown(verdict.remainingMs))
                        verdict.allowed -> guardConfirmText(action)
                        else -> stringResource(R.string.group_guard_reserve)
                    },
                    onClick = { if (verdict.allowed) onCommit() else onReserved() },
                    enabled = !waiting,
                    modifier = Modifier.weight(1f),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun AppGroupGuardDialogMaterial(
    show: Boolean,
    groupName: String,
    action: AppGroupGuardAction,
    verdict: PlanCloseGuard.Verdict,
    windowLeft: Long,
    guarders: List<String>,
    onDismiss: () -> Unit,
    onReserved: () -> Unit,
    onCommit: () -> Unit,
) {
    if (!show) return
    val waiting = verdict.state == PlanCloseGuard.State.APPOINTMENT_WAITING
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(guardTitle(action)) },
        text = {
            Column {
                Text(stringResource(R.string.group_guard_summary, groupName, guarders.joinToString("、")))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = guardStateText(verdict, windowLeft),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (verdict.allowed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.group_guard_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (verdict.allowed) onCommit() else onReserved() },
                enabled = !waiting,
            ) {
                Text(
                    when {
                        waiting -> stringResource(R.string.group_guard_wait_button, Format.countdown(verdict.remainingMs))
                        verdict.allowed -> guardConfirmText(action)
                        else -> stringResource(R.string.group_guard_reserve)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.plan_close_cooldown_cancel)) }
        },
    )
}
