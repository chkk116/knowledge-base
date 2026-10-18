/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 系统分享接收（Android）。
 *
 * 让「知识库」出现在系统分享面板 / 「用其他应用打开」列表里（见 AndroidManifest 的 intent-filter），
 * 并接住分享过来的 PDF / 图片 / 文本：复制到 App 缓存目录 cacheDir/share/，把清单交给 Web 层
 * 去上传并建成文档。
 *
 * 通信方式：
 *   - 冷启动（App 没开）：Web 加载后调 getPending() 取清单。
 *   - 热启动（App 已开）：notifyListeners("shareReceived", {files}) 推送，Web 监听后跳导入页。
 *   - 处理完调 clear() 清空。
 *
 * 每个文件项：{ name, mime, size, path, text? }
 *   path 是**相对 cacheDir** 的路径（如 share/xxxx-a.pdf），供 @capacitor/filesystem 的
 *   Directory.Cache 读取（readFile 拿 base64 → 转 Blob 上传）。
 *
 * ⚠️ 微信分享来的 URI 是 content://（如 content://com.tencent.mm.external.fileprovider/...），
 *    必须走 ContentResolver 读取，不能当 file:// 处理；临时读权限由系统在投递 intent 时授予。
 */
@CapacitorPlugin(name = "ShareReceiver")
public class ShareReceiverPlugin extends Plugin {

    private static final String DIR = "share";
    private final List<JSObject> pending = new ArrayList<>();

    @Override
    public void load() {
        // 冷启动：Activity 的启动 intent 就是分享 intent
        try { handleIntent(getActivity() == null ? null : getActivity().getIntent()); }
        catch (Exception e) { /* 忽略：分享处理失败不能拖垮启动 */ }
    }

