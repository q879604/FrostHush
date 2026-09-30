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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.frosthush.app.R
import com.frosthush.app.data.FocusStore
import com.frosthush.app.focus.PlanCloseGuard
import com.frosthush.app.focus.PlanEditGuard
import com.frosthush.app.ui.theme.LocalUiMode
import com.frosthush.app.ui.theme.UiMode
import com.frosthush.app.util.Format
import kotlin.random.Random
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 冷静期期间励志语录的切换间隔（毫秒）：与关闭计划的冷静期保持一致 */
private const val EDIT_COOLDOWN_QUOTE_SWITCH_MS = 6_000L

/**
 * 修改计划「生效时间 / 生效日期 / 暂停对象」前的预约对话框。
 *
 * 判定见 [PlanEditGuard]：只有动到这几类字段、且被改的计划**今天会执行**时才弹；
 * 状态机与关闭 / 删除计划的预约对话框完全一致（待预约 → 预约中 → 30 分钟窗口 → 过期重预约）。
 *
 * @param onReserved 点击「预约」：把预约写盘（按计划 id）
 * @param onReady 预约已生效、点确认：交给宿主继续走冷静期
 */
@Composable
internal fun PlanEditAppointmentDialog(
    show: Boolean,
    session: Int,
    plan: FocusStore.FocusPlan?,
    reasons: List<PlanEditGuard.Reason>,
    onDismiss: () -> Unit,
    onReserved: (Long) -> Unit,
    onReady: () -> Unit,
) {
    if (plan == null) return
    val context = LocalContext.current
    var now by remember(session) { mutableLongStateOf(System.currentTimeMillis()) }
    // [show] 门控：对话框关闭后本组件仍在组合中（退场动画），不加门控会留下一个永不结束的每秒循环
    LaunchedEffect(show, session) {
        if (!show) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val appointmentAt = FocusStore.planAppointment(plan.id)
    val verdict = PlanCloseGuard.evaluateBase(
        plan = plan,
        now = now,
        executedToday = FocusStore.planExecutedDay(plan.id) == FocusStore.todayCode(),
        appointmentAt = appointmentAt,
    )
    when (LocalUiMode.current) {
        UiMode.Miuix -> PlanEditAppointmentDialogMiuix(
            show = show, planName = plan.name, reasons = reasons, verdict = verdict,
            appointmentAt = appointmentAt, now = now, onDismiss = onDismiss,
            onReserved = {
                onReserved(System.currentTimeMillis())
                Toast.makeText(context, R.string.plan_edit_guard_reserved, Toast.LENGTH_SHORT).show()
            },
            onReady = onReady,
        )

        UiMode.Material -> PlanEditAppointmentDialogMaterial(
            show = show, planName = plan.name, reasons = reasons, verdict = verdict,
            appointmentAt = appointmentAt, now = now, onDismiss = onDismiss,
            onReserved = {
                onReserved(System.currentTimeMillis())
                Toast.makeText(context, R.string.plan_edit_guard_reserved, Toast.LENGTH_SHORT).show()
            },
            onReady = onReady,
        )
    }
}

/**
 * 改这几类字段前的冷静期对话框（第二道软闸）：与关闭 / 删除计划共用同一套交互，
 * 倒计时归零前「确认」保持禁用，期间滚动展示随机励志语录。
 */
@Composable
internal fun PlanEditCooldownDialog(
    show: Boolean,
    session: Int,
    plan: FocusStore.FocusPlan?,
    reasons: List<PlanEditGuard.Reason>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (plan == null) return
    val quotes = stringArrayResource(R.array.plan_cooldown_quotes)
    var left by remember(session) { mutableIntStateOf(PlanCloseGuard.COOLDOWN_SECONDS) }
    var index by remember(session) {
        mutableIntStateOf(if (quotes.isEmpty()) 0 else Random.nextInt(quotes.size))
    }
    LaunchedEffect(show, session) {
        if (!show) return@LaunchedEffect
        left = PlanCloseGuard.COOLDOWN_SECONDS
        while (left > 0) {
            delay(1000)
            left--
        }
    }
    LaunchedEffect(show, session, quotes.size) {
        if (!show || quotes.size <= 1) return@LaunchedEffect
        while (true) {
            delay(EDIT_COOLDOWN_QUOTE_SWITCH_MS)
            index = (index + 1 + Random.nextInt(quotes.size - 1)) % quotes.size
        }
    }
    val quote = quotes.getOrElse(index) { "" }
    when (LocalUiMode.current) {
        UiMode.Miuix -> PlanEditCooldownDialogMiuix(
            show = show, planName = plan.name, reasons = reasons, left = left, quote = quote,
            onDismiss = onDismiss, onConfirm = onConfirm,
        )

        UiMode.Material -> PlanEditCooldownDialogMaterial(
            show = show, planName = plan.name, reasons = reasons, left = left, quote = quote,
            onDismiss = onDismiss, onConfirm = onConfirm,
        )
    }
}

/** 变更原因文案：一行一条（生效时间 / 生效日期 / 暂停对象） */
@Composable
private fun reasonText(reasons: List<PlanEditGuard.Reason>): String =
    reasons.joinToString("、") { reason ->
        stringResource(
            when (reason) {
                PlanEditGuard.Reason.TIME -> R.string.plan_edit_guard_reason_time
                PlanEditGuard.Reason.WEEKDAYS -> R.string.plan_edit_guard_reason_weekdays
                PlanEditGuard.Reason.TARGETS -> R.string.plan_edit_guard_reason_targets
                PlanEditGuard.Reason.CLOSE -> R.string.plan_edit_guard_reason_close
            }
        )
    }

/** 预约状态行：待预约 / 预约中倒计时 / 已过期 / 可操作窗口剩余 */
@Composable
private fun editStateText(
    verdict: PlanCloseGuard.Verdict,
    appointmentAt: Long?,
    now: Long,
): String = when (verdict.state) {
    PlanCloseGuard.State.APPOINTMENT_WAITING ->
        stringResource(R.string.plan_edit_guard_state_waiting, Format.countdown(verdict.remainingMs))

    PlanCloseGuard.State.APPOINTMENT_EXPIRED -> stringResource(R.string.plan_edit_guard_state_expired)

    PlanCloseGuard.State.BLOCKED_NEAR_START -> {
        val minutes = ((verdict.remainingMs + 59_999L) / 60_000L).coerceAtLeast(0L)
        stringResource(R.string.plan_edit_guard_state_blocked, minutes)
    }

    // 走到 ALLOWED 且还停在本对话框，说明预约窗口已生效（只剩窗口剩余时间）
    PlanCloseGuard.State.ALLOWED -> stringResource(
        R.string.plan_edit_guard_state_ready,
        Format.countdown(PlanCloseGuard.windowLeftMillis(appointmentAt, now)),
    )

    PlanCloseGuard.State.NEED_APPOINTMENT -> stringResource(R.string.plan_edit_guard_state_need)
}

@Composable
private fun PlanEditAppointmentDialogMiuix(
    show: Boolean,
    planName: String,
    reasons: List<PlanEditGuard.Reason>,
    verdict: PlanCloseGuard.Verdict,
    appointmentAt: Long?,
    now: Long,
    onDismiss: () -> Unit,
    onReserved: () -> Unit,
    onReady: () -> Unit,
) {
    val waiting = verdict.state == PlanCloseGuard.State.APPOINTMENT_WAITING
    OverlayDialog(
        show = show,
        title = stringResource(R.string.plan_edit_guard_title),
        summary = stringResource(
            R.string.plan_edit_guard_summary,
            planName,
            reasonText(reasons),
        ),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            top.yukonga.miuix.kmp.basic.Text(
                text = editStateText(verdict, appointmentAt, now),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                color = if (verdict.allowed) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
            top.yukonga.miuix.kmp.basic.Text(
                text = stringResource(R.string.plan_edit_guard_note),
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
                        verdict.allowed -> stringResource(R.string.plan_edit_guard_confirm)
                        waiting -> stringResource(
                            R.string.plan_edit_guard_wait_button,
                            Format.countdown(verdict.remainingMs),
                        )
                        verdict.state == PlanCloseGuard.State.BLOCKED_NEAR_START ->
                            stringResource(R.string.plan_edit_guard_blocked_button)

                        else -> stringResource(R.string.plan_appointment_reserve)
                    },
                    onClick = { if (verdict.allowed) onReady() else onReserved() },
                    enabled = verdict.allowed || (!waiting && verdict.state != PlanCloseGuard.State.BLOCKED_NEAR_START),
                    modifier = Modifier.weight(1f),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun PlanEditAppointmentDialogMaterial(
    show: Boolean,
    planName: String,
    reasons: List<PlanEditGuard.Reason>,
    verdict: PlanCloseGuard.Verdict,
    appointmentAt: Long?,
    now: Long,
    onDismiss: () -> Unit,
    onReserved: () -> Unit,
    onReady: () -> Unit,
) {
    if (!show) return
    val waiting = verdict.state == PlanCloseGuard.State.APPOINTMENT_WAITING
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plan_edit_guard_title)) },
        text = {
            Column {
                Text(stringResource(R.string.plan_edit_guard_summary, planName, reasonText(reasons)))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = editStateText(verdict, appointmentAt, now),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (verdict.allowed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.plan_edit_guard_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (verdict.allowed) onReady() else onReserved() },
                enabled = verdict.allowed || (!waiting && verdict.state != PlanCloseGuard.State.BLOCKED_NEAR_START),
            ) {
                Text(
                    when {
                        verdict.allowed -> stringResource(R.string.plan_edit_guard_confirm)
                        waiting -> stringResource(
                            R.string.plan_edit_guard_wait_button,
                            Format.countdown(verdict.remainingMs),
                        )
                        verdict.state == PlanCloseGuard.State.BLOCKED_NEAR_START ->
                            stringResource(R.string.plan_edit_guard_blocked_button)

                        else -> stringResource(R.string.plan_appointment_reserve)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.plan_close_cooldown_cancel)) }
        },
    )
}

