package com.frosthush.app.focus

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.frosthush.app.R

/**
 * 设备管理员（DeviceAdmin）辅助：仅服务于「增强保活」。
 *
 * 激活后系统不允许对本应用「强行停止」，卸载前必须先取消激活，
 * 计划闹钟因此更不容易被系统清理。所有策略清单为空（res/xml/device_admin.xml），
 * 不申请锁屏 / 擦除数据 / 改密码等任何敏感能力。
 */
object DeviceAdmin {

    private fun component(context: Context): ComponentName =
        ComponentName(context.applicationContext, FrostHushDeviceAdminReceiver::class.java)

    private fun dpm(context: Context): DevicePolicyManager? =
        runCatching { context.getSystemService(DevicePolicyManager::class.java) }.getOrNull()

    /** 当前是否已激活为设备管理员（系统是唯一事实来源，无需本地开关） */
    fun isActive(context: Context): Boolean =
        runCatching { dpm(context)?.isAdminActive(component(context)) == true }.getOrDefault(false)

    /** 拉起系统「激活设备管理员」确认页 */
    fun activationIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.device_admin_explanation),
            )
        }

    /** 取消激活（系统会再弹一次系统级确认对话框） */
    fun deactivate(context: Context) {
        runCatching { dpm(context)?.removeActiveAdmin(component(context)) }
    }
}
