package com.frosthush.app.ui.plan

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.frosthush.app.R
import com.frosthush.app.data.FocusStore
import com.frosthush.app.focus.PlanCloseGuard
import com.frosthush.app.ui.theme.LocalUiMode
import com.frosthush.app.ui.theme.UiMode
import com.frosthush.app.util.Format
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 被守卫的操作类型：关闭（开关关掉）与删除共用同一套闸门，只有文案不同 */
internal enum class PlanGuardAction { CLOSE, DELETE }

/** 守卫对话框当前所处的阶段：当天会执行 → 先预约；预约生效 / 无需预约 → 90 秒冷静期 */
private enum class GuardStage { APPOINTMENT, COOLDOWN }

/** 预约对话框里一行计划的展示态 */
private data class GuardEntry(
    val plan: FocusStore.FocusPlan,
    val appointmentAt: Long?,
    val now: Long,
    val verdict: PlanCloseGuard.Verdict,
)

/**
 * 关闭 / 删除计划的守卫宿主：把 [PlanCloseGuard] 的判定结果翻译成两级对话框。
 *
 * - 非当天（或已过预约窗口）的计划直接进 90 秒冷静期；
 * - 当天会执行的计划先弹「预约」对话框：预约满 1 小时后出现 30 分钟可操作窗口，
 *   窗口内点确认继续走冷静期，超窗则回到待预约。
 *
 * [plans] 保留最后一次内容（dismiss 只把 [show] 置 false），以便 miuix 的 OverlayDialog
 * 正常播放退场动画；[session] 每次发起守卫自增，用于重置倒计时与阶段。
 */
@Composable
internal fun PlanGuardDialog(
    show: Boolean,
    session: Int,
    plans: List<FocusStore.FocusPlan>,
    action: PlanGuardAction,
    byAppointment: Boolean,
    onDismiss: () -> Unit,
    onCommit: (List<FocusStore.FocusPlan>) -> Unit,
) {
    if (plans.isEmpty()) return
    val context = LocalContext.current
    var stage by remember(session) {
        mutableStateOf(if (byAppointment) GuardStage.APPOINTMENT else GuardStage.COOLDOWN)
    }
    when (stage) {
        GuardStage.APPOINTMENT -> PlanAppointmentDialog(
            show = show,
            session = session,
            plans = plans,
            action = action,
            onDismiss = onDismiss,
            onReserved = { ids ->
                val at = System.currentTimeMillis()
                ids.forEach { FocusStore.setPlanAppointment(it, at) }
                Toast.makeText(context, R.string.plan_appointment_reserved, Toast.LENGTH_SHORT).show()
            },
            onReady = { stage = GuardStage.COOLDOWN },
        )

        GuardStage.COOLDOWN -> PlanCloseCooldownDialog(
            show = show,
            session = session,
            plans = plans,
            action = action,
            onDismiss = onDismiss,
            onConfirm = { onCommit(plans) },
        )
    }
}

/**
 * 当天会执行的计划：关闭 / 删除前必须先预约。
 * 每秒重算一次状态，倒计时与可操作窗口在对话框内实时更新。
 */
@Composable
private fun PlanAppointmentDialog(
    show: Boolean,
    session: Int,
    plans: List<FocusStore.FocusPlan>,
    action: PlanGuardAction,
    onDismiss: () -> Unit,
    onReserved: (List<Long>) -> Unit,
    onReady: () -> Unit,
) {
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
    val entries = plans.map { plan ->
        val appointmentAt = FocusStore.planAppointment(plan.id)
        GuardEntry(
            plan = plan,
            appointmentAt = appointmentAt,
            now = now,
            verdict = PlanCloseGuard.evaluate(
                plan = plan,
                now = now,
                executedToday = FocusStore.planExecutedDay(plan.id) == FocusStore.todayCode(),
                appointmentAt = appointmentAt,
            ),
        )
    }
    val toReserve = entries.filter {
        it.verdict.state == PlanCloseGuard.State.NEED_APPOINTMENT ||
            it.verdict.state == PlanCloseGuard.State.APPOINTMENT_EXPIRED
    }.map { it.plan.id }
    val allReady = entries.all { it.verdict.allowed }
    // 全部还在等待时，按钮显示"最近一次预约生效"的倒计时
    val waitingLeft = entries
        .filter { it.verdict.state == PlanCloseGuard.State.APPOINTMENT_WAITING }
        .minOfOrNull { it.verdict.remainingMs } ?: 0L

    when (LocalUiMode.current) {
        UiMode.Miuix -> PlanAppointmentDialogMiuix(
            show = show, entries = entries, action = action, allReady = allReady,
            toReserve = toReserve, waitingLeft = waitingLeft,
            onDismiss = onDismiss, onReserved = onReserved, onReady = onReady,
        )

        UiMode.Material -> PlanAppointmentDialogMaterial(
            show = show, entries = entries, action = action, allReady = allReady,
            toReserve = toReserve, waitingLeft = waitingLeft,
            onDismiss = onDismiss, onReserved = onReserved, onReady = onReady,
        )
    }
}

