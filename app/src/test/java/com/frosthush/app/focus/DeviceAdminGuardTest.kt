package com.frosthush.app.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeviceAdminGuard 回归测试（关闭设备管理员要先预约 1 小时 → 20 分钟窗口）：
 * - 未激活时没有可关闭的东西 → 直接放行，不设闸；
 * - 已激活 → 必须先预约；预约满 1 小时才有 20 分钟可操作窗口；
 * - 超窗需重新预约；
 * - **单独预约**：时间口径与计划预约（1 小时 + 30 分钟）、分类预约（45 分钟 + 25 分钟）
 *   都不同，本测试把 20 分钟窗口边界钉死，防止以后被误改成共用常量。
 */
class DeviceAdminGuardTest {

    private fun evaluate(
        active: Boolean = true,
        appointmentAt: Long? = null,
        at: Long = NOW,
    ) = DeviceAdminGuard.evaluate(active, at, appointmentAt)

    @Test
    fun `未激活时不设闸`() {
        assertTrue(evaluate(active = false).allowed)
        assertEquals(PlanCloseGuard.State.ALLOWED, evaluate(active = false).state)
    }

    @Test
    fun `已激活且未预约要预约`() {
        val v = evaluate()
        assertFalse(v.allowed)
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, v.state)
    }

    @Test
    fun `预约时刻在未来视为未预约`() {
        val v = evaluate(appointmentAt = NOW + 60_000L)
        assertEquals(PlanCloseGuard.State.NEED_APPOINTMENT, v.state)
    }

    @Test
    fun `刚预约完仍在等待`() {
        val v = evaluate(appointmentAt = NOW)
        assertEquals(PlanCloseGuard.State.APPOINTMENT_WAITING, v.state)
        assertEquals(DeviceAdminGuard.APPOINTMENT_WAIT_MS, v.remainingMs)
    }

    @Test
    fun `等待期剩余时间随时间递减`() {
        val v = evaluate(appointmentAt = NOW - 10 * 60_000L)
        assertEquals(PlanCloseGuard.State.APPOINTMENT_WAITING, v.state)
        assertEquals(50 * 60_000L, v.remainingMs)
    }

    @Test
    fun `差一秒还不放行`() {
        val v = evaluate(appointmentAt = NOW - DeviceAdminGuard.APPOINTMENT_WAIT_MS + 1_000L)
        assertEquals(PlanCloseGuard.State.APPOINTMENT_WAITING, v.state)
        assertFalse(v.allowed)
    }

    @Test
    fun `预约满一小时放行`() {
        val v = evaluate(appointmentAt = NOW - DeviceAdminGuard.APPOINTMENT_WAIT_MS)
        assertTrue(v.allowed)
        assertEquals(PlanCloseGuard.State.ALLOWED, v.state)
    }

    @Test
    fun `窗口最后一刻仍放行`() {
        val at = NOW - DeviceAdminGuard.APPOINTMENT_WAIT_MS - DeviceAdminGuard.APPOINTMENT_WINDOW_MS
        assertTrue(evaluate(appointmentAt = at).allowed)
    }

    @Test
    fun `超出二十分钟窗口需重新预约`() {
        val at = NOW - DeviceAdminGuard.APPOINTMENT_WAIT_MS - DeviceAdminGuard.APPOINTMENT_WINDOW_MS - 1_000L
        val v = evaluate(appointmentAt = at)
        assertEquals(PlanCloseGuard.State.APPOINTMENT_EXPIRED, v.state)
        assertFalse(v.allowed)
    }

    @Test
    fun `等待时长是一小时`() {
        assertEquals(60 * 60 * 1000L, DeviceAdminGuard.APPOINTMENT_WAIT_MS)
    }

    @Test
    fun `操作窗口是二十分钟`() {
        assertEquals(20 * 60 * 1000L, DeviceAdminGuard.APPOINTMENT_WINDOW_MS)
    }

    @Test
    fun `单独预约：窗口口径与计划预约和分类预约都不同`() {
        // 「单独预约」不只是存储分开，时长也各自独立 —— 防止以后被改成共用常量
        assertTrue(DeviceAdminGuard.APPOINTMENT_WAIT_MS != AppGroupGuard.APPOINTMENT_WAIT_MS)
        assertTrue(DeviceAdminGuard.APPOINTMENT_WINDOW_MS != AppGroupGuard.APPOINTMENT_WINDOW_MS)
        assertTrue(DeviceAdminGuard.APPOINTMENT_WINDOW_MS != PlanCloseGuard.APPOINTMENT_WINDOW_MS)
    }

    @Test
    fun `窗口剩余时间`() {
        val at = NOW - DeviceAdminGuard.APPOINTMENT_WAIT_MS
        assertEquals(DeviceAdminGuard.APPOINTMENT_WINDOW_MS, DeviceAdminGuard.windowLeftMillis(at, NOW))
    }

    @Test
    fun `窗口剩余时间过期归零`() {
        val at = NOW - DeviceAdminGuard.APPOINTMENT_WAIT_MS - DeviceAdminGuard.APPOINTMENT_WINDOW_MS - 5_000L
        assertEquals(0L, DeviceAdminGuard.windowLeftMillis(at, NOW))
    }

    @Test
    fun `未预约时窗口剩余为零`() {
        assertEquals(0L, DeviceAdminGuard.windowLeftMillis(null, NOW))
    }

    private companion object {
        /** 固定基准时刻，避免测试依赖真实时钟 */
        const val NOW = 1_800_000_000_000L
    }
}
