package com.kb.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 本机配置：存服务器地址与上次用户名。仅存非敏感信息，token 不落盘。 */
object ConfigStore {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 出厂默认服务器：环境变量 KB_DESKTOP_SERVER → 发版时用 -PKB_DESKTOP_SERVER 注入的内置资源
     * → 示例占位地址。仓库不存任何真实公网地址；正式发版必须注入，否则客户端会连到示例占位。
     */
    val defaultServer: String =
        System.getenv("KB_DESKTOP_SERVER")?.takeIf { it.isNotBlank() }
            ?: ConfigStore::class.java.getResourceAsStream("/kb-default-server.txt")
                ?.bufferedReader()?.use { it.readText() }?.trim()?.takeIf { it.isNotBlank() }
            ?: "https://kb.example.com"

    private val file: Path by lazy {
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")
        Paths.get(appData, "kb-desktop", "config.json")
    }

    fun read(): Map<String, String> = try {
        json.decodeFromString<Map<String, String>>(Files.readString(file))
    } catch (e: Exception) {
        emptyMap()
    }

    fun write(server: String, username: String) {
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, json.encodeToString(mapOf("server" to server, "username" to username)))
        } catch (e: Exception) {
            // 配置写失败不阻塞使用
        }
    }
}
