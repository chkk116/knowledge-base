package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.DmMessage;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DmMessageRepository extends JpaRepository<DmMessage, Long> {

    /**
     * 把整个会话里发给我的未读一次性标已读。
     * 原实现只取最近 100 条逐条 save：对方发了 100 条以上、或窗口里挤满我自己发出的消息时，
     * 剩下的未读永远标不掉，红点消了又回来（而且一次事务里最多 100 次写往返）。
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "update DmMessage m set m.readAt = :now where m.convKey = :convKey and m.toId = :me and m.readAt is null")
    int markConvRead(@org.springframework.data.repository.query.Param("convKey") String convKey,
                     @org.springframework.data.repository.query.Param("me") Long me,
                     @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);

    List<DmMessage> findByConvKeyOrderByIdDesc(String convKey, Pageable page);

    /** 向上翻页：取某条消息之前的 n 条（原来的 n*2 窗口在翻旧消息时会提前断页） */
    List<DmMessage> findTop50ByConvKeyAndIdLessThanOrderByIdDesc(String convKey, Long id, Pageable page);

    /** 某人发给某人的总条数（用于「未互关最多 3 条」判定） */
    long countByFromIdAndToId(Long fromId, Long toId);

    /** 对方是否回过我：回过就解除 3 条限制（等价于"接受了对话"） */
    boolean existsByFromIdAndToId(Long fromId, Long toId);

    long countByToIdAndReadAtIsNull(Long toId);

    /** 按会话聚合未读数（审查 r21：原实现只基于最近 200 条消息，长会话未读数会漏计） */
    @org.springframework.data.jpa.repository.Query(
            "select m.fromId, count(m) from DmMessage m where m.toId = :me and m.readAt is null group by m.fromId")
    java.util.List<Object[]> countUnreadByPeer(@org.springframework.data.repository.query.Param("me") Long me);

    /** 会话列表：我参与的会话里最新一条（按 id desc 取，再在内存里按会话去重） */
    List<DmMessage> findTop500ByFromIdOrToIdOrderByIdDesc(Long fromId, Long toId);

    long deleteByCreatedAtBefore(LocalDateTime time);

    /** 私信附件鉴权：按 content 引用找出所有引用它的消息，判定当前用户是否为会话双方之一 */
    List<DmMessage> findByContent(String content);
}
