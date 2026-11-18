/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.MailMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MailMessageRepository extends JpaRepository<MailMessage, Long> {

    Optional<MailMessage> findByIdAndUserId(Long id, Long userId);

    long countByAccountIdAndUserId(Long accountId, Long userId);

    boolean existsByAccountIdAndUid(Long accountId, Long uid);

    /** 列表检索：账号可选、关键词可选（主题/发件人/摘要），按时间倒序 */
    @Query("select m from MailMessage m where m.userId = :uid "
            + "and (:acc is null or m.accountId = :acc) "
            + "and (:q is null or :q = '' or lower(m.subject) like lower(concat('%', :q, '%')) "
            + "     or lower(m.fromAddr) like lower(concat('%', :q, '%')) "
            + "     or lower(m.snippet) like lower(concat('%', :q, '%'))) ")
    Page<MailMessage> search(@Param("uid") Long userId, @Param("acc") Long accountId,
                             @Param("q") String q, Pageable pageable);

    List<MailMessage> findByUserIdAndThreadKeyOrderBySentAtAsc(Long userId, String threadKey);
}
