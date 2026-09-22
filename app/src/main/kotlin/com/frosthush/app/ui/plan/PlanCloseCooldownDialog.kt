package com.frosthush.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.frosthush.app.R
import com.frosthush.app.data.FocusStore
import com.frosthush.app.ui.theme.LocalUiMode
import com.frosthush.app.ui.theme.UiMode
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 关闭计划的冷静期时长（秒）：倒计时结束前「确认关闭」保持禁用 */
internal const val PLAN_CLOSE_COOLDOWN_SECONDS = 90

/** 计划在这么多毫秒内就要开始 → 直接禁止关闭 */
internal const val PLAN_CLOSE_BLOCK_WINDOW_MS = 15 * 60 * 1000L

/**
 * 关闭计划前的「冷静期」对话框：计划不在 15 分钟内开始时会走这里，
 * 需要等 90 秒倒计时归零后才能确认关闭（防误关）。
 * material 版与 miuix 版业务逻辑逐字一致。
 */
@Composable
internal fun PlanCloseCooldownDialog(
    plan: FocusStore.FocusPlan?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> PlanCloseCooldownDialogMiuix(plan, onDismiss, onConfirm)
        UiMode.Material -> PlanCloseCooldownDialogMaterial(plan, onDismiss, onConfirm)
    }
}

/** 冷静期倒计时：以 planId 为 key，换计划或重开对话框都会重新计时 */
@Composable
private fun rememberCooldownLeft(planId: Long?): Int {
    var left by remember(planId) { mutableIntStateOf(PLAN_CLOSE_COOLDOWN_SECONDS) }
    LaunchedEffect(planId) {
        if (planId == null) return@LaunchedEffect
        left = PLAN_CLOSE_COOLDOWN_SECONDS
        while (left > 0) {
            delay(1000)
            left--
        }
    }
    return left
}

@Composable
private fun PlanCloseCooldownDialogMiuix(
    plan: FocusStore.FocusPlan?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val left = rememberCooldownLeft(plan?.id)
    OverlayDialog(
        show = plan != null,
        title = stringResource(R.string.plan_close_cooldown_title),
        onDismissRequest = onDismiss,
    ) {
        Column {
            top.yukonga.miuix.kmp.basic.Text(
                text = if (left > 0) stringResource(R.string.plan_close_cooldown_waiting, left)
                else stringResource(R.string.plan_close_cooldown_ready),
                style = MiuixTheme.textStyles.body1,
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
                    else stringResource(R.string.plan_close_cooldown_confirm),
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
    plan: FocusStore.FocusPlan?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (plan == null) return
    val left = rememberCooldownLeft(plan.id)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plan_close_cooldown_title)) },
        text = {
            Text(
                if (left > 0) stringResource(R.string.plan_close_cooldown_waiting, left)
                else stringResource(R.string.plan_close_cooldown_ready)
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = left <= 0) {
                Text(
                    if (left > 0) stringResource(R.string.plan_close_cooldown_wait_button, left)
                    else stringResource(R.string.plan_close_cooldown_confirm)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.plan_close_cooldown_cancel)) }
        },
    )
}