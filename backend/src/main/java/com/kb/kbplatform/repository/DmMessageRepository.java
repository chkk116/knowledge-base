package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.DmMessage;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DmMessageRepository extends JpaRepository<DmMessage, Long> {

    List<DmMessage> findByConvKeyOrderByIdDesc(String convKey, Pageable page);

    /** 某人发给某人的总条数（用于「未互关最多 3 条」判定） */
    long countByFromIdAndToId(Long fromId, Long toId);

    /** 对方是否回过我：回过就解除 3 条限制（等价于"接受了对话"） */
    boolean existsByFromIdAndToId(Long fromId, Long toId);

    long countByToIdAndReadAtIsNull(Long toId);

    /** 会话列表：我参与的会话里最新一条（按 id desc 取，再在内存里按会话去重） */
    List<DmMessage> findTop200ByFromIdOrToIdOrderByIdDesc(Long fromId, Long toId);

    long deleteByCreatedAtBefore(LocalDateTime time);
}
