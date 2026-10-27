package com.kb.kbplatform.shell.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * 更新下载的系统通知（对齐套壳版：下载进度在通知栏，下完点通知直接装）。
 *
 * 2026-10-27 加：原生壳之前只有「我的 → 检查更新」里一行小字显示进度，
 * 用户切走后完全看不到下载是否在进行；套壳版一直是通知栏进度 + 完成可点安装。
 *
 * 通知渠道用 IMPORTANCE_LOW + setSilent，下载过程不响铃不震动，只占一行。
 */
object UpdateNotify {
    private const val CHANNEL = "kb_update"
    private const val NOTIFY_ID = 7301
    private const val APK_MIME = "application/vnd.android.package-archive"

    private fun nm(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_LOW).apply {
            description = "下载更新包时的进度与完成提示"
            setShowBadge(false)
        }
        nm(ctx).createNotificationChannel(ch)
    }

    /** 下载中：常驻进度通知 */
    fun progress(ctx: Context, percent: Int) {
        runCatching {
            ensureChannel(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下载更新")
                .setContentText("$percent%")
                .setProgress(100, percent.coerceIn(0, 100), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm(ctx).notify(NOTIFY_ID, n)
        }
    }

    /** 下载完成：点通知直接拉起安装器（不需要先进 App） */
    fun done(ctx: Context, file: File) {
        runCatching {
            ensureChannel(ctx)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pi = PendingIntent.getActivity(
                ctx, 1, install,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("更新包已下载")
                .setContentText("点击安装")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
            nm(ctx).notify(NOTIFY_ID, n)
        }
    }

    fun fail(ctx: Context, msg: String) {
        runCatching {
            ensureChannel(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("更新下载失败")
                .setContentText(msg)
                .setAutoCancel(true)
                .build()
            nm(ctx).notify(NOTIFY_ID, n)
        }
    }

    fun cancel(ctx: Context) {
        runCatching { nm(ctx).cancel(NOTIFY_ID) }
    }

    /**
     * Android 13+ 通知要运行时授权。**不阻塞下载**：不授权只是看不到进度通知，
     * 下载和安装照常（进度还能在「我的 → 检查更新」那行看到）。
     */
    fun ensurePermission(ctx: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val ok = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (ok) return
        val act = ctx as? android.app.Activity ?: return
        runCatching {
            androidx.core.app.ActivityCompat.requestPermissions(
                act, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7301
            )
        }
    }
}
