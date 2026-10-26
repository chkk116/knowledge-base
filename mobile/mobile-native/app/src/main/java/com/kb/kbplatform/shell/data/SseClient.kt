/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.data

import com.kb.kbplatform.shell.AppConfig
import com.kb.kbplatform.shell.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 通知 SSE 长连接（GET /api/notifications/stream）。
 *
 * - 登录即连、登出/换后端地址即断（由 AppRoot 驱动 start/stop）
 * - 事件体 {"type":"notification","unread":{...}}；ping 注释行由 EventSource 自动忽略
 * - 未读数以服务端为准：连上后 MessagesScreen 拉 /notifications/unread 校准；
 *   SSE 到达 +1，点开已读 -1，仅作角标展示，不做持久化
 * - 断线自动重连（10s 退避），与 Web 端 fetch 流式监听行为对齐
 */
object SseClient {
    private var es: EventSource? = null
    private var lastToken: String? = null
    private val reconScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread

    /** 任意新通知到达（携带通知 JSON），消息页监听后自动刷新列表 */
    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 8)
    val events: SharedFlow<JSONObject> = _events

    fun setUnread(n: Int) { _unread.value = n.coerceAtLeast(0) }
    fun decrementUnread() { _unread.value = (_unread.value - 1).coerceAtLeast(0) }

    private fun client(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)   // SSE 长连接不设读超时
            .build()

    fun start() {
        val token = TokenStore.token.value ?: return stop()
        if (es != null && token == lastToken) return
        stop()
        lastToken = token

        val req = Request.Builder()
            .url(AppConfig.effectiveBaseUrl + "/api/notifications/stream")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .header("User-Agent", ApiClient.USER_AGENT)
            .build()

        es = EventSources.createFactory(client()).newEventSource(req, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val obj = JSONObject(data)
                    if (obj.optString("type") == "notification") {
                        _unread.value += 1
                        val payload = obj.optJSONObject("unread") ?: obj
                        _events.tryEmit(payload)
                    }
                } catch (e: Exception) { /* 非 JSON 数据忽略 */ }
            }

            override fun onClosed(eventSource: EventSource) { scheduleReconnect() }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (es == null) return   // 主动 stop 过，不重连
        reconnectJob?.cancel()
        reconnectJob = reconScope.launch {
            delay(10_000)
            if (es != null) start()
        }
    }

    fun stop() {
        reconnectJob?.cancel()
        reconnectJob = null
        es?.cancel()
        es = null
        lastToken = null
        _unread.value = 0
    }
}