@Composable
private fun PlanEditCooldownDialogMiuix(
    show: Boolean,
    planName: String,
    reasons: List<PlanEditGuard.Reason>,
    left: Int,
    quote: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = stringResource(R.string.plan_edit_guard_cooldown_title),
        onDismissRequest = onDismiss,
    ) {
        Column {
            top.yukonga.miuix.kmp.basic.Text(
                text = if (left > 0) quote else stringResource(R.string.plan_edit_guard_cooldown_ready),
                style = MiuixTheme.textStyles.body1,
            )
            Spacer(Modifier.height(6.dp))
            top.yukonga.miuix.kmp.basic.Text(
                text = stringResource(R.string.plan_edit_guard_cooldown_target, planName, reasonText(reasons)),
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
                    else stringResource(R.string.plan_edit_guard_cooldown_confirm),
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
private fun PlanEditCooldownDialogMaterial(
    show: Boolean,
    planName: String,
    reasons: List<PlanEditGuard.Reason>,
    left: Int,
    quote: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plan_edit_guard_cooldown_title)) },
        text = {
            Column {
                Text(if (left > 0) quote else stringResource(R.string.plan_edit_guard_cooldown_ready))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.plan_edit_guard_cooldown_target, planName, reasonText(reasons)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = left <= 0) {
                Text(
                    if (left > 0) stringResource(R.string.plan_close_cooldown_wait_button, left)
                    else stringResource(R.string.plan_edit_guard_confirm)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.plan_close_cooldown_cancel)) }
        },
    )
}
