package com.kb.desktop

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 桌面端应用内更新：下载安装包 → 校验 → 交给 Inno Setup 静默覆盖安装 → 自动重启。
 *
 * 为什么可行：安装包是 Inno Setup 6（`packaging/kb-desktop.iss`），`AppId` 固定、
 * `CloseApplications=yes`，用 `/VERYSILENT /SUPPRESSMSGBOXES /NORESTART` 就能静默覆盖到原目录。
 * 重启由我们自己的小 `.cmd` 负责（`start /wait installer` → 再 `start <自身 exe>`），
 * 不依赖 Inno 的 `/RESTARTAPPLICATIONS`（它只重启被 Restart Manager 关闭的进程，未必包含自己退出的我们）。
 *
 * 安装目录不做假设：取当前进程的命令行（jpackage 安装版就是 `<安装目录>\kb-desktop.exe`），
 * 拿不到就退回注册表 `InstallLocation`，再拿不到就不自动重启（提示用户手动打开）。
 */
object KbUpdater {

    /** 更新包落盘目录：`%LOCALAPPDATA%\kb-desktop\updates`（没有 LOCALAPPDATA 时退回用户目录） */
    fun updatesDir(): Path {
        val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")
        val dir = Paths.get(base, "kb-desktop", "updates")
        Files.createDirectories(dir)
        return dir
    }

    /**
     * 把外部传入的文件名收敛成**纯文件名**：丢弃所有路径分隔符（绝对路径 / `../` 全部失效）、
     * 非法字符替换为 `_`；为空、`.`、`..` 或仍含 `..` 的一律拒绝，防止写到更新目录之外。
     */
    fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val name = base.replace(Regex("[\\x00-\\x1f<>:\"|?*]"), "_")
        require(name.isNotEmpty() && name != "." && name != ".." && !name.contains("..")) {
            "非法的更新包文件名：" + raw
        }
        return name
    }

    /**
     * 下载到 [updatesDir]，边下边回调 `(已下载字节, 总字节)`（总字节未知时为 -1）。
     * 文件名先净化为纯文件名，再校验规范化后的落盘路径仍在更新目录内。
     */
    suspend fun download(
        url: String,
        fileName: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Path = withContext(Dispatchers.IO) {
        val dir = updatesDir().toAbsolutePath().normalize()
        val dest = dir.resolve(sanitizeFileName(fileName)).normalize()
        if (!dest.startsWith(dir) || dest == dir) {
            throw IllegalStateException("非法的更新包路径：" + fileName)
        }
        // 上一次残留的先删掉，避免续传到坏文件
        runCatching { if (Files.exists(dest)) Files.delete(dest) }
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(30))
            .GET()
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        if (resp.statusCode() >= 400) {
            throw IllegalStateException("下载失败：HTTP " + resp.statusCode())
        }
        val total = resp.headers().firstValueAsLong("content-length").orElse(-1L)
        resp.body().use { input ->
            Files.newOutputStream(dest).use { out ->
                val buf = ByteArray(1 shl 16)
                var got = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    got += n
                    onProgress(got, total)
                }
            }
        }
        dest
    }

    /** SHA-256（小写 hex），与后端 `app_version_desktop.checksum` 同口径 */
    fun sha256(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 写一个「等主程序退出 → 静默安装 → 启动新版」的批处理并立即运行，然后调用方应自行退出应用。
     * 返回 true 表示脚本已启动。
     */
    fun launchApplyScript(setup: Path): Boolean = runCatching {
        val selfExe = currentExePath()
        val script = updatesDir().resolve("apply-update.cmd")
        // 路径**不拼进命令行**：改用环境变量传给 cmd，脚本内以 "%VAR%" 引用。
        // Windows 文件名不含 " 与换行（我们这里再净化过），引号内的 & | < > ^ 不会被当成命令，
        // 环境变量展开也不会二次解析 %，因此不存在命令注入。
        val lines = buildList {
            add("@echo off")
            add("rem 等待主程序释放文件")
            add("timeout /t 2 /nobreak >nul")
            add("start \"\" /wait \"%KB_UPDATE_SETUP%\" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART")
            if (selfExe != null) {
                add("start \"\" \"%KB_UPDATE_EXE%\"")
            }
            add("del \"%~f0\"")
        }
        Files.write(script, lines.joinToString("\r\n").toByteArray(Charsets.UTF_8))
        // 用最小化窗口的 cmd 跑，避免黑框挡在前面；脚本自身带 /wait，安装完才启动新版
        val pb = ProcessBuilder("cmd.exe", "/c", "start", "/min", "", script.toAbsolutePath().toString())
            .redirectErrorStream(true)
        pb.environment()["KB_UPDATE_SETUP"] = setup.toAbsolutePath().normalize().toString()
        if (selfExe != null) pb.environment()["KB_UPDATE_EXE"] = selfExe
        pb.start()
        true
    }.getOrElse { false }

    /** 当前进程 exe（安装版为 `<安装目录>\kb-desktop.exe`；开发态是 java.exe，返回 null 则不自动重启） */
    fun currentExePath(): String? {
        val cmd = ProcessHandle.current().info().command().orElse(null) ?: return null
        // 开发态（java.exe / gradle）不自动重启，避免拉起一个不带参数的空 JVM
        val name = java.nio.file.Paths.get(cmd).fileName.toString().lowercase()
        if (name == "java.exe" || name == "javaw.exe") return null
        return if (java.nio.file.Files.isExecutable(java.nio.file.Paths.get(cmd))) cmd else null
    }

    /** 安装目录（当前 exe 的父目录），用于展示与兜底 */
    fun installDir(): String? = currentExePath()?.let {
        runCatching { java.nio.file.Paths.get(it).parent?.toString() }.getOrNull()
    }
}