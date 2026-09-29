package com.frosthush.app.focus

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.frosthush.app.R
import com.frosthush.app.util.DebugLog

/**
 * 设备管理员接收器：只用于「增强保活」，不含任何敏感策略（见 res/xml/device_admin.xml）。
 *
 * 激活后系统不再允许对本应用「强行停止」，卸载前必须先在系统设置里取消激活，
 * 专注计划的闹钟因此更不容易被系统清理掉。是否激活完全由用户在设置页决定。
 */
class FrostHushDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        DebugLog.d(TAG, "设备管理员已激活")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        DebugLog.d(TAG, "设备管理员已取消激活")
    }

    /** 用户点「取消激活」时系统展示的提醒文案 */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.device_admin_disable_warning)

    private companion object {
        const val TAG = "DeviceAdmin"
    }
}
