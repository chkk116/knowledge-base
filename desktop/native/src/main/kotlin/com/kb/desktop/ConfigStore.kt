package com.kb.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** 本机配置：存服务器地址与上次用户名。仅存非敏感信息，token 不落盘。 */
object ConfigStore {

    /** 仓库默认值禁止私人域名 → 默认指向本机后端；换环境用登录框修改或设 KB_DESKTOP_SERVER。 */
    val defaultServer: String =
        System.getenv("KB_DESKTOP_SERVER")?.takeIf { it.isNotBlank() } ?: "http://localhost:8080"

    private val file: Path by lazy {
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")
        Paths.get(appData, "kb-desktop", "config.json")
    }

    fun read(): Map<String, String> = try {
        val text = Files.readString(file)
        val entries = Regex("""\"([A-Za-z]+)\"\s*:\s*\"((?:[^\"\\]|\\.)*)\"""")
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\") }
        entries
    } catch (e: Exception) {
        emptyMap()
    }

    fun write(server: String, username: String) {
        try {
            Files.createDirectories(file.parent)
            val json = """{"server":${escape(server)},"username":${escape(username)}}"""
            Files.writeString(file, json)
        } catch (e: Exception) {
            // 配置写失败不阻塞使用
        }
    }

    private fun escape(v: String): String = buildString {
        append('"')
        for (ch in v) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> append(ch)
        }
        append('"')
    }
}
