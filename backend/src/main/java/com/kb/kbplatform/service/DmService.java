/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.DmConvState;
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

    /**
     * 会话级发送锁：配额是「检查后写入」，并发下可被绕过（审查 r20 High）。
     * 单实例部署用 JVM 锁即可；上多实例时要换成数据库行锁或 Redis 锁。
     */
    private final Map<String, Object> convLocks = new java.util.concurrent.ConcurrentHashMap<>();

    private final DmMessageRepository msgs;
    private final DmSettingRepository settings;
    private final DmConvStateRepository convStates;
    private final FollowRepository follows;
    private final UserRepository users;
    private final AuditService audit;
    private final SseSessionRegistry sse;

    public DmService(DmMessageRepository msgs, DmSettingRepository settings, DmConvStateRepository convStates,
                     FollowRepository follows, UserRepository users,
                     SseSessionRegistry sse, AuditService audit) {
        this.msgs = msgs;
        this.settings = settings;
        this.convStates = convStates;
        this.follows = follows;
        this.users = users;
        this.sse = sse;
        this.audit = audit;
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

    // 注意：send 不能包外层事务（审查 NH4）——事务在锁外开启时，锁释放后本线程的
    // 写入尚未提交，并发线程在 READ COMMITTED 下看不到，3 条限制会被并发刷穿。
    // 锁内仅做单条 save（自动提交），配额立即可见；多表一致性无需求。
    public Map<String, Object> send(Long me, Long to, String content, String kind, Long replyId) {
        if (to == null || to.equals(me)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能给自己发私信");
        User peer = users.findById(to).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "对方不存在"));
        if (!peer.isEnabled()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "对方账号不可用");
        if (!allowDm(to)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "对方未开放私信");

        String text = HtmlSanitizer.toText(content == null ? "" : content).trim();
        if (text.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能为空");
        if (text.length() > MAX_LEN) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息太长（上限 " + MAX_LEN + " 字）");

        // 会话级锁：让「查配额 → 落库」对同一会话串行，防止并发请求把 3 条限制刷穿
        Object lock = convLocks.computeIfAbsent(DmMessage.convKey(me, to), k -> new Object());
        synchronized (lock) {
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
        // 引用快照：只截 200 字存下来，避免前端渲染引用时再翻历史
        if (replyId != null) {
            DmMessage ref = msgs.findById(replyId).orElse(null);
            if (ref != null && ref.getConvKey().equals(DmMessage.convKey(me, to))) {
                m.setReplyId(ref.getId());
                m.setReplyFrom(ref.getFromId());
                String snap = ref.getContent() == null ? "" : ref.getContent();
                if (snap.length() > 200) snap = snap.substring(0, 200);
                m.setReplyText(snap);
            }
        }
        m.setCreatedAt(LocalDateTime.now());
        msgs.save(m);

        // 收件人曾把该会话删掉：新消息到达即恢复显示，否则红点在、会话却永久消失
        convStates.findByUserIdAndPeerId(to, me).filter(DmConvState::isDeleted).ifPresent(st -> {
            st.setDeleted(false);
            st.setArchived(false);
            st.setUpdatedAt(LocalDateTime.now());
            convStates.save(st);
        });

        // 实时推给收件人（多标签页都能收到）；发送方自己靠接口返回当场渲染，不用回推
        try {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("type", "dm");
            ev.put("id", m.getId());
            ev.put("fromId", me);
            ev.put("content", text);
            ev.put("createdAt", m.getCreatedAt());
            if (m.getReplyId() != null) {
                ev.put("replyId", m.getReplyId());
                ev.put("replyFrom", m.getReplyFrom());
                ev.put("replyText", m.getReplyText());
            }
            sse.pushDm(to, ev);
        } catch (Exception ignore) {
            // 推送失败不影响消息落库（离线用户靠下次拉取）
        }

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("id", m.getId());
        ret.put("quota", quota(me, to));
        return ret;
        }   // 会话锁结束
    }

    /** 会话历史（升序返回，beforeId 用于向上翻页） */
    public List<Map<String, Object>> history(Long me, Long peer, Long beforeId, int size) {
        int n = Math.min(Math.max(size, 1), 50);
        String key = DmMessage.convKey(me, peer);
        // 游标分页：beforeId 有值时走 IdLessThan 查询，翻多老的历史都不会断页
        List<DmMessage> list = beforeId == null
                ? msgs.findByConvKeyOrderByIdDesc(key, PageRequest.of(0, n))
                : msgs.findTop50ByConvKeyAndIdLessThanOrderByIdDesc(key, beforeId, PageRequest.of(0, n));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = list.size() - 1; i >= 0; i--) {
            DmMessage m = list.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("fromId", m.getFromId());
            row.put("kind", m.getKind());
            row.put("content", m.getContent());
            row.put("createdAt", m.getCreatedAt());
            row.put("read", m.getReadAt() != null);
            if (m.getReplyId() != null) {
                row.put("replyId", m.getReplyId());
                row.put("replyFrom", m.getReplyFrom());
                row.put("replyText", m.getReplyText());
            }
            out.add(row);
            if (out.size() >= n) break;
        }
        return out;
    }

    /** 会话列表；archived=true 只看归档。已删除（本用户视角）不返回。 */
    public List<Map<String, Object>> conversations(Long me, boolean archived) {
        // 窗口取 500 条：过滤归档/删除后仍要有足够候选，避免极端流量把主列表挤空
        List<DmMessage> recent = msgs.findTop500ByFromIdOrToIdOrderByIdDesc(me, me);
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
        // 未读数：数据库按会话聚合（审查 r21：原来只基于最近 200 条，长会话未读会漏计）
        for (Object[] row : msgs.countUnreadByPeer(me)) {
            Map<String, Object> row2 = byPeer.get(((Number) row[0]).longValue());
            if (row2 != null) row2.put("unread", ((Number) row[1]).intValue());
        }
        // 归档/删除过滤（按当前用户视角）
        Map<Long, DmConvState> states = new HashMap<>();
        for (DmConvState st : convStates.findByUserId(me)) states.put(st.getPeerId(), st);
        byPeer.entrySet().removeIf(e -> {
            DmConvState st = states.get(e.getKey());
            if (st == null) return archived;              // 无状态：主列表显示，归档区不显示
            if (st.isDeleted()) return true;               // 已删除：两边都不显示
            return st.isArchived() != archived;            // 归档区只看归档，主列表排除归档
        });
        return new ArrayList<>(byPeer.values());
    }

    /** 归档 / 取消归档（右滑） */
    @Transactional
    public void setArchived(Long me, Long peer, boolean archived) {
        DmConvState st = convStates.findByUserIdAndPeerId(me, peer).orElseGet(() -> {
            DmConvState n = new DmConvState();
            n.setUserId(me);
            n.setPeerId(peer);
            return n;
        });
        st.setArchived(archived);
        st.setDeleted(false);
        st.setUpdatedAt(LocalDateTime.now());
        convStates.save(st);
    }

    /** 删除会话（左滑；本用户视角软删，不影响对方） */
    @Transactional
    public void deleteConv(Long me, Long peer) {
        DmConvState st = convStates.findByUserIdAndPeerId(me, peer).orElseGet(() -> {
            DmConvState n = new DmConvState();
            n.setUserId(me);
            n.setPeerId(peer);
            return n;
        });
        st.setDeleted(true);
        st.setArchived(false);
        st.setUpdatedAt(LocalDateTime.now());
        convStates.save(st);
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

    /** 撤回窗口：2 分钟内（与飞书/微信一致） */
    private static final long RECALL_WINDOW_MS = 2 * 60 * 1000L;

    /**
     * 撤回消息：仅发送者本人、2 分钟内。
     * 不物理删除（要留审计痕迹），改成 kind=RECALL + content 清空；
     * 双方通过 SSE 收到 dm-recall 事件即时替换气泡，不必刷新。
     */
    @Transactional
    public Map<String, Object> recall(Long me, Long messageId) {
        DmMessage m = msgs.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "消息不存在"));
        if (!m.getFromId().equals(me)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能撤回自己发的消息");
        if ("RECALL".equals(m.getKind())) return Map.of("recalled", true);
        long age = java.time.Duration.between(m.getCreatedAt(), LocalDateTime.now()).toMillis();
        if (age > RECALL_WINDOW_MS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "超过 2 分钟，无法撤回");
        }
        m.setKind("RECALL");
        m.setContent("");
        msgs.save(m);
        // 撤回属敏感动作（内容会从对方视野消失），进操作审计留痕
        try {
            audit.log(me, String.valueOf(me), "RECALL_DM", "DM", m.getId(), "消息 " + m.getId(), null);
        } catch (Exception ignore) {
        }

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "dm-recall");
        ev.put("id", m.getId());
        ev.put("fromId", m.getFromId());
        ev.put("toId", m.getToId());
        try {
            sse.pushDmEvent(m.getToId(), "dm-recall", ev);
            sse.pushDmEvent(m.getFromId(), "dm-recall", ev);   // 自己的其它标签页也要同步
        } catch (Exception ignore) {
        }
        return Map.of("recalled", true);
    }

    public long unreadTotal(Long me) {
        return msgs.countByToIdAndReadAtIsNull(me);
    }
}
