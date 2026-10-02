package com.frosthush.app.focus

import com.frosthush.app.data.FocusStore.FocusPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * PlanEditGuard 回归测试（改生效时间 / 生效日期 / 暂停对象要和关闭计划一样过闸）：
 * - 只有动到受保护字段（含分段结构、启用改停用）才设闸，只改名字不设闸；
 * - 动到受保护字段就一定要过闸（非当天的改动也走 90 秒冷静期，与列表里关计划一致）；
 * - 当天会执行 → 预约闸（1 小时等待 + 30 分钟窗口）；非当天但 15 分钟内开始 → 直接禁止；
 * - 已停用的计划改时间只需冷静期、不用预约（与关闭计划同口径），
 *   但「改完之后今天会执行」（挪到今天 / 停用改到今天并启用）同样要过预约闸；
 * - 预约记录与关闭计划共用同一份：关闭用的预约，编辑守卫读到的是同一个值。
 *
 * 2026-09-16 是周三。
 */
class PlanEditGuardTest {

    private fun millisOf(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val wednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 9, 0)

    private fun plan(
        id: Long = 1,
        name: String = "p",
        startMinute: Int = 12 * 60,
        endMinute: Int = 13 * 60,
        weekdays: Set<Int> = setOf(3),
        groupIds: List<Long>? = listOf(7L),
        directEntries: List<String>? = null,
        enabled: Boolean = true,
    ) = FocusPlan(
        id = id, name = name, startMinute = startMinute, endMinute = endMinute,
        weekdays = weekdays, appGroupIds = groupIds, directEntries = directEntries, enabled = enabled,
    )

    private fun evaluate(
        old: FocusPlan?,
        new: FocusPlan,
        appointmentAt: Long? = null,
        executedToday: Boolean = false,
        now: Long = wednesday,
    ) = PlanEditGuard.evaluate(old, new, now, executedToday, appointmentAt)

    // ---------- 什么改动要设闸 ----------

