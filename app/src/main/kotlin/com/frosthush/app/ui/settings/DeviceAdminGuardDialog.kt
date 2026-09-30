package com.frosthush.app.ui.settings

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
import com.frosthush.app.focus.DeviceAdmin
import com.frosthush.app.focus.DeviceAdminGuard
import com.frosthush.app.focus.PlanCloseGuard
import com.frosthush.app.ui.theme.LocalUiMode
import com.frosthush.app.ui.theme.UiMode
import com.frosthush.app.util.Format
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「关闭设备管理员」守卫对话框。
 *
 * 设备管理员是计划保活的最后一道保险（激活后系统不允许强行停止本应用），
 * 随手关掉等于悄悄拆掉所有计划的保活，所以关闭前必须先预约：
 * 预约满 1 小时 → 20 分钟可操作窗口 → 超窗重新预约。
 *
 * **单独预约**：预约记录走 [FocusStore.deviceAdminAppointment] 这一份独立文件，
 * 与计划预约、应用分类预约完全隔离 —— 预约生效后只解锁「关闭设备管理员」，
 * 不会同步解锁其他任何权限。打开设备管理员不经过这里（不设闸）。
 */
@Composable
internal fun DeviceAdminGuardDialog(
    show: Boolean,
    session: Int,
    onDismiss: () -> Unit,
    onCommit: () -> Unit,
) {
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
    val appointmentAt = FocusStore.deviceAdminAppointment()
    val active = DeviceAdmin.isActive(context)
    // 对话框只在「已激活、正在申请关闭」时出现，故 active 恒为 true；仍按真实状态判定，
    // 这样万一在别处已被关掉，这里会立刻判定为放行而不是卡住。
    val verdict = DeviceAdminGuard.evaluate(active, now, appointmentAt)
    val windowLeft = DeviceAdminGuard.windowLeftMillis(appointmentAt, now)

    when (LocalUiMode.current) {
        UiMode.Miuix -> DeviceAdminGuardDialogMiuix(
            show = show,
            verdict = verdict,
            windowLeft = windowLeft,
            onDismiss = onDismiss,
            onReserved = {
                FocusStore.setDeviceAdminAppointment(System.currentTimeMillis())
                Toast.makeText(context, R.string.device_admin_guard_reserved, Toast.LENGTH_SHORT).show()
            },
            onCommit = onCommit,
        )

        UiMode.Material -> DeviceAdminGuardDialogMaterial(
            show = show,
            verdict = verdict,
            windowLeft = windowLeft,
            onDismiss = onDismiss,
            onReserved = {
                FocusStore.setDeviceAdminAppointment(System.currentTimeMillis())
                Toast.makeText(context, R.string.device_admin_guard_reserved, Toast.LENGTH_SHORT).show()
            },
            onCommit = onCommit,
        )
    }
}

/** 预约状态行：待预约 / 预约中倒计时 / 已过期 / 可操作窗口剩余 */
@Composable
private fun adminStateText(verdict: PlanCloseGuard.Verdict, windowLeft: Long): String =
    when (verdict.state) {
        PlanCloseGuard.State.APPOINTMENT_WAITING ->
            stringResource(R.string.device_admin_guard_state_waiting, Format.countdown(verdict.remainingMs))

        PlanCloseGuard.State.APPOINTMENT_EXPIRED ->
            stringResource(R.string.device_admin_guard_state_expired)

        PlanCloseGuard.State.ALLOWED ->
            stringResource(R.string.device_admin_guard_state_ready, Format.countdown(windowLeft))

        // NEED_APPOINTMENT，以及设备管理员关闭不会出现的 BLOCKED_NEAR_START
        else -> stringResource(R.string.device_admin_guard_state_need)
    }

@Composable
private fun DeviceAdminGuardDialogMiuix(
    show: Boolean,
    verdict: PlanCloseGuard.Verdict,
    windowLeft: Long,
    onDismiss: () -> Unit,
    onReserved: () -> Unit,
    onCommit: () -> Unit,
) {
    val waiting = verdict.state == PlanCloseGuard.State.APPOINTMENT_WAITING
    OverlayDialog(
        show = show,
        title = stringResource(R.string.device_admin_guard_title),
        summary = stringResource(R.string.device_admin_guard_summary),
        onDismissRequest = onDismiss,
    ) {
        Column {
            top.yukonga.miuix.kmp.basic.Text(
                text = adminStateText(verdict, windowLeft),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                color = if (verdict.allowed) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
            top.yukonga.miuix.kmp.basic.Text(
                text = stringResource(R.string.device_admin_guard_note),
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
                        waiting -> stringResource(
                            R.string.device_admin_guard_wait_button,
                            Format.countdown(verdict.remainingMs),
                        )
                        verdict.allowed -> stringResource(R.string.device_admin_guard_confirm)
                        else -> stringResource(R.string.device_admin_guard_reserve)
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
private fun DeviceAdminGuardDialogMaterial(
    show: Boolean,
    verdict: PlanCloseGuard.Verdict,
    windowLeft: Long,
    onDismiss: () -> Unit,
    onReserved: () -> Unit,
    onCommit: () -> Unit,
) {
    if (!show) return
    val waiting = verdict.state == PlanCloseGuard.State.APPOINTMENT_WAITING
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_admin_guard_title)) },
        text = {
            Column {
                Text(stringResource(R.string.device_admin_guard_summary))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = adminStateText(verdict, windowLeft),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (verdict.allowed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.device_admin_guard_note),
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
                        waiting -> stringResource(
                            R.string.device_admin_guard_wait_button,
                            Format.countdown(verdict.remainingMs),
                        )
                        verdict.allowed -> stringResource(R.string.device_admin_guard_confirm)
                        else -> stringResource(R.string.device_admin_guard_reserve)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.plan_close_cooldown_cancel)) }
        },
    )
}
