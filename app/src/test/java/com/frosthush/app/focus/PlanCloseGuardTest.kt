package com.frosthush.app.focus

import com.frosthush.app.data.FocusStore.FocusPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * PlanCloseGuard 回归测试（关闭 / 删除计划的防冲动守卫）：
 * - 当天会执行的计划：必须先预约，预约满 1 小时后有 30 分钟可操作窗口，超窗重新预约
 * - 非当天但 15 分钟内就要开始：直接禁止
 * - 已停用的计划不设闸（它不会再触发）
 *
 * 2026-09-14 是周一，2026-09-16 是周三。
 */
class PlanCloseGuardTest {

    private fun millisOf(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun plan(
        startMinute: Int,
        endMinute: Int,
        weekdays: Set<Int>,
        enabled: Boolean = true,
    ) = FocusPlan(
        id = 1, name = "test", startMinute = startMinute, endMinute = endMinute,
        weekdays = weekdays, enabled = enabled,
    )

    // ---------- 当天会执行 → 预约闸 ----------

    @Test
    fun `周三当天的计划未预约时要求先预约`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = PlanCloseGuard.evaluate(
            plan = plan(12 * 60, 13 * 60, setOf(3)),
            now = wednesday,
            executedToday = false,
            appointmentAt = null,
        )
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, verdict.state)
        assertTrue(verdict.appointmentGate)
    }

    @Test
    fun `预约未满一小时仍在等待（剩余时间递减）`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = PlanCloseGuard.evaluate(
            plan = plan(12 * 60, 13 * 60, setOf(3)),
            now = wednesday,
            executedToday = false,
            appointmentAt = wednesday - 10 * 60_000L,
        )
        assertEquals(PlanCloseGuard.State.APPOINTMENT_WAITING, verdict.state)
        assertEquals(50 * 60_000L, verdict.remainingMs)
    }

    @Test
    fun `预约满一小时且在半小时窗口内放行`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = PlanCloseGuard.evaluate(
            plan = plan(12 * 60, 13 * 60, setOf(3)),
            now = wednesday,
            executedToday = false,
            appointmentAt = wednesday - 61 * 60_000L,
        )
        assertEquals(PlanCloseGuard.State.ALLOWED, verdict.state)
    }

    @Test
    fun `预约窗口边界：恰好一小时放行、恰好一小时半仍放行`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val plan = plan(12 * 60, 13 * 60, setOf(3))
        assertEquals(
            PlanCloseGuard.State.ALLOWED,
            PlanCloseGuard.evaluate(plan, wednesday, false, wednesday - 60 * 60_000L).state,
        )
        assertEquals(
            PlanCloseGuard.State.ALLOWED,
            PlanCloseGuard.evaluate(plan, wednesday, false, wednesday - 90 * 60_000L).state,
        )
    }

    @Test
    fun `超过半小时窗口需重新预约`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = PlanCloseGuard.evaluate(
            plan = plan(12 * 60, 13 * 60, setOf(3)),
            now = wednesday,
            executedToday = false,
            appointmentAt = wednesday - 91 * 60_000L,
        )
        assertEquals(PlanCloseGuard.State.APPOINTMENT_EXPIRED, verdict.state)
        assertEquals(0L, PlanCloseGuard.windowLeftMillis(wednesday - 91 * 60_000L, wednesday))
    }

    @Test
    fun `预约时刻在未来时视为未预约`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = PlanCloseGuard.evaluate(
            plan = plan(12 * 60, 13 * 60, setOf(3)),
            now = wednesday,
            executedToday = false,
            appointmentAt = wednesday + 60 * 60_000L,
        )
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, verdict.state)
    }

    @Test
    fun `当天开始时刻已过也仍需预约（整天都要预约）`() {
        // 周三 20:00 想关掉当天 12:00 已经跑过的计划
        val wednesdayNight = millisOf(2026, Calendar.SEPTEMBER, 16, 20, 0)
        val verdict = PlanCloseGuard.evaluate(
            plan = plan(12 * 60, 13 * 60, setOf(3)),
            now = wednesdayNight,
            executedToday = true,
            appointmentAt = null,
        )
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, verdict.state)
    }

    @Test
    fun `正在执行中的计划算当天`() {
        val mondayMorning = millisOf(2026, Calendar.SEPTEMBER, 14, 8, 30)
        val verdict = PlanCloseGuard.evaluate(
            plan = plan(8 * 60, 9 * 60, setOf(1)),
            now = mondayMorning,
            executedToday = true,
            appointmentAt = null,
        )
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, verdict.state)
    }

    @Test
    fun `跨午夜计划延续到次日凌晨仍算当天`() {
        // 周日 23:00 开始、180 分钟 → 周一 00:30 仍在执行
        val mondayEarly = millisOf(2026, Calendar.SEPTEMBER, 14, 0, 30)
        val verdict = PlanCloseGuard.evaluate(
            plan = plan(23 * 60, 2 * 60, setOf(7)),
            now = mondayEarly,
            executedToday = false,
            appointmentAt = null,
        )
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, verdict.state)
    }

    @Test
    fun `不重复计划当天已执行过也算当天`() {
        val saturdayNoon = millisOf(2026, Calendar.SEPTEMBER, 12, 12, 0)
        val verdict = PlanCloseGuard.evaluate(
            plan = plan(8 * 60, 9 * 60, emptySet()),
            now = saturdayNoon,
            executedToday = true,
            appointmentAt = null,
        )
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, verdict.state)
    }

    @Test
    fun `不重复计划下一次触发在明天则不设预约闸`() {
        val saturdayNoon = millisOf(2026, Calendar.SEPTEMBER, 12, 12, 0)
        val verdict = PlanCloseGuard.evaluate(
            plan = plan(8 * 60, 9 * 60, emptySet()),
            now = saturdayNoon,
            executedToday = false,
            appointmentAt = null,
        )
        assertEquals(PlanCloseGuard.State.ALLOWED, verdict.state)
    }

    // ---------- 非当天 → 15 分钟禁操作闸 ----------

    @Test
    fun `非当天但十五分钟内就要开始禁止操作`() {
        // 周一 23:55，计划是周二 00:05 开始 → 不属于"今天"，但 10 分钟后就触发
        val mondayLate = millisOf(2026, Calendar.SEPTEMBER, 14, 23, 55)
        val verdict = PlanCloseGuard.evaluate(
            plan = plan(5, 8 * 60, setOf(2)),
            now = mondayLate,
            executedToday = false,
            appointmentAt = null,
        )
        assertEquals(PlanCloseGuard.State.BLOCKED_NEAR_START, verdict.state)
        assertEquals(10 * 60_000L, verdict.remainingMs)
    }

    @Test
    fun `非当天且超过十五分钟放行`() {
        val mondayNight = millisOf(2026, Calendar.SEPTEMBER, 14, 23, 0)
        val verdict = PlanCloseGuard.evaluate(
            plan = plan(5, 8 * 60, setOf(2)),
            now = mondayNight,
            executedToday = false,
            appointmentAt = null,
        )
        assertEquals(PlanCloseGuard.State.ALLOWED, verdict.state)
    }

    @Test
    fun `十五分钟边界：恰好十五分钟仍禁止`() {
        val mondayLate = millisOf(2026, Calendar.SEPTEMBER, 14, 23, 50)
        val verdict = PlanCloseGuard.evaluate(
            plan = plan(5, 8 * 60, setOf(2)),
            now = mondayLate,
            executedToday = false,
            appointmentAt = null,
        )
        assertEquals(PlanCloseGuard.State.BLOCKED_NEAR_START, verdict.state)
        assertEquals(15 * 60_000L, verdict.remainingMs)
    }

    // ---------- 其它 ----------

    @Test
    fun `已停用的计划不设闸（当天也不会触发）`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = PlanCloseGuard.evaluate(
            plan = plan(12 * 60, 13 * 60, setOf(3), enabled = false),
            now = wednesday,
            executedToday = false,
            appointmentAt = null,
        )
        assertEquals(PlanCloseGuard.State.ALLOWED, verdict.state)
        assertFalse(verdict.appointmentGate)
    }

    @Test
    fun `可操作窗口剩余时间按时长上限截断`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val at = wednesday - 61 * 60_000L
        assertEquals(29 * 60_000L, PlanCloseGuard.windowLeftMillis(at, wednesday))
    }

    @Test
    fun `occursToday 对当天执行日返回真`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        assertTrue(PlanCloseGuard.occursToday(plan(12 * 60, 13 * 60, setOf(3)), wednesday, false))
        assertFalse(PlanCloseGuard.occursToday(plan(12 * 60, 13 * 60, setOf(2)), wednesday, false))
    }
}
