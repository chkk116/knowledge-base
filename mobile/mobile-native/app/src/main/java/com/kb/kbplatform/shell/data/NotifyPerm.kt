package com.kb.kbplatform.shell.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 通知权限的统一入口（Android 13+ 的 POST_NOTIFICATIONS）。
 *
 * 2026-10-28 加：之前只有「弹一次系统授权框」，用户点了「不允许」之后
 * 就再也没有任何入口 —— 更新下载没进度通知、站内消息也不提醒，用户完全不知道为什么。
 * 这里补上「跳系统设置」的通道，并在设置页给一行可点的状态。
 */
object NotifyPerm {

    fun granted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 首次/未决时弹系统授权框；已永久拒绝时系统不会再弹（此时应引导去设置） */
    fun request(ctx: Context) {
        if (Build.VERSION.SDK_INT < 33 || granted(ctx)) return
        val act = ctx as? Activity ?: return
        runCatching {
            ActivityCompat.requestPermissions(
                act, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7303
            )
        }
    }

    /** 打开本应用的通知设置页（用户在这里能手动开启/关闭） */
    fun openSettings(ctx: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${ctx.packageName}")
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }
}
