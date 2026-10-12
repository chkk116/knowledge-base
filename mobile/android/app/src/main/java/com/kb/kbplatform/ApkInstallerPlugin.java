/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内升级：下载 APK + 直接拉起系统安装器。
 *
 * 为什么自研：主流做法是「App 内下载 → 弹系统安装界面」，而不是跳浏览器。
 * 跳浏览器的问题：把用户扔出 App、下载完还要自己去文件管理里找，体验割裂。
 *
 * 流程：downloadAndInstall({url}) →
 *   ① 后台线程下载到 cacheDir/update/kb-update.apk，期间 notifyListeners("progress", {received,total,percent})
 *   ② 下载完检查「安装未知应用」权限：
 *        - 没授权 → 跳系统设置页 + notifyListeners("needInstallPermission")，用户授权后重试
 *        - 已授权 → FileProvider 生成 content:// 再 startActivity(ACTION_VIEW) 拉起安装器
 *
 * 权限：AndroidManifest 需声明 REQUEST_INSTALL_PACKAGES（Android 8.0+）。
 * FileProvider：复用工程里已注册的 `${applicationId}.fileprovider`；
 *   文件放 cacheDir（已被 res/xml/file_paths.xml 的 <cache-path path="." /> 覆盖）。
 *
 * ⚠️ 若将来要上架 Google Play，REQUEST_INSTALL_PACKAGES 属于敏感权限，需改用 Play 内更新。
 *    自建分发 / 国内商店不受此限。
 */
@CapacitorPlugin(name = "ApkInstaller")
public class ApkInstallerPlugin extends Plugin {

    private static final String TAG = "ApkInstaller";
    /** 下载进度通知：渠道 + 通知 id（固定 id → 后续更新覆盖同一条，不会刷屏） */
    private static final String CHANNEL_ID = "kb_app_update";
    private static final int NOTIF_ID = 9001;
    private static final int REQ_POST_NOTIF = 9101;
    /** 同一个包只允许一个下载任务在跑 */
    /** 下载任务互斥：AtomicBoolean 保证 check-then-act 原子性
     * （外部审查 2026-10-12：volatile 只保证可见性，并发调用会双下载写同一文件） */
    private final java.util.concurrent.atomic.AtomicBoolean downloading =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @PluginMethod
    public void downloadAndInstall(final PluginCall call) {
        final String url = call.getString("url");
        if (url == null || url.trim().isEmpty()) {
            call.reject("缺少下载地址");
            return;
        }
        if (!downloading.compareAndSet(false, true)) {
            call.reject("已有下载任务在进行中");
            return;
        }
        // 固定服务端文件名：不采信 JS 侧传入的 fileName（../ 或绝对路径会穿越出缓存目录）
        final String fileName = "kb-update.apk";
        // SHA-256 校验值（十六进制，来自后端 SysSetting）：缺值一律拒绝（fail-closed）。
        // 外部审查 2026-10-11：空值不再当作「免校验」，否则被注入的 JS 可传自己的
        // APK + 配套哈希绕过完整性校验。
        final String checksum = call.getString("checksum");
        if (checksum == null || checksum.trim().isEmpty()) {
            call.reject("更新包缺少 SHA-256 校验值，已拒绝安装");
            return;
        }
        // 通知栏进度：先建渠道（Android 8+），再按需申请通知权限（Android 13+）。
        // 被拒绝也不影响下载，只是通知栏不显示。
        ensureChannel();
        requestNotifPermissionIfNeeded();

        new Thread(() -> {
            try {
                File apk = download(url, fileName, checksum);
                JSObject res = new JSObject();
                res.put("filePath", apk.getAbsolutePath());
                res.put("size", apk.length());
                res.put("launched", true);
                launchInstall(apk);
                showDoneNotification();
                call.resolve(res);
            } catch (Exception e) {
                JSObject err = new JSObject();
                err.put("message", String.valueOf(e.getMessage()));
                notifyListeners("downloadError", err);
                showFailNotification(String.valueOf(e.getMessage()));
                call.reject("下载失败：" + e.getMessage());
            } finally {
                downloading.set(false);
            }
        }, "kb-apk-download").start();
    }

