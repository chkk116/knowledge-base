/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.data

import android.content.Context
import com.kb.kbplatform.shell.TokenStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 离线缓存（列表级）：文档库的空间列表 + 各空间文档列表，按账号隔离落 JSON 文件。
 * 断网时 DocsScreen 用它兜底展示（标题/时间可看，点开走 WebView 报错）。
 * 与 Capacitor 版 IndexedDB 缓存同级能力的第一步，正文级缓存（Room）留给后续。
 */
object OfflineCache {

    data class Snapshot(
        val savedAt: Long,
        val spaces: List<JSONObject>,
        val docsByShare: Map<Long, List<JSONObject>>
    )

    fun file(ctx: Context): File {
        val user = TokenStore.username.value.ifBlank { "default" }
        return File(ctx.filesDir, "offline_docs_$user.json")
    }

    fun save(ctx: Context, spaces: List<JSONObject>, docsByShare: Map<Long, List<JSONObject>>) {
        try {
            val m = JSONObject()
                .put("savedAt", System.currentTimeMillis())
                .put("spaces", JSONArray().apply { spaces.forEach { put(it) } })
                .put("docs", JSONObject().apply {
                    docsByShare.forEach { (sid, list) ->
                        put(sid.toString(), JSONArray().apply { list.forEach { put(it) } })
                    }
                })
            file(ctx).writeText(m.toString())
        } catch (e: Exception) { /* 缓存失败静默：在线功能不受影响 */ }
    }

    fun load(ctx: Context): Snapshot? {
        return try {
            val f = file(ctx)
            if (!f.exists()) return null
            val m = JSONObject(f.readText())
            val spaces = m.optJSONArray("spaces")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            } ?: emptyList()
            val docs = m.optJSONObject("docs") ?: JSONObject()
            val byShare = mutableMapOf<Long, List<JSONObject>>()
            for (key in docs.keys()) {
                val arr = docs.optJSONArray(key) ?: continue
                byShare[key.toLong()] = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            }
            Snapshot(m.optLong("savedAt", 0), spaces, byShare)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 正文级缓存：打开过的文档整页离线可读 ----------

    private fun contentFile(ctx: Context): File {
        val user = TokenStore.username.value.ifBlank { "default" }
        return File(ctx.filesDir, "offline_content_$user.json")
    }

    /** 缓存一篇文档正文（title + content HTML）；同 id 覆盖，容量上限 100 篇（超出丢最旧） */
    fun saveDocContent(ctx: Context, shareId: Long, docId: Long, title: String, content: String) {
        try {
            val f = contentFile(ctx)
            val root = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            root.put("$shareId:$docId", JSONObject()
                .put("title", title)
                .put("content", content)
                .put("savedAt", System.currentTimeMillis()))
            // 容量控制：超过 100 篇按保存时间丢最旧
            val keys = mutableListOf<String>()
            val kit = root.keys()
            while (kit.hasNext()) keys.add(kit.next())
            if (keys.size > 100) {
                keys.sortBy { root.optJSONObject(it)?.optLong("savedAt", 0) ?: 0 }
                repeat(keys.size - 100) { root.remove(keys[it]) }
            }
            f.writeText(root.toString())
        } catch (e: Exception) { /* 静默 */ }
    }

    data class DocContent(val title: String, val content: String, val savedAt: Long)

    fun loadDocContent(ctx: Context, shareId: Long, docId: Long): DocContent? {
        return try {
            val f = contentFile(ctx)
            if (!f.exists()) return null
            val m = JSONObject(f.readText()).optJSONObject("$shareId:$docId") ?: return null
            DocContent(m.optString("title"), m.optString("content"), m.optLong("savedAt", 0))
        } catch (e: Exception) {
            null
        }
    }
}
