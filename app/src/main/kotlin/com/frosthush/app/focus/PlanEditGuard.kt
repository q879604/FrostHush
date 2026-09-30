package com.frosthush.app.focus

import com.frosthush.app.data.FocusStore.FocusPlan

/**
 * 计划编辑守卫的纯逻辑（无 Android 依赖，可 JVM 单元测试）。
 *
 * 修改「当天会执行的计划」的**生效时间 / 生效日期 / 暂停对象**（应用集或直选应用），
 * 以及从编辑页把计划**改成效停用**（等价于列表里的关闭计划），
 * 效果等同于把它今天的实际专注对象和时间挪走 —— 和关闭、删除一样是绕过口子，
 * 因此共用 [PlanCloseGuard] 那套闸门：预约满 1 小时 → 30 分钟可操作窗口 → 超窗重新预约；
 * 非当天但 15 分钟内就要开始 → 直接禁止。
 *
 * 只有「真正动到这些字段」才设闸：只改名字不受影响。
 * 非当天的计划改这些字段同样要过闸（走 90 秒冷静期），与列表里关闭计划的行为一致。
 */
object PlanEditGuard {

    /** 本次保存动到了哪一类受保护字段（用于对话框里列清楚原因） */
    enum class Reason {
        /** 生效时间（开始/结束时刻）变了 */
        TIME,

        /** 生效日期（重复的星期）变了 */
        WEEKDAYS,

        /** 暂停对象（绑定的应用集 / 直选应用）变了 */
        TARGETS,

        /** 计划从启用被改成停用（等价于列表里的「关闭计划」） */
        CLOSE,
    }

    /** 判定结果：需要过闸时 [reasons] 非空，[guarders] 是需要过闸的那些计划 */
    data class Verdict(
        val reasons: List<Reason>,
        val guarders: List<FocusPlan>,
        val verdicts: Map<Long, PlanCloseGuard.Verdict>,
    ) {
        val guarded: Boolean get() = reasons.isNotEmpty() && guarders.isNotEmpty()

        /** 全部被守卫的计划都已放行（预约窗口内） */
        val allowed: Boolean get() = !guarded || guarders.all { verdicts[it.id]?.allowed == true }

        /**
         * 「15 分钟内就要开始」的剩余毫秒（该状态下直接拒绝，连预约都不给）；没有则 null。
         * UI 用它弹 Toast 提示还剩几分钟，与列表里关闭计划的行为一致。
         */
        val blockedRemainingMs: Long?
            get() = verdicts.values.firstOrNull { it.state == PlanCloseGuard.State.BLOCKED_NEAR_START }?.remainingMs
    }

    /**
     * 本次保存的「形式」改动：只描述是否动到受保护字段，不涉及是否过闸。
     */
    fun reasonsFor(old: FocusPlan, new: FocusPlan): List<Reason> = buildList {
        // 分段结构也算「生效时间」：分段顺序/时长直接决定当天何时暂停哪些应用
        // 注意用 orEmpty() 而不是 `?: emptyList()`：布尔表达式里没有期望类型，
        // emptyList() 的泛型 T 推断不出来（CI 编译器直接报 Cannot infer type for T）
        val oldSegments = old.segments.orEmpty()
        val newSegments = new.segments.orEmpty()
        if (old.startMinute != new.startMinute || old.endMinute != new.endMinute || oldSegments != newSegments) {
            add(Reason.TIME)
        }
        if (old.weekdays != new.weekdays) add(Reason.WEEKDAYS)
        if (targetsOf(old) != targetsOf(new)) add(Reason.TARGETS)
        // 编辑页里的启用开关就是「关闭计划」的另一个入口，不能成为绕过口子
        if (old.enabled && !new.enabled) add(Reason.CLOSE)
    }

    /**
     * 判定这次编辑要过哪道闸。判定对象是**编辑前**的计划（现在会执行的那一个）。
     *
     * 判定直接委托 [PlanCloseGuard.evaluate]，与关闭 / 删除计划是同一把尺子：
     * - 计划已停用 → 不执行，不设闸（只需冷静期）；
     * - 改之前「今天会执行」→ 必须先预约（1 小时等待 + 30 分钟窗口）；
     * - 非当天但 15 分钟内就要开始 → 直接禁止（[PlanCloseGuard.State.BLOCKED_NEAR_START]）；
     * - 其余 → [PlanCloseGuard.State.ALLOWED]，由 UI 接着走 90 秒冷静期。
     *
     * 只要动到受保护字段就一定 [guarded]（非当天的改动同样要冷静期，和列表里关计划一样）。
     *
     * @param old 已保存的计划；新建计划传 null（新建不是「改今天的计划」，不设闸）
     * @param now 当前时刻
     * @param executedToday 该计划今天是否已经执行过
     * @param appointmentAt 该计划已存的预约时刻；未预约传 null
     */
    fun evaluate(
        old: FocusPlan?,
        new: FocusPlan,
        now: Long,
        executedToday: Boolean,
        appointmentAt: Long?,
    ): Verdict {
        if (old == null) return Verdict(emptyList(), emptyList(), emptyMap())
        val reasons = reasonsFor(old, new)
        if (reasons.isEmpty()) return Verdict(emptyList(), emptyList(), emptyMap())
        // 与列表里关闭计划同一个函数、同一套语义：已停用的计划本来就不会执行，
        // 改它的时间不产生任何当天影响 → 只需冷静期，不用预约
        val verdict = PlanCloseGuard.evaluate(old, now, executedToday, appointmentAt)
        return Verdict(reasons, listOf(old), mapOf(old.id to verdict))
    }

    /**
     * 暂停对象指纹：绑定的应用集 id（新的多集优先，回落旧的单集字段）+ 直选应用。
     *
     * 显式写出局部变量类型：`?: emptyList()` 没有期望类型时泛型 T 推断不出来会编译失败。
     */
    private fun targetsOf(plan: FocusPlan): List<String> {
        val groups: List<String> = plan.appGroupIds?.map { "g$it" }
            ?: plan.appGroupId?.let { listOf("g$it") }
            ?: emptyList()
        val direct: List<String> = plan.directEntries?.sorted() ?: emptyList()
        return groups + direct
    }
}
