/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.web;

import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.DmService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 私信接口。
 *
 * GET  /api/dm/conversations          会话列表（含未读数）
 * GET  /api/dm/messages?peerId=&beforeId=  历史（升序）
 * POST /api/dm/messages               发送 {toId, content, kind}
 * POST /api/dm/read                   标记与某人的消息已读 {peerId}
 * GET  /api/dm/quota?peerId=          限制状态（未互关剩余条数）
 * GET  /api/dm/settings  PUT          私信开关
 * GET  /api/dm/unread                 总未读
 */
@RestController
@RequestMapping("/api/dm")
public class DmController {

    private final DmService svc;

    public DmController(DmService svc) {
        this.svc = svc;
    }

    private Long uid(TokenClaims c) {
        if (c == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return c.userId();
    }

    @GetMapping("/conversations")
    public List<Map<String, Object>> conversations(@RequestAttribute("kbUser") TokenClaims c,
                                                   @RequestParam(defaultValue = "false") boolean archived) {
        return svc.conversations(uid(c), archived);
    }

    /** 归档 / 取消归档（右滑） */
    @PostMapping("/conversations/{peerId}/archive")
    public Map<String, Object> archive(@RequestAttribute("kbUser") TokenClaims c,
                                       @PathVariable Long peerId,
                                       @RequestBody(required = false) Map<String, Object> body) {
        boolean archived = body == null || !Boolean.FALSE.equals(body.get("archived"));
        svc.setArchived(uid(c), peerId, archived);
        return Map.of("ok", true, "archived", archived);
    }

    /** 删除会话（左滑；本用户视角软删，不影响对方） */
    @DeleteMapping("/conversations/{peerId}")
    public Map<String, Object> deleteConv(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long peerId) {
        svc.deleteConv(uid(c), peerId);
        return Map.of("ok", true);
    }

    @GetMapping("/messages")
    public List<Map<String, Object>> messages(@RequestAttribute("kbUser") TokenClaims c,
                                              @RequestParam Long peerId,
                                              @RequestParam(required = false) Long beforeId,
                                              @RequestParam(defaultValue = "30") int size) {
        return svc.history(uid(c), peerId, beforeId, size);
    }

    @PostMapping("/messages")
    public Map<String, Object> send(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        Long to = body.get("toId") instanceof Number n ? n.longValue() : null;
        if (to == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少收件人");
        String content = body.get("content") == null ? "" : String.valueOf(body.get("content"));
        String kind = body.get("kind") == null ? "TEXT" : String.valueOf(body.get("kind"));
        Long replyId = body.get("replyId") instanceof Number n2 ? n2.longValue() : null;
        return svc.send(uid(c), to, content, kind, replyId);
    }

    /** 撤回自己发的消息（2 分钟内） */
    @DeleteMapping("/messages/{id}")
    public Map<String, Object> recall(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        return svc.recall(uid(c), id);
    }

    @PostMapping("/read")
    public Map<String, Object> read(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        Long peer = body.get("peerId") instanceof Number n ? n.longValue() : null;
        if (peer == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少会话对象");
        return Map.of("read", svc.markRead(uid(c), peer));
    }

    @GetMapping("/quota")
    public Map<String, Object> quota(@RequestAttribute("kbUser") TokenClaims c, @RequestParam Long peerId) {
        return svc.quota(uid(c), peerId);
    }

    @GetMapping("/settings")
    public Map<String, Object> getSettings(@RequestAttribute("kbUser") TokenClaims c) {
        return Map.of("allowDm", svc.allowDm(uid(c)));
    }

    @PutMapping("/settings")
    public Map<String, Object> setSettings(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        boolean allow = !Boolean.FALSE.equals(body.get("allowDm"));
        return svc.setAllowDm(uid(c), allow);
    }

    @GetMapping("/unread")
    public Map<String, Object> unread(@RequestAttribute("kbUser") TokenClaims c) {
        return Map.of("unread", svc.unreadTotal(uid(c)));
    }
}
