package com.frosthush.app.focus

import com.frosthush.app.data.FocusStore.FocusPlan

/**
 * 应用集（分类）修改守卫（纯逻辑，无 Android 依赖，可 JVM 单元测试）。
 *
 * 动机：计划会用到应用集，但应用集本身是可以随手改的——把微信从分类里挪走、
 * 或者干脆删掉整个分类，就能让「今天要执行的计划」形同虚设，比关掉计划更隐蔽。
 * 所以：**只要今天会执行的启用计划引用了某个应用集**，对该应用集做「减法」就要先预约。
 *
 * 规则：
 * - 需要预约的动作：从该应用集**移除**任意应用、**删除**整个应用集；
 * - 不需要预约的动作：**只添加**应用（加应用不会削弱计划效果，加多少都放行）；
 * - 预约满 [APPOINTMENT_WAIT_MS]（45 分钟）后，进入 [APPOINTMENT_WINDOW_MS]（25 分钟）
 *   可操作窗口；窗口内可自由增删应用、删除分类；超出窗口需重新预约；
 * - 预约时刻落盘（不是内存），45 分钟等待期内进程被回收也不会丢。
 *
 * 判定复用了 [PlanCloseGuard.occursToday]，即「今天会不会执行」的口径与计划守卫完全一致
 * （含正在执行中 / 跨天延续 / 当天已执行过）。
 */
object AppGroupGuard {
    /** 预约后需要等待的时长（45 分钟） */
    const val APPOINTMENT_WAIT_MS = 45 * 60 * 1000L

    /** 预约生效后的可操作窗口（25 分钟） */
    const val APPOINTMENT_WINDOW_MS = 25 * 60 * 1000L

    /** 计划是否引用了该应用集（多集优先，兼容旧的单集字段） */
    fun references(plan: FocusPlan, groupId: Long): Boolean =
        plan.appGroupIds?.contains(groupId) == true || plan.appGroupId == groupId

    /**
     * 保护该应用集的计划：**启用中** 且 **今天会执行** 且引用了它。
     * 计划关闭（enabled=false）后不再保护对应的分类。
     */
    fun guardingPlans(
        plans: List<FocusPlan>,
        groupId: Long,
        now: Long,
        executedToday: (FocusPlan) -> Boolean,
    ): List<FocusPlan> = plans.filter {
        it.enabled && references(it, groupId) && PlanCloseGuard.occursToday(it, now, executedToday(it))
    }

    /**
     * 判定一次「减法」修改（移除应用 / 删除分类）是否放行。
     *
     * @param plans 全部计划
     * @param groupId 被修改的应用集 id
     * @param now 当前时刻
     * @param executedToday 该计划今天是否已经执行过（FocusStore.planExecutedDay == todayCode）
     * @param appointmentAt 该应用集已存的预约时刻；未预约传 null
     */
    fun evaluate(
        plans: List<FocusPlan>,
        groupId: Long,
        now: Long,
        executedToday: (FocusPlan) -> Boolean,
        appointmentAt: Long?,
    ): PlanCloseGuard.Verdict {
        // 没有「今天会执行的启用计划」引用它 → 不设闸，随时可改
        if (guardingPlans(plans, groupId, now, executedToday).isEmpty()) {
            return PlanCloseGuard.Verdict(PlanCloseGuard.State.ALLOWED)
        }
        if (appointmentAt == null || appointmentAt > now) {
            return PlanCloseGuard.Verdict(PlanCloseGuard.State.NEED_APPOINTMENT)
        }
        val elapsed = now - appointmentAt
        if (elapsed < APPOINTMENT_WAIT_MS) {
            return PlanCloseGuard.Verdict(PlanCloseGuard.State.APPOINTMENT_WAITING, APPOINTMENT_WAIT_MS - elapsed)
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
