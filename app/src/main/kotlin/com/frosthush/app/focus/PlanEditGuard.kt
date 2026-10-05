package com.frosthush.app.focus

import com.frosthush.app.data.FocusStore.FocusPlan

/**
 * 计划编辑守卫的纯逻辑（无 Android 依赖，可 JVM 单元测试）。
 *
 * 修改「当天会执行的计划」的**生效时间 / 生效日期 / 减少暂停对象**（应用集或直选应用），
 * 以及从编辑页把计划**改成效停用**（等价于列表里的关闭计划），
 * 效果等同于把它今天的实际专注对象和时间挪走 —— 和关闭、删除一样是绕过口子，
 * 因此共用 [PlanCloseGuard] 那套闸门（**同一份预约记录**）：预约满 1 小时 → 30 分钟可操作窗口 →
 * 超窗重新预约；非当天但 15 分钟内就要开始 → 直接禁止。
 *
 * 只有「真正动到这些字段」才设闸：只改名字不受影响。
 * 非当天的计划改这些字段同样要过闸（走 90 秒冷静期），与列表里关闭计划的行为一致。
 *
 * **增加一律不受限制**：暂停对象只有「减少 / 换掉」才设闸；追加应用集、多选应用集、
 * 追加直选应用都属于「增加」，默认允许、无需预约 —— 纯添加只会扩大当天要暂停的范围，
 * 不会削弱已经在执行的专注效果（与「给分类添加应用、新建分类不受限制」是同一口径）。
 *
 * 「改完之后今天会执行」也算：把计划挪到今天、或把已停用的计划改到今天并启用，
 * 改完就今天执行，同样是在动今天的专注安排，必须过同一道闸
 * （否则「先改到明天、再改回今天」就是一条绕过口子）。
 */
object PlanEditGuard {

    /** 本次保存动到了哪一类受保护字段（用于对话框里列清楚原因） */
    enum class Reason {
        /** 生效时间（开始/结束时刻）变了 */
        TIME,

        /** 生效日期（重复的星期）变了 */
        WEEKDAYS,

        /**
         * 暂停对象（绑定的应用集 / 直选应用）**被减少或换掉**：改完之后有应用不再被暂停。
         * 纯「增加」（真正会暂停的应用一个都没少，只是多了几个）不算改动，
         * 不设闸、也不会出现在这个列表里。
         */
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
     *
     * @param fallbackGroupId 计划没绑定任何对象时兜底生效的应用集 id（运行时用的是默认集，
     *   见 `FocusStore.planEntries`）。传进来才能把「本来就在用默认集的老计划」看成没改动，
     *   而不是「凭空多绑定了一个应用集」，也不会把「从默认集换成别的集」误判成纯添加。
     *   纯逻辑测试可以不传。
     * @param groupEntries 按应用集 id 取条目（`FocusStore.appGroups()`）；用于比较「真正暂停了哪些应用」。
     *   不传时退回按集 id 比较，行为不变。
     */
    fun reasonsFor(
        old: FocusPlan,
        new: FocusPlan,
        fallbackGroupId: Long? = null,
        groupEntries: (Long) -> List<String> = { emptyList<String>() },
    ): List<Reason> = buildList {
        // 分段结构也算「生效时间」：分段顺序/时长直接决定当天何时暂停哪些应用
        // 注意用 orEmpty() 而不是 `?: emptyList()`：布尔表达式里没有期望类型，
        // emptyList() 的泛型 T 推断不出来（CI 编译器直接报 Cannot infer type for T）
        val oldSegments = old.segments.orEmpty()
        val newSegments = new.segments.orEmpty()
        if (old.startMinute != new.startMinute || old.endMinute != new.endMinute || oldSegments != newSegments) {
            add(Reason.TIME)
        }
        if (old.weekdays != new.weekdays) add(Reason.WEEKDAYS)
        // 只有「减少 / 换掉」暂停对象才算动到受保护字段：
        // 多选应用集、追加直选应用等纯添加一律直接放行（增加不需要预约）
        if (!targetsOnlyAdded(old, new, fallbackGroupId, groupEntries)) add(Reason.TARGETS)
        // 编辑页里的启用开关就是「关闭计划」的另一个入口，不能成为绕过口子
        if (old.enabled && !new.enabled) add(Reason.CLOSE)
    }