    /** 已下载好时，只拉起安装（例如用户去授权「安装未知应用」后回来重试） */
    @PluginMethod
    public void install(PluginCall call) {
        String path = call.getString("filePath");
        if (path == null || path.isEmpty()) {
            call.reject("缺少文件路径");
            return;
        }
        // 外部审查 2026-10-12：filePath 由 JS 传入，必须校验规范化后位于本插件自己的
        // 缓存目录内——否则被注入的 JS 可让本应用安装设备上任意 APK。
        File dir = new File(getContext().getCacheDir(), "update");
        File f = new File(path);
        try {
            String dirCanon = dir.getCanonicalPath() + File.separator;
            if (!f.getCanonicalPath().startsWith(dirCanon)) {
                call.reject("非法的安装包路径");
                return;
            }
        } catch (Exception e) {
            call.reject("路径校验失败");
            return;
        }
        if (!f.exists()) {
            call.reject("安装包不存在，请重新下载");
            return;
        }
        launchInstall(f);
        call.resolve();
    }

    // ---------------- 内部实现 ----------------

    /**
     * 打开连接并手动处理重定向（外部审查 2026-10-12）。
     * 原来一刀切 setInstanceFollowRedirects(false) 又无手动处理，CDN 类 301/302 地址
     * 直接报「HTTP 3xx」失败。这里手动跟随，但**每一跳都重新校验 https + 域名白名单**，
     * 防止 https→http 降级或跨域重定向绕过 URL 校验（简单放开自动跟随会作废该防线）。
     */
    private HttpURLConnection openValidated(String urlStr) throws Exception {
        String cur = urlStr;
        for (int hop = 0; hop < 4; hop++) {
            if (!cur.startsWith("https://")) throw new Exception("更新地址必须为 HTTPS");
            // 原生层域名白名单：host 必须在构建期注入的 kb_allowed_hosts 内。
            // 这是最后一道防线——WebView 内被注入的 JS 可以绕过 JS 侧校验直接调桥，
            // 只有原生自己持有的白名单才拦得住（外部审查 2026-10-11）。
            java.util.Set<String> allowed = allowedHosts();
            String host = new URL(cur).getHost();
            if (allowed.isEmpty()) throw new Exception("App 未配置允许的更新域名（KB_ALLOWED_HOSTS），已拒绝下载");
            if (!allowed.contains(host)) throw new Exception("更新地址域名不在白名单内");
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(60_000);
            // 禁止自动跟随重定向：由本方法按上面的口径逐跳校验
            c.setInstanceFollowRedirects(false);
            c.connect();
            int code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            if (code >= 300 && code < 400 && loc != null && !loc.isBlank()) {
                c.disconnect();
                cur = new URL(new URL(cur), loc).toString();   // 相对 Location 基于当前 URL 解析
                continue;
            }
            return c;
        }
        throw new Exception("重定向次数过多");
    }

    private File download(String url, String fileName, String expectedSha256) throws Exception {
        File dir = new File(getContext().getCacheDir(), "update");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建缓存目录");
        File out = new File(dir, fileName);
        if (out.exists() && !out.delete()) throw new Exception("无法覆盖旧安装包");

        // 手动跟随重定向（每跳验 https + 白名单），见 openValidated
        HttpURLConnection conn = openValidated(url);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new Exception("HTTP " + code);
        }
        int total = conn.getContentLength();

