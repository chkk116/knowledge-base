/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * SSE 长连接注册表：按 userId 维护每个登录用户（可能多标签页）的 SseEmitter。
 * 有新通知时由 NotificationService 调用 push，向该用户所有连接推送未读变更。
 *
 * 心跳：每个连接注册一个 25s 定时任务发送注释事件（": ping"），
 * 防止空闲连接被 nginx proxy_read_timeout（默认 120s）等中间层掐断，
 * 从而避免前端 EventSource 陷入「断开-重连」循环并触发全局错误提示。
 */
@Service
public class SseSessionRegistry {

    /** 心跳间隔：25s（小于 nginx 的 proxy_read_timeout，留足余量） */
    private static final long HEARTBEAT_SECONDS = 25L;

    // userId -> 该用户的全部 emitter（多标签页 / 多设备）
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> clients = new ConcurrentHashMap<>();

    // emitter -> 心跳任务句柄（连接结束时取消）
    private final Map<SseEmitter, ScheduledFuture<?>> beats = new ConcurrentHashMap<>();

    private final ScheduledExecutorService heartbeat = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    /** 注册一个长连接，连接完成 / 超时后自动清理，避免内存堆积 */
    public SseEmitter register(Long userId, long timeoutMs) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        CopyOnWriteArrayList<SseEmitter> list =
                clients.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        // 心跳：每 25s 发一个注释事件，保活代理 / 浏览器连接（timeout 通常更长）
        ScheduledFuture<?> beat = heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception ex) {
                cleanup(userId, emitter);
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        beats.put(emitter, beat);

        emitter.onCompletion(() -> cleanup(userId, emitter));
        emitter.onTimeout(() -> { emitter.complete(); cleanup(userId, emitter); });
        emitter.onError(e -> cleanup(userId, emitter));
        return emitter;
    }

    /** 向某用户所有连接推送数据；连接异常则移除 */
    public void push(Long userId, Object payload) {
        List<SseEmitter> list = clients.get(userId);
        if (list == null || list.isEmpty()) return;
        for (SseEmitter e : list) {
            try {
                e.send(SseEmitter.event()
                        .name("notification")
                        .data(Map.of("type", "notification", "unread", payload)));
            } catch (Exception ex) {
                cleanup(userId, e);
            }
        }
    }


    /** 私信实时推送：独立事件名 dm，与通知未读分开（前端按事件名分流） */
    public void pushDm(Long userId, Object payload) {
        List<SseEmitter> list = clients.get(userId);
        if (list == null || list.isEmpty()) return;
        for (SseEmitter e : list) {
            try {
                e.send(SseEmitter.event().name("dm").data(payload));
            } catch (Exception ex) {
                cleanup(userId, e);
            }
        }
    }


    /** 私信相关事件（发送 / 撤回）：同一会话双方都要收到 */
    public void pushDmEvent(Long userId, String eventName, Object payload) {
        List<SseEmitter> list = clients.get(userId);
        if (list == null || list.isEmpty()) return;
        for (SseEmitter e : list) {
            try {
                e.send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception ex) {
                cleanup(userId, e);
            }
        }
    }

    /** 推送未读数量（NotificationService 计算好再传，避免重复查库） */
    public void pushUnread(Long userId, long unread) {
        push(userId, unread);
    }

    /** 连接结束（完成 / 超时 / 出错）：取消心跳并移除 emitter */
    private void cleanup(Long userId, SseEmitter e) {
        ScheduledFuture<?> beat = beats.remove(e);
        if (beat != null) beat.cancel(false);
        List<SseEmitter> list = clients.get(userId);
        if (list != null) list.remove(e);
    }
}
