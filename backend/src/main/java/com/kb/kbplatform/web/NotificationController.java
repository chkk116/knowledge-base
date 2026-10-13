/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.NotificationService;
import com.kb.kbplatform.service.SseSessionRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService svc;
    private final SseSessionRegistry sse;

    public NotificationController(NotificationService svc, SseSessionRegistry sse) {
        this.svc = svc;
        this.sse = sse;
    }

    /** 登录用户：我的通知列表（倒序） */
    @GetMapping
    public List<?> list(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.list(c.userId());
    }

    /** 登录用户：未读数量 */
    @GetMapping("/unread")
    public Map<String, Object> unread(@RequestAttribute("kbUser") TokenClaims c) {
        return Map.of("count", svc.unreadCount(c.userId()));
    }

    /** 实时通知流（SSE）：前端用 fetch 流式读取长连接监听（Authorization 头带令牌，
     *  避免 token 进 URL 落日志/历史——外部审查 2026-10-13 M11）。
     *  AuthFilter 仍保留 ?token= 查询参数兜底：兼容改造前打开的旧标签页（EventSource 无法自定义头）。 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestAttribute("kbUser") TokenClaims c) {
        return sse.register(c.userId(), 30L * 60 * 1000);   // 30 分钟超时；连接断开后前端按退避重连
    }

    /** 登录用户：标记单条已读 */
    @PostMapping("/{id}/read")
    public Map<String, Object> read(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        svc.markRead(id, c.userId());
        return Map.of("ok", true);
    }

    /** 登录用户：全部标已读 */
    @PostMapping("/read-all")
    public Map<String, Object> readAll(@RequestAttribute("kbUser") TokenClaims c) {
        svc.markAllRead(c.userId());
        return Map.of("ok", true);
    }

    /** 登录用户：删除单条通知 */
    @DeleteMapping("/{id}")
    public Map<String, Object> deleteOne(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        svc.deleteOne(id, c.userId());
        return Map.of("ok", true);
    }

    /** 登录用户：批量删除（body: { ids: [...] }） */
    @DeleteMapping("/batch")
    public Map<String, Object> deleteBatch(@RequestAttribute("kbUser") TokenClaims c,
                                           @RequestBody Map<String, List<Long>> body) {
        svc.deleteMany(body.get("ids"), c.userId());
        return Map.of("ok", true);
    }

    /** 登录用户：清空全部通知 */
    @DeleteMapping("/all")
    public Map<String, Object> deleteAll(@RequestAttribute("kbUser") TokenClaims c) {
        svc.deleteAll(c.userId());
        return Map.of("ok", true);
    }
}
