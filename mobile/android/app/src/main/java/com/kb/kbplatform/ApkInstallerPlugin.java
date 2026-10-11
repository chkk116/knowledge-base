/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

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
    /** 同一个包只允许一个下载任务在跑 */
    private volatile boolean downloading = false;

    @PluginMethod
    public void downloadAndInstall(final PluginCall call) {
        final String url = call.getString("url");
        if (url == null || url.trim().isEmpty()) {
            call.reject("缺少下载地址");
            return;
        }
        if (downloading) {
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
        downloading = true;

        new Thread(() -> {
            try {
                File apk = download(url, fileName, checksum);
                JSObject res = new JSObject();
                res.put("filePath", apk.getAbsolutePath());
                res.put("size", apk.length());
                res.put("launched", true);
                launchInstall(apk);
                call.resolve(res);
            } catch (Exception e) {
                JSObject err = new JSObject();
                err.put("message", String.valueOf(e.getMessage()));
                notifyListeners("downloadError", err);
                call.reject("下载失败：" + e.getMessage());
            } finally {
                downloading = false;
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
        File f = new File(path);
        if (!f.exists()) {
            call.reject("安装包不存在，请重新下载");
            return;
        }
        launchInstall(f);
        call.resolve();
    }

    // ---------------- 内部实现 ----------------

    private File download(String url, String fileName, String expectedSha256) throws Exception {
        // 原生层白名单兜底：只允许 https（JS 侧已校验，这里防未来新增调用点漏检）
        if (!url.startsWith("https://")) throw new Exception("更新地址必须为 HTTPS");
        // 原生层域名白名单：host 必须在构建期注入的 kb_allowed_hosts 内。
        // 这是最后一道防线——WebView 内被注入的 JS 可以绕过 JS 侧校验直接调桥，
        // 只有原生自己持有的白名单才拦得住（外部审查 2026-10-11）。
        java.util.Set<String> allowed = allowedHosts();
        String host = new URL(url).getHost();
        if (allowed.isEmpty()) throw new Exception("App 未配置允许的更新域名（KB_ALLOWED_HOSTS），已拒绝下载");
        if (!allowed.contains(host)) throw new Exception("更新地址域名不在白名单内");
        File dir = new File(getContext().getCacheDir(), "update");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建缓存目录");
        File out = new File(dir, fileName);
        if (out.exists() && !out.delete()) throw new Exception("无法覆盖旧安装包");

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(60_000);
        // 禁止自动跟随重定向：防 https→http 降级或跨域重定向绕过 URL 校验
        conn.setInstanceFollowRedirects(false);
        conn.connect();

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
                    p.put("percent", total > 0 ? Math.round(received * 100.0 / total) : -1);
                    notifyListeners("progress", p);
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
