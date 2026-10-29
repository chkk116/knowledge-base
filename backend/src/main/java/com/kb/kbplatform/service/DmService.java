/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.DmMessage;
import com.kb.kbplatform.domain.DmSetting;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.*;
import com.kb.kbplatform.security.HtmlSanitizer;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 私信（一对一）。
 *
 * 反骚扰规则（用户定的）：
 *  - 未「互相关注」且对方从没回过我 → 我最多只能发 3 条；
 *    对方一旦回过一条，就视为接受了对话，限制解除（否则对方永远回不了嘴）
 *  - 对方关闭了私信开关 → 直接拒绝，且不告诉他"你被限流了"
 *  - 自己不能给自己发
 */
@Service
public class DmService {

    private static final int STRANGER_LIMIT = 3;
    private static final int MAX_LEN = 2000;

    private final DmMessageRepository msgs;
    private final DmSettingRepository settings;
    private final FollowRepository follows;
    private final UserRepository users;
    private final SseSessionRegistry sse;

    public DmService(DmMessageRepository msgs, DmSettingRepository settings,
                     FollowRepository follows, UserRepository users,
                     SseSessionRegistry sse) {
        this.msgs = msgs;
        this.settings = settings;
        this.follows = follows;
        this.users = users;
        this.sse = sse;
    }

    public boolean allowDm(Long userId) {
        return settings.findById(userId).map(DmSetting::isAllowDm).orElse(true);
    }

    @Transactional
    public Map<String, Object> setAllowDm(Long userId, boolean allow) {
        DmSetting s = settings.findById(userId).orElseGet(() -> {
            DmSetting n = new DmSetting();
            n.setUserId(userId);
            return n;
        });
        s.setAllowDm(allow);
        s.setUpdatedAt(LocalDateTime.now());
        settings.save(s);
        return Map.of("allowDm", allow);
    }

    /** 互相关注？ */
    private boolean mutual(Long a, Long b) {
        return follows.existsByFollowerIdAndFollowingId(a, b) && follows.existsByFollowerIdAndFollowingId(b, a);
    }

    /** 剩余可发条数 / 是否受限（给 UI 显示「未互关，还能发 2 条」） */
    public Map<String, Object> quota(Long me, Long peer) {
        boolean mu = mutual(me, peer);
        boolean replied = msgs.existsByFromIdAndToId(peer, me);
        long sent = msgs.countByFromIdAndToId(me, peer);
        long left = mu || replied ? -1 : Math.max(0, STRANGER_LIMIT - sent);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mutual", mu);
        m.put("replied", replied);
        m.put("sent", sent);
        m.put("remaining", left);        // -1 表示不限
        m.put("limited", left >= 0);
        m.put("peerAllowDm", allowDm(peer));
        return m;
    }

    @Transactional
    public Map<String, Object> send(Long me, Long to, String content, String kind) {
        if (to == null || to.equals(me)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能给自己发私信");
        User peer = users.findById(to).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "对方不存在"));
        if (!peer.isEnabled()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "对方账号不可用");
        if (!allowDm(to)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "对方未开放私信");

        String text = HtmlSanitizer.toText(content == null ? "" : content).trim();
        if (text.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能为空");
        if (text.length() > MAX_LEN) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息太长（上限 " + MAX_LEN + " 字）");

        Map<String, Object> q = quota(me, to);
        if (Boolean.TRUE.equals(q.get("limited")) && ((Number) q.get("remaining")).longValue() <= 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "你们还没有互相关注，最多先发 " + STRANGER_LIMIT + " 条；等对方回复后就可以继续聊");
        }

        DmMessage m = new DmMessage();
        m.setConvKey(DmMessage.convKey(me, to));
        m.setFromId(me);
        m.setToId(to);
        m.setKind("IMAGE".equalsIgnoreCase(kind) ? "IMAGE" : "TEXT");
        m.setContent(text);
        m.setCreatedAt(LocalDateTime.now());
        msgs.save(m);

        // 实时推给收件人（多标签页都能收到）；发送方自己靠接口返回当场渲染，不用回推
        try {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("type", "dm");
            ev.put("id", m.getId());
            ev.put("fromId", me);
            ev.put("content", text);
            ev.put("createdAt", m.getCreatedAt());
            sse.pushDm(to, ev);
        } catch (Exception ignore) {
            // 推送失败不影响消息落库（离线用户靠下次拉取）
        }

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("id", m.getId());
        ret.put("quota", quota(me, to));
        return ret;
    }

    /** 会话历史（升序返回，beforeId 用于向上翻页） */
    public List<Map<String, Object>> history(Long me, Long peer, Long beforeId, int size) {
        int n = Math.min(Math.max(size, 1), 50);
        List<DmMessage> list = msgs.findByConvKeyOrderByIdDesc(DmMessage.convKey(me, peer), PageRequest.of(0, n * 2));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = list.size() - 1; i >= 0; i--) {
            DmMessage m = list.get(i);
            if (beforeId != null && m.getId() >= beforeId) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("fromId", m.getFromId());
            row.put("kind", m.getKind());
            row.put("content", m.getContent());
            row.put("createdAt", m.getCreatedAt());
            row.put("read", m.getReadAt() != null);
            out.add(row);
            if (out.size() >= n) break;
        }
        return out;
    }

    /** 会话列表：每个对端取最新一条 + 未读数 */
    public List<Map<String, Object>> conversations(Long me) {
        List<DmMessage> recent = msgs.findTop200ByFromIdOrToIdOrderByIdDesc(me, me);
        Map<Long, Map<String, Object>> byPeer = new LinkedHashMap<>();
        for (DmMessage m : recent) {
            Long peerId = m.getFromId().equals(me) ? m.getToId() : m.getFromId();
            Map<String, Object> row = byPeer.get(peerId);
            if (row == null) {
                User u = users.findById(peerId).orElse(null);
                row = new LinkedHashMap<>();
                row.put("peerId", peerId);
                row.put("name", u == null ? "已注销用户" : (u.getDisplayName() == null || u.getDisplayName().isBlank()
                        ? u.getUsername() : u.getDisplayName()));
                row.put("avatar", u == null ? null : u.getAvatar());
                row.put("lastMessage", m.getContent());
                row.put("lastFromMe", m.getFromId().equals(me));
                row.put("lastAt", m.getCreatedAt());
                row.put("unread", 0);
                row.put("allowDm", allowDm(peerId));
                byPeer.put(peerId, row);
            }
        }
        // 未读数（一次聚合，避免逐会话查询）
        for (DmMessage m : recent) {
            if (!m.getToId().equals(me) || m.getReadAt() != null) continue;
            Map<String, Object> row = byPeer.get(m.getFromId());
            if (row != null) row.put("unread", ((Number) row.get("unread")).intValue() + 1);
        }
        return new ArrayList<>(byPeer.values());
    }

    @Transactional
    public int markRead(Long me, Long peer) {
        List<DmMessage> list = msgs.findByConvKeyOrderByIdDesc(DmMessage.convKey(me, peer), PageRequest.of(0, 100));
        int n = 0;
        for (DmMessage m : list) {
            if (m.getToId().equals(me) && m.getReadAt() == null) {
                m.setReadAt(LocalDateTime.now());
                msgs.save(m);
                n++;
            }
        }
        return n;
    }

    public long unreadTotal(Long me) {
        return msgs.countByToIdAndReadAtIsNull(me);
    }
}
