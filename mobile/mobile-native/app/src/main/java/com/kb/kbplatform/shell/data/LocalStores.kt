/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.data

import android.content.Context
import com.kb.kbplatform.shell.TokenStore
import org.json.JSONObject

/**
 * 本地数据（对标套壳的 localStorage 类功能）：
 *  - HistoryStore：浏览历史（打开文档时记录，倒序去重，上限 50）
 *  - Bookmarks：本地收藏（收藏的是空间文档，键 spaceId:docId；对标 utils/bookmarks.js）
 */
object HistoryStore {
    data class Entry(val shareId: Long, val docId: Long, val title: String, val at: Long)

    private fun file(ctx: Context) = java.io.File(
        ctx.filesDir, "history_${TokenStore.username.value.ifBlank { "default" }}.json"
    )

    fun list(ctx: Context): List<Entry> = try {
        val arr = org.json.JSONArray(file(ctx).readText())
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Entry(it.optLong("shareId"), it.optLong("docId"), it.optString("title"), it.optLong("at"))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun record(ctx: Context, shareId: Long, docId: Long, title: String) {
        try {
            val rest = list(ctx).filter { !(it.shareId == shareId && it.docId == docId) }
            val all = listOf(Entry(shareId, docId, title, System.currentTimeMillis())) + rest
            val arr = org.json.JSONArray()
            all.take(50).forEach {
                arr.put(JSONObject().put("shareId", it.shareId).put("docId", it.docId)
                    .put("title", it.title).put("at", it.at))
            }
            file(ctx).writeText(arr.toString())
        } catch (e: Exception) { /* 静默 */ }
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}

object Bookmarks {
    data class Entry(val shareId: Long, val docId: Long, val title: String)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("kb_bookmarks", Context.MODE_PRIVATE)

    private fun keyOf(username: String) = "list_" + username.ifBlank { "default" }

    fun list(ctx: Context): List<Entry> = try {
        val arr = org.json.JSONArray(prefs(ctx).getString(keyOf(TokenStore.username.value), "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Entry(it.optLong("shareId"), it.optLong("docId"), it.optString("title"))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun isBookmarked(ctx: Context, shareId: Long, docId: Long): Boolean =
        list(ctx).any { it.shareId == shareId && it.docId == docId }

    fun toggle(ctx: Context, shareId: Long, docId: Long, title: String) {
        val cur = list(ctx)
        val next = if (cur.any { it.shareId == shareId && it.docId == docId }) {
            cur.filter { !(it.shareId == shareId && it.docId == docId) }
        } else {
            listOf(Entry(shareId, docId, title)) + cur
        }
        val arr = org.json.JSONArray()
        next.take(200).forEach {
            arr.put(JSONObject().put("shareId", it.shareId).put("docId", it.docId).put("title", it.title))
        }
        prefs(ctx).edit().putString(keyOf(TokenStore.username.value), arr.toString()).apply()
    }
}