        try (InputStream in = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            long received = 0;
            long lastEmit = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                received += n;
                long now = System.currentTimeMillis();
                if (now - lastEmit > 200) {          // 节流，别把桥打爆
                    lastEmit = now;
                    JSObject p = new JSObject();
                    p.put("received", received);
                    p.put("total", total);
                    int pct = total > 0 ? (int) Math.round(received * 100.0 / total) : -1;
                    p.put("percent", pct);
                    notifyListeners("progress", p);
                    // 系统通知栏同步显示进度（大厂 App 的通用做法）
                    showProgressNotification(pct, received, total);
                }
            }
            fos.flush();
        } finally {
            conn.disconnect();
        }
        // SHA-256 完整性校验：checksum 由后端 SysSetting 随更新信息下发（构建脚本算出）
        if (expectedSha256 != null && !expectedSha256.isBlank()) {
            String actual = sha256Hex(out);
            if (!actual.equalsIgnoreCase(expectedSha256.trim())) {
                out.delete();
                throw new Exception("安装包校验失败（SHA-256 不匹配），已删除重试");
            }
        }
        return out;
    }

    // ---------------- 下载进度通知（通知栏） ----------------

    /** 通知渠道：Android 8.0+ 必须先建渠道，否则通知不显示。IMPORTANCE_LOW = 不响铃不震动 */
    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("应用内更新包的下载进度");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Exception ignore) { /* 建不了就退化为不显示通知 */ }
    }

    /** 是否允许发通知（Android 13+ 需运行时授权） */
    private boolean canNotify() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return ContextCompat.checkSelfPermission(getContext(), "android.permission.POST_NOTIFICATIONS")
                        == PackageManager.PERMISSION_GRANTED;
            }
            return NotificationManagerCompat.from(getContext()).areNotificationsEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    /** 开始下载时申请通知权限（用户拒绝也继续下载，只是没有通知栏进度） */
    private void requestNotifPermissionIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT < 33 || canNotify()) return;
            if (getActivity() == null) return;
            ActivityCompat.requestPermissions(getActivity(),
                    new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_POST_NOTIF);
        } catch (Exception ignore) { /* 申请失败不阻断下载 */ }
    }

    /** 点击通知回到 App */
    private PendingIntent contentIntent() {
        try {
            Intent open = new Intent(getContext(), getActivity().getClass());
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getActivity(getContext(), 0, open, flags);
        } catch (Exception e) {
            return null;
        }
    }

    /** 下载中：常驻进度条（固定 id → 覆盖更新同一条） */
    private void showProgressNotification(int percent, long received, long total) {
        if (!canNotify()) return;
        try {
            String size = total > 0
                    ? String.format(java.util.Locale.US, "%.1f/%.1f MB", received / 1048576.0, total / 1048576.0)
                    : "下载中";
            NotificationCompat.Builder b = new NotificationCompat.Builder(getContext(), CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("正在下载应用更新")
                    .setContentText(percent >= 0 ? (percent + "%  ·  " + size) : size)
                    .setOnlyAlertOnce(true)      // 进度刷新不重复响铃
                    .setOngoing(true)            // 下载中不可划掉
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setProgress(100, Math.max(0, percent), percent < 0);
            PendingIntent pi = contentIntent();
            if (pi != null) b.setContentIntent(pi);
            NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, b.build());
        } catch (Exception ignore) { /* 通知失败不影响下载 */ }
    }

    /** 下载完成：去掉进度条、可划掉 */
    private void showDoneNotification() {
        if (!canNotify()) return;
        try {
            NotificationCompat.Builder b = new NotificationCompat.Builder(getContext(), CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("更新包下载完成")
                    .setContentText("正在打开安装界面…")
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setProgress(0, 0, false);
            PendingIntent pi = contentIntent();
            if (pi != null) b.setContentIntent(pi);
            NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, b.build());
        } catch (Exception ignore) { /* ignore */ }
    }

    /** 下载失败 */
    private void showFailNotification(String msg) {
        if (!canNotify()) return;
        try {
            NotificationCompat.Builder b = new NotificationCompat.Builder(getContext(), CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("更新失败")
                    .setContentText(msg == null || msg.isEmpty() ? "请稍后重试" : msg)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            PendingIntent pi = contentIntent();
            if (pi != null) b.setContentIntent(pi);
            NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, b.build());
        } catch (Exception ignore) { /* ignore */ }
    }

    /** 构建期注入的更新域名白名单（strings.xml: kb_allowed_hosts，逗号分隔） */
    private java.util.Set<String> allowedHosts() {
        java.util.Set<String> set = new java.util.HashSet<>();
        try {
            int id = getContext().getResources().getIdentifier("kb_allowed_hosts", "string", getContext().getPackageName());
            if (id != 0) {
                for (String h : getContext().getString(id).split(",")) {
                    String t = h.trim().toLowerCase();
                    if (!t.isEmpty()) set.add(t);
                }
            }
        } catch (Exception ignore) { /* 缺资源 = 空 = fail-closed */ }
        return set;
    }

    private static String sha256Hex(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void launchInstall(final File apk) {
        try {
            // Android 8.0+ 必须允许「安装未知应用」，否则安装器会静默失败
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !getContext().getPackageManager().canRequestPackageInstalls()) {
                JSObject o = new JSObject();
                o.put("needPermission", true);
                notifyListeners("needInstallPermission", o);
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getContext().getPackageName()));
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(s);
                return;
            }
            Uri uri = FileProvider.getUriForFile(
                    getContext(),
                    getContext().getPackageName() + ".fileprovider",
                    apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception e) {
            JSObject err = new JSObject();
            err.put("message", "拉起安装失败：" + e.getMessage());
            notifyListeners("installError", err);
        }
    }
}
