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

/**
 * SSE 长连接注册表：按 userId 维护每个登录用户（可能多标签页）的 SseEmitter。
 * 有新通知时由 NotificationService 调用 push，向该用户所有连接推送未读变更。
 */
@Service
public class SseSessionRegistry {

    // userId -> 该用户的全部 emitter（多标签页 / 多设备）
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> clients = new ConcurrentHashMap<>();

    /** 注册一个长连接，连接完成 / 超时后自动清理，避免内存堆积 */
    public SseEmitter register(Long userId, long timeoutMs) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        CopyOnWriteArrayList<SseEmitter> list =
                clients.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> { emitter.complete(); remove(userId, emitter); });
        // 心跳：每 25s 发一个注释事件，保活代理 / 浏览器连接（timeout 通常更长）
        emitter.onError(e -> remove(userId, emitter));
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
                remove(userId, e);
            }
        }
    }

    /** 推送未读数量（NotificationService 计算好再传，避免重复查库） */
    public void pushUnread(Long userId, long unread) {
        push(userId, unread);
    }

    private void remove(Long userId, SseEmitter e) {
        List<SseEmitter> list = clients.get(userId);
        if (list != null) list.remove(e);
    }
}