    /** 处理一个分享 intent（冷启动由 load 调，热启动由 MainActivity.onNewIntent 转发） */
    public void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action)
                && !Intent.ACTION_SEND_MULTIPLE.equals(action)
                && !Intent.ACTION_VIEW.equals(action)) {
            return;
        }

        List<Uri> uris = new ArrayList<>();
        String text = null;
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else {
            Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (single != null) uris.add(single);
            else if (intent.getData() != null) uris.add(intent.getData());   // ACTION_VIEW
        }
        if (Intent.ACTION_SEND.equals(action)) text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (uris.isEmpty() && (text == null || text.trim().isEmpty())) return;

        List<JSObject> files = new ArrayList<>();
        for (Uri u : uris) {
            JSObject f = copyToCache(u);
            if (f != null) files.add(f);
        }
        // 纯文本分享（无文件）：落成一个 .txt 便于统一处理
        if (files.isEmpty() && text != null && !text.trim().isEmpty()) {
            JSObject f = writeText(text);
            if (f != null) files.add(f);
        }
        if (files.isEmpty()) return;

        synchronized (this) {
            pending.clear();
            pending.addAll(files);
        }
        JSArray arr = new JSArray();
        for (JSObject o : files) arr.put(o);
        JSObject payload = new JSObject();
        payload.put("files", arr);
        notifyListeners("shareReceived", payload);

        // 消费掉启动 intent，避免旋转/重建时重复触发
        try {
            Intent consumed = new Intent(Intent.ACTION_MAIN);
            getActivity().setIntent(consumed);
        } catch (Exception e) { /* ignore */ }
    }

    /** Web 冷启动时取待导入清单 */
    @PluginMethod
    public void getPending(PluginCall call) {
        JSArray arr = new JSArray();
        synchronized (this) { for (JSObject o : pending) arr.put(o); }
        JSObject ret = new JSObject();
        ret.put("files", arr);
        call.resolve(ret);
    }

    /** 导入完成后清空 */
    @PluginMethod
    public void clear(PluginCall call) {
        synchronized (this) { pending.clear(); }
        call.resolve();
    }

    /**
     * 解压分享过来的压缩包。
     *
     * 背景：部分平台（含微信的某些转发路径）会把 PDF / 图片先打包成 zip 再分享，
     * 系统分享面板按 MIME 过滤时这些条目就不是 application/pdf 了；若清单里不注册 zip，
     * 「知识库」根本不会出现在分享列表里。
     *
     * 实现：按**魔数**判断真实类型（PK = zip，Rar! = rar），不信任扩展名——
     * 常见「.rar 其实是 zip」的情况也能正确处理。真 RAR 需要额外依赖，直接给出明确提示。
     *
     * 安全：源文件必须位于 cacheDir/share 内；条目名做 zip-slip 过滤；
     * 限制条目数 200 / 总解压体积 200MB，防止 zip 炸弹。
     *
     * @param path 相对 cacheDir 的路径（与 getPending 返回的 path 同格式）
     * @return { files: [{ name, mime, size, path }] }
     */
    @PluginMethod
    public void extract(PluginCall call) {
        final String path = call.getString("path");
        if (path == null || path.trim().isEmpty()) { call.reject("缺少文件路径"); return; }

        File base = new File(getContext().getCacheDir(), DIR);
        File src = new File(getContext().getCacheDir(), path);
        try {
            String baseCanon = base.getCanonicalPath() + File.separator;
            if (!src.getCanonicalPath().startsWith(baseCanon)) { call.reject("非法的文件路径"); return; }
        } catch (Exception e) { call.reject("路径校验失败"); return; }
        if (!src.exists()) { call.reject("文件不存在"); return; }

        // 魔数判断真实格式，别信扩展名
        String magic;
        try (InputStream in = new FileInputStream(src)) {
            byte[] head = new byte[4];
            int n = in.read(head);
            magic = n >= 4 ? new String(head, 0, 4, "ISO-8859-1") : "";
        } catch (Exception e) { call.reject("读取失败：" + e.getMessage()); return; }

        if (magic.startsWith("Rar!")) {
            call.reject("这是 RAR 压缩包，App 内暂不支持解压，请先在文件管理里解压后再分享");
            return;
        }
        if (!magic.startsWith("PK")) { call.reject("不是可识别的压缩包（仅支持 ZIP）"); return; }

        File outDir = new File(base, "unzip-" + UUID.randomUUID().toString().substring(0, 8));
        if (!outDir.mkdirs()) { call.reject("无法创建解压目录"); return; }

        final int MAX_COUNT = 200;
        final long MAX_TOTAL = 200L * 1024 * 1024;
        JSArray arr = new JSArray();
        int count = 0;
        long total = 0;

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(src)))) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                if (count >= MAX_COUNT || total >= MAX_TOTAL) break;
                String entryName = e.getName() == null ? "" : e.getName().replace('\\', '/');
                String simple = entryName.substring(entryName.lastIndexOf('/') + 1);
                // 跳过隐藏文件（.DS_Store / __MACOSX 里的资源叉等）
                if (simple.isEmpty() || simple.startsWith(".")) continue;

                count++;
                File out = new File(outDir, count + "-" + sanitize(simple));
                long size = 0;
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        size += n;
                        total += n;
                        if (total > MAX_TOTAL) break;
                    }
                }
                if (total > MAX_TOTAL) { out.delete(); break; }

                JSObject o = new JSObject();
                o.put("name", simple);
                o.put("mime", guessMime(simple));
                o.put("size", size);
                o.put("path", DIR + "/" + outDir.getName() + "/" + out.getName());
                arr.put(o);
            }
        } catch (Exception e) {
            call.reject("解压失败：" + e.getMessage());
            return;
        }

        if (arr.length() == 0) { call.reject("压缩包里没有可导入的文件"); return; }
        JSObject ret = new JSObject();
        ret.put("files", arr);
        call.resolve(ret);
    }

    /** 按扩展名猜 MIME（解压出来的条目没有 ContentResolver 可查） */
    private String guessMime(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".txt") || n.endsWith(".md")) return "text/plain";
        if (n.endsWith(".doc")) return "application/msword";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".xls")) return "application/vnd.ms-excel";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (n.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        return "application/octet-stream";
    }

    private JSObject copyToCache(Uri uri) {
        try {
            ContentResolver cr = getContext().getContentResolver();
            String name = queryName(cr, uri);
            if (name == null || name.isEmpty()) name = "shared-" + System.currentTimeMillis();
            String mime = cr.getType(uri);

            File dir = new File(getContext().getCacheDir(), DIR);
            if (!dir.exists() && !dir.mkdirs()) return null;
            File out = new File(dir, UUID.randomUUID().toString().substring(0, 8) + "-" + sanitize(name));

            try (InputStream in = cr.openInputStream(uri); FileOutputStream fos = new FileOutputStream(out)) {
                if (in == null) return null;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }

            JSObject o = new JSObject();
            o.put("name", name);
            o.put("mime", mime == null ? "" : mime);
            o.put("size", out.length());
            o.put("path", DIR + "/" + out.getName());
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private JSObject writeText(String text) {
        try {
            File dir = new File(getContext().getCacheDir(), DIR);
            if (!dir.exists() && !dir.mkdirs()) return null;
            String fname = UUID.randomUUID().toString().substring(0, 8) + "-shared.txt";
            File out = new File(dir, fname);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(text.getBytes("UTF-8"));
            }
            JSObject o = new JSObject();
            o.put("name", "分享的文本.txt");
            o.put("mime", "text/plain");
            o.put("size", out.length());
            o.put("path", DIR + "/" + fname);
            o.put("text", text);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private String queryName(ContentResolver cr, Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = cr.query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception e) {
            /* ignore */
        } finally {
            if (c != null) c.close();
        }
        if (name == null) name = uri.getLastPathSegment();
        return name;
    }

    private String sanitize(String n) {
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
