/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kb.kbplatform.shell.MainActivity

/** 应用是否在前台：MainActivity 的 onStart/onStop 维护，用于决定要不要弹系统通知 */
object AppForeground {
    @Volatile
    var active: Boolean = false
}

/**
 * 站内消息的系统通知（Android 13+ 需 POST_NOTIFICATIONS 运行时授权）。
 *
 * 只在 App 不在前台时弹：用户正在看消息页还弹通知只会烦人。
 * 点击通知回到 App 首页（深链到具体消息需要 intent extra，后续再加）。
 */
object MsgNotify {
    private const val CHANNEL = "kb_messages"
    private const val REQ_CODE = 7302

    fun granted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    /** 首次进前台时申请一次；已授权/已拒绝都不再打扰（拒绝后可在系统设置里开） */
    fun ensurePermission(ctx: Context) {
        NotifyPerm.request(ctx)
    }

    fun show(ctx: Context, title: String, body: String) {
        if (!granted(ctx)) return
        ensureChannel(ctx)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title.ifBlank { "知识库" })
            .setContentText(body.ifBlank { "你有一条新消息" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .build()
        runCatching {
            NotificationManagerCompat.from(ctx)
                .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
        }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "站内消息", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "评论、@我、审核结果等站内通知"
            }
        )
    }
}