    @Test
    fun `改生效时间要设闸`() {
        val old = plan()
        val v = evaluate(old, old.copy(startMinute = 20 * 60, endMinute = 21 * 60))
        assertTrue(v.guarded)
        assertEquals(listOf(PlanEditGuard.Reason.TIME), v.reasons)
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, v.verdicts[old.id]?.state)
    }

    @Test
    fun `改生效日期要设闸`() {
        val old = plan()
        val v = evaluate(old, old.copy(weekdays = setOf(3, 4)))
        assertTrue(v.guarded)
        assertEquals(listOf(PlanEditGuard.Reason.WEEKDAYS), v.reasons)
    }

    @Test
    fun `改绑定的应用集要设闸`() {
        val old = plan()
        val v = evaluate(old, old.copy(appGroupIds = listOf(8L)))
        assertTrue(v.guarded)
        assertEquals(listOf(PlanEditGuard.Reason.TARGETS), v.reasons)
    }

    @Test
    fun `改直选应用要设闸`() {
        val old = plan(groupIds = null, directEntries = listOf("com.a"))
        val v = evaluate(old, old.copy(directEntries = listOf("com.a", "com.b")))
        assertTrue(v.guarded)
        assertEquals(listOf(PlanEditGuard.Reason.TARGETS), v.reasons)
    }

    @Test
    fun `只改名字不设闸`() {
        val old = plan()
        assertFalse(evaluate(old, old.copy(name = "新名字")).guarded)
    }

    @Test
    fun `改分段顺序要设闸（起止时间不变但暂停节奏变了）`() {
        val old = plan().copy(
            segments = listOf(seg(ROLE_FOCUS, 30), seg(ROLE_REST, 10), seg(ROLE_FOCUS, 20)),
        )
        val new = old.copy(
            segments = listOf(seg(ROLE_FOCUS, 20), seg(ROLE_REST, 10), seg(ROLE_FOCUS, 30)),
        )
        val v = evaluate(old, new)
        assertTrue(v.guarded)
        assertEquals(listOf(PlanEditGuard.Reason.TIME), v.reasons)
    }

    @Test
    fun `分段完全没变时不设闸`() {
        val old = plan().copy(segments = listOf(seg(ROLE_FOCUS, 60)))
        assertFalse(evaluate(old, old.copy(name = "新名字")).guarded)
    }

    @Test
    fun `编辑页关闭计划（启用改停用）要设闸`() {
        val old = plan()
        val v = evaluate(old, old.copy(enabled = false))
        assertTrue(v.guarded)
        assertEquals(listOf(PlanEditGuard.Reason.CLOSE), v.reasons)
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, v.verdicts[old.id]?.state)
    }

    @Test
    fun `打开已停用的计划不设闸`() {
        val old = plan(enabled = false)
        assertFalse(evaluate(old, old.copy(enabled = true)).guarded)
    }

    @Test
    fun `新建计划不设闸`() {
        assertFalse(evaluate(null, plan()).guarded)
    }

    // ---------- 哪些计划要设闸 ----------

    @Test
    fun `今天不执行的计划改时间不用预约但要走冷静期`() {
        // 周三 9:00 看一个只在周四执行的计划：离开始还早 → 不拦预约闸，但仍需冷静期
        val old = plan(weekdays = setOf(4))
        val v = evaluate(old, old.copy(startMinute = 20 * 60, endMinute = 21 * 60))
        assertTrue(v.guarded)
        assertTrue(v.allowed)
        assertEquals(PlanCloseGuard.State.ALLOWED, v.verdicts[old.id]?.state)
    }

    @Test
    fun `已停用的计划改时间要过闸但不用预约（与关闭计划同口径）`() {
        // 停用的计划本来就不执行，改它的时间不影响当天 → 只需冷静期
        val old = plan(enabled = false)
        val v = evaluate(old, old.copy(startMinute = 20 * 60, endMinute = 21 * 60, enabled = false))
        assertTrue(v.guarded)
        assertTrue(v.allowed)
        assertEquals(PlanCloseGuard.State.ALLOWED, v.verdicts[old.id]?.state)
    }

    @Test
    fun `当天已执行过的计划改时间要过闸`() {
        val old = plan(weekdays = emptySet())
        val v = evaluate(old, old.copy(startMinute = 20 * 60, endMinute = 21 * 60), executedToday = true)
        assertTrue(v.guarded)
    }

    // ---------- 改完之后才落到今天：同样要预约 ----------

    @Test
    fun `改完之后今天才执行要预约（把计划挪到今天）`() {
        // 周三 9:00：只在周四执行的计划，改成周三执行 → 改完今天就会执行
        val old = plan(weekdays = setOf(4))
        val v = evaluate(old, old.copy(weekdays = setOf(3)))
        assertTrue(v.guarded)
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, v.verdicts[old.id]?.state)
        assertFalse(v.allowed)
    }

    @Test
    fun `把已过时的一次性计划改到稍后今天要预约`() {
        // 周三 9:00：不重复计划的 07:00 时段已经过去（今天不会再执行），
        // 改到 20:00 之后今天就会执行 → 同样要预约
        val old = plan(weekdays = emptySet(), startMinute = 7 * 60, endMinute = 8 * 60)
        val v = evaluate(old, old.copy(startMinute = 20 * 60, endMinute = 21 * 60))
        assertTrue(v.guarded)
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, v.verdicts[old.id]?.state)
        assertFalse(v.allowed)
    }

    @Test
    fun `停用的计划改到今天并启用要预约`() {
        val old = plan(weekdays = setOf(4), enabled = false)
        val v = evaluate(old, old.copy(weekdays = setOf(3), enabled = true))
        assertTrue(v.guarded)
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, v.verdicts[old.id]?.state)
    }

    @Test
    fun `改完之后仍在今天之外只需冷静期`() {
        val old = plan(weekdays = setOf(4, 5))
        val v = evaluate(old, old.copy(weekdays = setOf(4, 5, 6), appGroupIds = listOf(9L)))
        assertTrue(v.guarded)
        assertTrue(v.allowed)
        assertEquals(PlanCloseGuard.State.ALLOWED, v.verdicts[old.id]?.state)
    }

    @Test
    fun `改完之后今天执行时同样吃同一份预约`() {
        // 「关闭计划」预约满 1 小时后写入的时刻，编辑守卫读到同一个值 → 挪到今天也放行
        val old = plan(weekdays = setOf(4))
        val at = wednesday - 61 * 60_000L
        val v = evaluate(old, old.copy(weekdays = setOf(3)), appointmentAt = at)
        assertTrue(v.allowed)
        assertTrue(PlanCloseGuard.windowLeftMillis(at, wednesday) > 0)
    }

    // ---------- 与「关闭计划」共用同一份预约 ----------

    @Test
    fun `关闭用的预约也解锁修改（共用同一份预约）`() {
        // 周三 9:00：预约写在 10 分钟前 → 还在 1 小时等待期，关闭与修改都不放行
        val old = plan()
        val recent = wednesday - 10 * 60_000L
        val v = evaluate(old, old.copy(startMinute = 20 * 60, endMinute = 21 * 60), appointmentAt = recent)
        assertFalse(v.allowed)
        assertEquals(PlanCloseGuard.State.APPOINTMENT_WAITING, v.verdicts[old.id]?.state)

        // 同一份预约满 1 小时后：关闭与修改同一口径，都放行，且共享同一个窗口剩余时间
        val matured = wednesday - 61 * 60_000L
        val v2 = evaluate(old, old.copy(startMinute = 20 * 60, endMinute = 21 * 60), appointmentAt = matured)
        assertTrue(v2.allowed)
        assertTrue(PlanCloseGuard.windowLeftMillis(matured, wednesday) > 0)
        assertEquals(
            PlanCloseGuard.evaluate(old, wednesday, executedToday = false, appointmentAt = matured).state,
            v2.verdicts[old.id]?.state,
        )
    }

    // ---------- 预约状态 ----------

    @Test
    fun `预约未满一小时仍在等待`() {
        val old = plan()
        val v = evaluate(
            old, old.copy(startMinute = 20 * 60, endMinute = 21 * 60),
            appointmentAt = wednesday - 10 * 60_000L,
        )
        assertEquals(PlanCloseGuard.State.APPOINTMENT_WAITING, v.verdicts[old.id]?.state)
        assertFalse(v.allowed)
    }

    @Test
    fun `预约满一小时放行`() {
        val old = plan()
        val v = evaluate(
            old, old.copy(startMinute = 20 * 60, endMinute = 21 * 60),
            appointmentAt = wednesday - 61 * 60_000L,
        )
        assertTrue(v.allowed)
        assertEquals(PlanCloseGuard.State.ALLOWED, v.verdicts[old.id]?.state)
    }

    @Test
    fun `预约超窗需重新预约`() {
        val old = plan()
        val v = evaluate(
            old, old.copy(startMinute = 20 * 60, endMinute = 21 * 60),
            appointmentAt = wednesday - 2 * 60 * 60_000L,
        )
        assertEquals(PlanCloseGuard.State.APPOINTMENT_EXPIRED, v.verdicts[old.id]?.state)
        assertFalse(v.allowed)
    }

    @Test
    fun `当天执行时走预约闸而不是十五分钟闸`() {
        val thursday = millisOf(2026, Calendar.SEPTEMBER, 17, 9, 0)
        val old = plan(weekdays = setOf(4), startMinute = 9 * 60 + 5, endMinute = 10 * 60)
        val v = evaluate(old, old.copy(startMinute = 11 * 60, endMinute = 12 * 60), now = thursday)
        assertTrue(v.guarded)
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, v.verdicts[old.id]?.state)
    }

    @Test
    fun `十五分钟内开始且今天不执行时直接禁止`() {
        // 周三 23:55 看周四 00:05 的计划：今天不执行（周三不在 weekdays），但 10 分钟后开始
        val lateWednesday = millisOf(2026, Calendar.SEPTEMBER, 16, 23, 55)
        val old = plan(weekdays = setOf(4), startMinute = 5, endMinute = 6 * 60)
        val v = PlanEditGuard.evaluate(
            old, old.copy(startMinute = 7, endMinute = 6 * 60),
            now = lateWednesday, executedToday = false, appointmentAt = null,
        )
        assertTrue(v.guarded)
        assertEquals(PlanCloseGuard.State.BLOCKED_NEAR_START, v.verdicts[old.id]?.state)
        assertFalse(v.allowed)
    }

    @Test
    fun `同时改时间与暂停对象会列出两条原因`() {
        val old = plan()
        val v = evaluate(old, old.copy(startMinute = 20 * 60, endMinute = 21 * 60, appGroupIds = listOf(9L)))
        assertEquals(listOf(PlanEditGuard.Reason.TIME, PlanEditGuard.Reason.TARGETS), v.reasons)
    }

    private companion object {
        const val ROLE_FOCUS = com.frosthush.app.data.FocusStore.SEGMENT_FOCUS
        const val ROLE_REST = com.frosthush.app.data.FocusStore.SEGMENT_REST

        fun seg(type: Int, minutes: Int) = com.frosthush.app.data.FocusStore.Segment(type, minutes)
    }
}
