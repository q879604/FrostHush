package com.frosthush.app.focus

/**
 * 设备管理员（增强保活）关闭守卫（纯逻辑，无 Android 依赖，可 JVM 单元测试）。
 *
 * 动机：设备管理员是「计划能准点触发」的最后一道保险 —— 激活后系统不允许对本应用
 * 强行停止。随手把它关掉，等于悄悄拆掉所有计划的保活，比关掉单个计划更隐蔽。
 * 所以：**关闭设备管理员必须先预约**；打开不需要预约（[evaluate] 只在已激活时设闸）。
 *
 * 规则：
 * - 预约满 [APPOINTMENT_WAIT_MS]（1 小时）后，进入 [APPOINTMENT_WINDOW_MS]（20 分钟）
 *   可操作窗口；窗口内可关闭；超出窗口需重新预约；
 * - 预约时刻落盘（不是内存），1 小时等待期内进程被回收也不会丢；
 * - **单独预约**：这条预约只解锁「关闭设备管理员」这一项，与计划预约
 *   （planAppointmentsFile）、分类预约（groupAppointmentsFile）完全独立、互不相通 ——
 *   任一方的预约生效都不会顺带解锁另一方。
 */
object DeviceAdminGuard {
    /** 预约后需要等待的时长（1 小时） */
    const val APPOINTMENT_WAIT_MS = 60 * 60 * 1000L

    /** 预约生效后的可操作窗口（20 分钟） */
    const val APPOINTMENT_WINDOW_MS = 20 * 60 * 1000L

    /**
     * 判定一次「关闭设备管理员」是否放行。
     *
     * @param active 当前是否已激活为设备管理员；未激活时无需守卫（也就没有可关闭的东西）
     * @param now 当前时刻
     * @param appointmentAt 已存的预约时刻（专用记录）；未预约传 null
     */
    fun evaluate(active: Boolean, now: Long, appointmentAt: Long?): PlanCloseGuard.Verdict {
        // 本来就没激活 → 没有可关闭的东西，放行
        if (!active) return PlanCloseGuard.Verdict(PlanCloseGuard.State.ALLOWED)
        if (appointmentAt == null || appointmentAt > now) {
            return PlanCloseGuard.Verdict(PlanCloseGuard.State.NEED_APPOINTMENT)
        }
        val elapsed = now - appointmentAt
        if (elapsed < APPOINTMENT_WAIT_MS) {
            return PlanCloseGuard.Verdict(
                PlanCloseGuard.State.APPOINTMENT_WAITING,
                APPOINTMENT_WAIT_MS - elapsed,
            )
        }
        if (elapsed > APPOINTMENT_WAIT_MS + APPOINTMENT_WINDOW_MS) {
            return PlanCloseGuard.Verdict(PlanCloseGuard.State.APPOINTMENT_EXPIRED)
        }
        return PlanCloseGuard.Verdict(PlanCloseGuard.State.ALLOWED)
    }

    /** 预约「可操作窗口」的剩余毫秒（已过期返回 0）；未预约返回 0 */
    fun windowLeftMillis(appointmentAt: Long?, now: Long): Long =
        if (appointmentAt == null) 0L
        else (appointmentAt + APPOINTMENT_WAIT_MS + APPOINTMENT_WINDOW_MS - now).coerceAtLeast(0L)
}