    /**
     * 判定这次编辑要过哪道闸。
     *
     * 判定委托 [PlanCloseGuard.evaluate]，与关闭 / 删除计划是同一把尺子、同一份预约记录：
     * - 已停用、且改完仍不在今天执行 → 不执行，不设预约闸（只需冷静期）；
     * - **改之前**「今天会执行」→ 必须先预约（1 小时等待 + 30 分钟窗口）；
     * - 改之前不执行、**改完之后今天会执行**（挪到今天 / 停用改到今天并启用）→ 同样必须先预约：
     *   改完就今天执行，一样是在动今天的专注安排，不能成为绕过口子；
     * - 两者都不在当天、但 15 分钟内就要开始 → 直接禁止（[PlanCloseGuard.State.BLOCKED_NEAR_START]）；
     * - 其余 → [PlanCloseGuard.State.ALLOWED]，由 UI 接着走 90 秒冷静期。
     *
     * 只要动到受保护字段就一定 [guarded]（非当天的改动同样要冷静期，和列表里关计划一样）；
     * 纯「增加暂停对象」（多选应用集、追加直选应用）不算动到受保护字段 → 直接放行、不设闸。
     *
     * @param old 已保存的计划；新建计划传 null（新建不是「改今天的计划」，不设闸）
     * @param now 当前时刻
     * @param executedToday 该计划今天是否已经执行过
     * @param appointmentAt 该计划已存的预约时刻；未预约传 null。与「关闭计划」是同一份记录
     *   （`FocusStore.planAppointment` / `setPlanAppointment`），预约一次既能关闭、也能改这几项
     * @param fallbackGroupId 计划没绑定任何对象时兜底生效的应用集 id；语义见 [reasonsFor]
     * @param groupEntries 按应用集 id 取条目；语义见 [reasonsFor]
     */
    fun evaluate(
        old: FocusPlan?,
        new: FocusPlan,
        now: Long,
        executedToday: Boolean,
        appointmentAt: Long?,
        fallbackGroupId: Long? = null,
        groupEntries: (Long) -> List<String> = { emptyList<String>() },
    ): Verdict {
        if (old == null) return Verdict(emptyList(), emptyList(), emptyMap())
        val reasons = reasonsFor(old, new, fallbackGroupId, groupEntries)
        if (reasons.isEmpty()) return Verdict(emptyList(), emptyList(), emptyMap())
        // 判定基准：优先「改之前今天会执行」的那份计划；否则看「改完之后今天会执行」的那份。
        // 后者覆盖「把计划挪到今天」「把停用的计划改到今天并启用」——改完就今天执行，
        // 同样要过预约闸，否则「先改到明天、再改回今天」就是一条绕过口子。
        val oldRunsToday = old.enabled && PlanCloseGuard.occursToday(old, now, executedToday)
        val newRunsToday = !oldRunsToday && new.enabled && PlanCloseGuard.occursToday(new, now, false)
        val verdict = if (newRunsToday) {
            // 改完才落到今天：按「改完之后」的计划过闸（今天显然还没执行过）
            PlanCloseGuard.evaluate(new, now, executedToday = false, appointmentAt = appointmentAt)
        } else {
            // 与列表里关闭计划同一个函数、同一套语义：已停用的计划本来就不会执行，
            // 改它的时间不产生任何当天影响 → 只需冷静期，不用预约
            PlanCloseGuard.evaluate(old, now, executedToday, appointmentAt)
        }
        return Verdict(reasons, listOf(old), mapOf(old.id to verdict))
    }

    /**
     * 这次改动是不是「只增加暂停对象」：**改之前真正会暂停的应用**全都还在新的集合里。
     *
     * 比较的是应用条目而不是绑定写法，所以「从直选应用换成包含这些应用的应用集」
     * 也算纯增加 —— 真正被暂停的应用一个都没少。
     * 用集合（不是列表）比较：顺序、重复项都不算改动。
     * 旧集合为空时视为纯增加 —— 之前没有任何应用会因此被取消暂停。
     */
    private fun targetsOnlyAdded(
        old: FocusPlan,
        new: FocusPlan,
        fallbackGroupId: Long?,
        groupEntries: (Long) -> List<String>,
    ): Boolean {
        val before: Set<String> = targetsOf(old, fallbackGroupId, groupEntries)
        val after: Set<String> = targetsOf(new, fallbackGroupId, groupEntries)
        return before.all { it in after }
    }

    /**
     * 计划真正生效的暂停对象：绑定的应用集条目（新的多集优先，回落旧的单集字段）→ 直选应用 → 默认集。
     *
     * 回落顺序与 `FocusStore.planEntries` 完全一致，前三级都为空时才算「用默认集」，
     * 这样才不会把「从默认集换成别的集」当成纯增加。
     * 应用集取不到条目（已删除 / 空集）时退回 `g<id>` 指纹，避免它在比较里静默消失。
     *
     * 显式写出局部变量类型：`?: emptyList()` 没有期望类型时泛型 T 推断不出来会编译失败。
     */
    private fun targetsOf(
        plan: FocusPlan,
        fallbackGroupId: Long?,
        groupEntries: (Long) -> List<String>,
    ): Set<String> {
        val groupIds: List<Long> = plan.appGroupIds?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(plan.appGroupId)
        if (groupIds.isNotEmpty()) return groupIds.flatMap { entriesOf(it, groupEntries) }.toSet()
        val direct: List<String> = plan.directEntries.orEmpty()
        if (direct.isNotEmpty()) return direct.toSet()
        return listOfNotNull(fallbackGroupId).flatMap { entriesOf(it, groupEntries) }.toSet()
    }

    /** 应用集的条目；取不到（集不存在或为空）时退回 `g<id>` 指纹，保证它在比较里不会凭空消失 */
    private fun entriesOf(groupId: Long, groupEntries: (Long) -> List<String>): List<String> {
        val entries: List<String> = groupEntries(groupId)
        return entries.ifEmpty { listOf("g$groupId") }
    }
}