/** 行内状态文案：待预约 / 预约中倒计时 / 已过期 / 可操作窗口剩余 / 可直接操作 / 临近开始禁止 */
@Composable
private fun entryStateText(entry: GuardEntry): String = when (entry.verdict.state) {
    PlanCloseGuard.State.BLOCKED_NEAR_START -> {
        val minutes = ((entry.verdict.remainingMs + 59_999L) / 60_000L).coerceAtLeast(0L)
        stringResource(R.string.plan_appointment_state_blocked, minutes)
    }

    PlanCloseGuard.State.NEED_APPOINTMENT -> stringResource(R.string.plan_appointment_state_need)

    PlanCloseGuard.State.APPOINTMENT_WAITING ->
        stringResource(R.string.plan_appointment_state_waiting, Format.countdown(entry.verdict.remainingMs))

    PlanCloseGuard.State.APPOINTMENT_EXPIRED -> stringResource(R.string.plan_appointment_state_expired)

    PlanCloseGuard.State.ALLOWED ->
        if (entry.appointmentAt == null) stringResource(R.string.plan_appointment_state_direct)
        else stringResource(
            R.string.plan_appointment_state_ready,
            Format.countdown(PlanCloseGuard.windowLeftMillis(entry.appointmentAt, entry.now)),
        )
}

/** 预约对话框主按钮文案 */
@Composable
private fun confirmText(action: PlanGuardAction): String = stringResource(
    when (action) {
        PlanGuardAction.CLOSE -> R.string.plan_appointment_confirm_close
        PlanGuardAction.DELETE -> R.string.plan_appointment_confirm_delete
    }
)

@Composable
private fun PlanAppointmentDialogMiuix(
    show: Boolean,
    entries: List<GuardEntry>,
    action: PlanGuardAction,
    allReady: Boolean,
    toReserve: List<Long>,
    waitingLeft: Long,
    onDismiss: () -> Unit,
    onReserved: (List<Long>) -> Unit,
    onReady: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = stringResource(R.string.plan_appointment_title),
        summary = stringResource(R.string.plan_appointment_summary),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            entries.forEach { entry ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    top.yukonga.miuix.kmp.basic.Text(
                        text = entry.plan.name,
                        style = MiuixTheme.textStyles.body2,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    top.yukonga.miuix.kmp.basic.Text(
                        text = entryStateText(entry),
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Bold,
                        color = if (entry.verdict.allowed) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.error,
                    )
                }
            }
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
                        allReady -> confirmText(action)
                        toReserve.isNotEmpty() -> stringResource(R.string.plan_appointment_reserve)
                        else -> stringResource(R.string.plan_appointment_wait_button, Format.countdown(waitingLeft))
                    },
                    onClick = {
                        when {
                            allReady -> onReady()
                            toReserve.isNotEmpty() -> onReserved(toReserve)
                        }
                    },
                    enabled = allReady || toReserve.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun PlanAppointmentDialogMaterial(
    show: Boolean,
    entries: List<GuardEntry>,
    action: PlanGuardAction,
    allReady: Boolean,
    toReserve: List<Long>,
    waitingLeft: Long,
    onDismiss: () -> Unit,
    onReserved: (List<Long>) -> Unit,
    onReady: () -> Unit,
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plan_appointment_title)) },
        text = {
            Column {
                Text(stringResource(R.string.plan_appointment_summary))
                Spacer(Modifier.height(8.dp))
                entries.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry.plan.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = entryStateText(entry),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (entry.verdict.allowed) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        allReady -> onReady()
                        toReserve.isNotEmpty() -> onReserved(toReserve)
                    }
                },
                enabled = allReady || toReserve.isNotEmpty(),
            ) {
                Text(
                    when {
                        allReady -> confirmText(action)
                        toReserve.isNotEmpty() -> stringResource(R.string.plan_appointment_reserve)
                        else -> stringResource(R.string.plan_appointment_wait_button, Format.countdown(waitingLeft))
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.plan_close_cooldown_cancel)) }
        },
    )
}
