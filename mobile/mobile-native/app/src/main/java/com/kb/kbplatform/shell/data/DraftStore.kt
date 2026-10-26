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
 * 离线草稿队列（对标套壳 utils/offlineStore.js 的 STORE_QUEUE）：
 * 队列项 = { localId, spaceId, title, content, createdAt, tries, lastError }。
 * 保存失败（断网/异常）自动入队；联网后在草稿页手动或自动重试上传，成功即出队。
 * 按登录账号隔离（换号不串数据，与 Web 端行为一致）。
 */
object DraftStore {
    data class Draft(
        val localId: String,
        val spaceId: Long?,
        val title: String,
        val content: String,
        val createdAt: Long,
        val tries: Int = 0,
        val lastError: String = ""
    )

    private fun file(ctx: Context) = File(
        ctx.filesDir, "drafts_${TokenStore.username.value.ifBlank { "default" }}.json"
    )

    @Synchronized
    fun list(ctx: Context): List<Draft> = try {
        val arr = JSONArray(file(ctx).readText())
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Draft(
                    localId = it.optString("localId"),
                    spaceId = if (it.has("spaceId") && !it.isNull("spaceId")) it.optLong("spaceId") else null,
                    title = it.optString("title"),
                    content = it.optString("content"),
                    createdAt = it.optLong("createdAt"),
                    tries = it.optInt("tries", 0),
                    lastError = it.optString("lastError")
                )
            }
        }.sortedByDescending { it.createdAt }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun enqueue(ctx: Context, spaceId: Long?, title: String, content: String): String {
        val localId = "d${System.currentTimeMillis()}_${(100..999).random()}"
        val all = list(ctx).toMutableList()
        all.add(
            0, Draft(localId, spaceId, title, content, System.currentTimeMillis())
        )
        persist(ctx, all)
        return localId
    }

    @Synchronized
    fun remove(ctx: Context, localId: String) {
        persist(ctx, list(ctx).filter { it.localId != localId })
    }

    @Synchronized
    fun update(ctx: Context, localId: String, patch: (Draft) -> Draft) {
        persist(ctx, list(ctx).map { if (it.localId == localId) patch(it) else it })
    }

    /** 重试上传一条草稿；成功返回 true（调用方负责出队） */
    suspend fun upload(ctx: Context, d: Draft): Boolean = try {
        val sid = d.spaceId?.takeIf { it > 0 } ?: KbApi.personalShare().optLong("id", 0)
        KbApi.createShareDoc(sid, d.title, d.content)
        true
    } catch (e: Exception) {
        update(ctx, d.localId) {
            it.copy(tries = it.tries + 1, lastError = e.message ?: "上传失败")
        }
        false
    }

    private fun persist(ctx: Context, drafts: List<Draft>) {
        try {
            val arr = JSONArray()
            drafts.forEach {
                arr.put(
                    JSONObject()
                        .put("localId", it.localId)
                        .put("spaceId", it.spaceId ?: JSONObject.NULL)
                        .put("title", it.title)
                        .put("content", it.content)
                        .put("createdAt", it.createdAt)
                        .put("tries", it.tries)
                        .put("lastError", it.lastError)
                )
            }
            file(ctx).writeText(arr.toString())
        } catch (e: Exception) { /* 静默 */ }
    }
}
