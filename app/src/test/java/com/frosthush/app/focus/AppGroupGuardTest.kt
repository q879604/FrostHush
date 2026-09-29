package com.frosthush.app.focus

import com.frosthush.app.data.FocusStore.FocusPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * AppGroupGuard 回归测试（分类改动的防冲动守卫）：
 * - 只有「被今天会执行的**启用**计划引用」的分类才设闸；
 * - 设闸后：预约满 45 分钟 → 25 分钟可操作窗口 → 超窗重新预约；
 * - 没被引用的分类随时可改（纯添加应用在 UI 层直接放行，不进这里）。
 *
 * 2026-09-14 是周一，2026-09-16 是周三。
 */
class AppGroupGuardTest {

    private fun millisOf(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun plan(
        weekdays: Set<Int>,
        id: Long = 1,
        groupId: Long? = null,
        groupIds: List<Long>? = null,
        enabled: Boolean = true,
    ) = FocusPlan(
        id = id, name = "p$id", startMinute = 12 * 60, endMinute = 13 * 60,
        weekdays = weekdays, appGroupId = groupId, appGroupIds = groupIds, enabled = enabled,
    )

    private val noneExecuted: (FocusPlan) -> Boolean = { false }

    @Test
    fun `分类没被任何计划引用时不设闸`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = AppGroupGuard.evaluate(
            plans = listOf(plan(setOf(3), groupId = 7L)),
            groupId = 8L,
            now = wednesday,
            executedToday = noneExecuted,
            appointmentAt = null,
        )
        assertTrue(verdict.allowed)
        assertFalse(verdict.appointmentGate)
    }

    @Test
    fun `被今天会执行的启用计划引用时需要预约`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = AppGroupGuard.evaluate(
            plans = listOf(plan(setOf(3), groupId = 7L)),
            groupId = 7L,
            now = wednesday,
            executedToday = noneExecuted,
            appointmentAt = null,
        )
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, verdict.state)
        assertTrue(verdict.appointmentGate)
    }

    @Test
    fun `多应用集字段同样算引用`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = AppGroupGuard.evaluate(
            plans = listOf(plan(setOf(3), groupIds = listOf(4L, 7L))),
            groupId = 7L,
            now = wednesday,
            executedToday = noneExecuted,
            appointmentAt = null,
        )
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, verdict.state)
    }

    @Test
    fun `计划已关闭时不再保护该分类`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = AppGroupGuard.evaluate(
            plans = listOf(plan(setOf(3), groupId = 7L, enabled = false)),
            groupId = 7L,
            now = wednesday,
            executedToday = noneExecuted,
            appointmentAt = null,
        )
        assertTrue(verdict.allowed)
    }

    @Test
    fun `计划不在今天执行时不保护该分类`() {
        // 周三 9:00 看向一个只在周四执行的计划
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = AppGroupGuard.evaluate(
            plans = listOf(plan(setOf(4), groupId = 7L)),
            groupId = 7L,
            now = wednesday,
            executedToday = noneExecuted,
            appointmentAt = null,
        )
        assertTrue(verdict.allowed)
    }

    @Test
    fun `当天已执行过的不重复计划也算今天会执行`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = AppGroupGuard.evaluate(
            plans = listOf(plan(emptySet(), groupId = 7L)),
            groupId = 7L,
            now = wednesday,
            executedToday = { it.id == 1L },
            appointmentAt = null,
        )
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, verdict.state)
    }

    @Test
    fun `预约未满四十五分钟处于等待中并返回剩余`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = AppGroupGuard.evaluate(
            plans = listOf(plan(setOf(3), groupId = 7L)),
            groupId = 7L,
            now = wednesday,
            executedToday = noneExecuted,
            appointmentAt = wednesday - 15 * 60_000L,
        )
        assertEquals(PlanCloseGuard.State.APPOINTMENT_WAITING, verdict.state)
        assertEquals(30 * 60_000L, verdict.remainingMs)
    }

    @Test
    fun `恰好满四十五分钟进入可操作窗口`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = AppGroupGuard.evaluate(
            plans = listOf(plan(setOf(3), groupId = 7L)),
            groupId = 7L,
            now = wednesday,
            executedToday = noneExecuted,
            appointmentAt = wednesday - 45 * 60_000L,
        )
        assertTrue(verdict.allowed)
        assertEquals(25 * 60_000L, AppGroupGuard.windowLeftMillis(wednesday - 45 * 60_000L, wednesday))
    }

    @Test
    fun `窗口最后一刻仍放行`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = AppGroupGuard.evaluate(
            plans = listOf(plan(setOf(3), groupId = 7L)),
            groupId = 7L,
            now = wednesday,
            executedToday = noneExecuted,
            appointmentAt = wednesday - 70 * 60_000L,
        )
        assertTrue(verdict.allowed)
    }

    @Test
    fun `超过七十分钟需重新预约`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = AppGroupGuard.evaluate(
            plans = listOf(plan(setOf(3), groupId = 7L)),
            groupId = 7L,
            now = wednesday,
            executedToday = noneExecuted,
            appointmentAt = wednesday - 71 * 60_000L,
        )
        assertEquals(PlanCloseGuard.State.APPOINTMENT_EXPIRED, verdict.state)
        assertTrue(verdict.appointmentGate)
        assertEquals(0L, AppGroupGuard.windowLeftMillis(wednesday - 71 * 60_000L, wednesday))
    }

    @Test
    fun `预约时刻在未来视为未预约`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val verdict = AppGroupGuard.evaluate(
            plans = listOf(plan(setOf(3), groupId = 7L)),
            groupId = 7L,
            now = wednesday,
            executedToday = noneExecuted,
            appointmentAt = wednesday + 60_000L,
        )
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, verdict.state)
    }

    @Test
    fun `guardingPlans 只返回当天会执行的启用计划`() {
        val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)
        val plans = listOf(
            plan(setOf(3), id = 1L, groupIds = listOf(7L)),
            plan(setOf(3), id = 2L, groupIds = listOf(7L), enabled = false),
            plan(setOf(4), id = 3L, groupIds = listOf(7L)),
            plan(setOf(3), id = 4L, groupIds = listOf(9L)),
        )
        val guarders = AppGroupGuard.guardingPlans(plans, 7L, wednesday, noneExecuted)
        assertEquals(listOf(1L), guarders.map { it.id })
    }
}
