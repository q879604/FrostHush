package com.frosthush.app.focus

import com.frosthush.app.data.FocusStore.FocusPlan
import java.util.Calendar

/**
 * 关闭 / 删除计划的「防冲动」守卫（纯逻辑，无 Android 依赖，可 JVM 单元测试）。
 *
 * 两道硬闸 + 一道软闸：
 * 1. 当天会执行的计划 → 必须先「预约」（[APPOINTMENT_WAIT_MS]）：预约满 1 小时后，
 *    有 [APPOINTMENT_WINDOW_MS] 的窗口可以关闭/删除；超出窗口需重新预约。
 *    例：星期三 12:00 的计划，整个星期三想关掉它都要先预约。
 * 2. 非当天、但 [BLOCK_WINDOW_MS] 内就要开始 → 直接禁止（临开始前不许反悔式关闭/删除）。
 * 3. 过了上面两闸后，UI 还要走 [COOLDOWN_SECONDS] 秒冷静期对话框，倒计时归零才能确认。
 *
 * 关闭（开关关掉）与删除（长按多选删除）走完全相同的守卫，避免「删除」成为绕过口子。
 */
object PlanCloseGuard {
    /** 冷静期秒数：倒计时结束前「确认」按钮保持禁用 */
    const val COOLDOWN_SECONDS = 90

    /** 计划在这么多毫秒内就要开始 → 直接禁止关闭/删除 */
    const val BLOCK_WINDOW_MS = 15 * 60 * 1000L

    /** 预约后需要等待的时长（1 小时） */
    const val APPOINTMENT_WAIT_MS = 60 * 60 * 1000L

    /** 预约生效后的可操作窗口（30 分钟） */
    const val APPOINTMENT_WINDOW_MS = 30 * 60 * 1000L

    /** 守卫判定结果 */
    enum class State {
        /** 放行：进入冷静期对话框 */
        ALLOWED,

        /** 15 分钟内就要开始 → 直接禁止 */
        BLOCKED_NEAR_START,

        /** 当天会执行，但还没预约 */
        NEED_APPOINTMENT,

        /** 预约等待中（1 小时未满） */
        APPOINTMENT_WAITING,

        /** 预约已超过 30 分钟可操作窗口 → 需重新预约 */
        APPOINTMENT_EXPIRED,
    }

    /** 判定结果；[remainingMs] 为 BLOCKED_NEAR_START / APPOINTMENT_WAITING 的对应倒计时 */
    data class Verdict(val state: State, val remainingMs: Long = 0L) {
        val allowed: Boolean get() = state == State.ALLOWED

        /** 是否属于「当天需预约」这一类（要走预约对话框，而不是直接放行） */
        val appointmentGate: Boolean
            get() = state == State.NEED_APPOINTMENT ||
                state == State.APPOINTMENT_WAITING ||
                state == State.APPOINTMENT_EXPIRED
    }

    /**
     * 判定一次关闭 / 删除是否放行。
     * 「已停用的计划不再触发，不需要闸门」这条特例只对关闭/删除成立（见 [evaluateBase] 注释），
     * 编辑计划时必须用 [evaluateBase]，否则把「改完还是停用」也一起放行了。
     *
     * @param now 当前时刻
     * @param executedToday 该计划今天是否已经执行过（FocusStore.planExecutedDay == todayCode）
     * @param appointmentAt 该计划已存的预约时刻；未预约传 null
     */
    fun evaluate(
        plan: FocusPlan,
        now: Long,
        executedToday: Boolean,
        appointmentAt: Long?,
    ): Verdict {
        // 已停用的计划不会再触发，不需要守卫（删除仍会走冷静期）
        if (!plan.enabled) return Verdict(State.ALLOWED)
        return evaluateBase(plan, now, executedToday, appointmentAt)
    }

    /**
     * 基础闸（关闭 / 删除 / 改生效时间 / 改生效日期 / 改暂停对象共用）：
     * 1. 当天会执行 → 必须先预约（预约满 1 小时 → 30 分钟窗口 → 超窗重预约）；
     * 2. 非当天但 [BLOCK_WINDOW_MS] 内就要开始 → 直接禁止；
     * 3. 其余放行。
     *
     * **与 [evaluate] 的唯一区别**：不因 `enabled == false` 提前放行。编辑计划时必须走这条 ——
     * 把一个「当天已停用」的计划改到当天稍后时段，改完它就会真的执行，所以仍然要过闸。
     */
    fun evaluateBase(
        plan: FocusPlan,
        now: Long,
        executedToday: Boolean,
        appointmentAt: Long?,
    ): Verdict {
        if (occursToday(plan, now, executedToday)) {
            // 当天会执行：预约是唯一入口
            if (appointmentAt == null || appointmentAt > now) return Verdict(State.NEED_APPOINTMENT)
            val elapsed = now - appointmentAt
            if (elapsed < APPOINTMENT_WAIT_MS) {
                return Verdict(State.APPOINTMENT_WAITING, APPOINTMENT_WAIT_MS - elapsed)
            }
            if (elapsed > APPOINTMENT_WAIT_MS + APPOINTMENT_WINDOW_MS) {
                return Verdict(State.APPOINTMENT_EXPIRED)
            }
            return Verdict(State.ALLOWED)
        }

        val remaining = PlanScheduler.nextStartMillis(plan, now) - now
        if (remaining in 1..BLOCK_WINDOW_MS) return Verdict(State.BLOCKED_NEAR_START, remaining)
        return Verdict(State.ALLOWED)
    }

    /**
     * 该计划「今天会不会执行」：
     * - 正在执行中（含跨天 / 超长计划从昨天延续到今天）→ 算；
     * - 重复计划：今天是执行日 → 算（无论今天的开始时刻是否已过，整天都算）；
     * - 不重复计划：今天已执行过，或下一次触发就落在今天 → 算。
     */
    fun occursToday(plan: FocusPlan, now: Long, executedToday: Boolean): Boolean {
        val durationMillis = plan.durationMinutes * 60_000L
        if (PlanScheduler.inProgressEndMillis(plan, now, durationMillis) != null) return true
        if (plan.weekdays.isNotEmpty()) return weekdayOf(now) in plan.weekdays
        if (executedToday) return true
        return sameDay(now, PlanScheduler.nextStartMillis(plan, now))
    }

    /** 预约「可操作窗口」的剩余毫秒（已过期返回 0）；[appointmentAt] 为 null 返回 0 */
    fun windowLeftMillis(appointmentAt: Long?, now: Long): Long =
        if (appointmentAt == null) 0L
        else (appointmentAt + APPOINTMENT_WAIT_MS + APPOINTMENT_WINDOW_MS - now).coerceAtLeast(0L)

    /** Calendar 的星期（SUNDAY=1..SATURDAY=7）→ 计划星期（1=周一..7=周日） */
    private fun weekdayOf(millis: Long): Int {
        val dow = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
}
