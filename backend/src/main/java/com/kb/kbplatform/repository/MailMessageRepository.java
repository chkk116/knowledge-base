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

    /** UID 只在单个文件夹内唯一，去重必须带 folder */
    boolean existsByAccountIdAndUidAndFolder(Long accountId, Long uid, String folder);

    /** 批量取某账户某文件夹已存的 UID，避免逐封查库 */
    @Query("select m.uid from MailMessage m where m.accountId = :acc and m.folder = :folder")
    java.util.List<Long> findUids(@Param("acc") Long accountId, @Param("folder") String folder);

    /** 列表检索：账号可选、关键词可选（主题/发件人/摘要），按时间倒序 */
    @Query("select m from MailMessage m where m.userId = :uid "
            + "and (:folder is null or :folder = '' or m.folder = :folder) "
            + "and (:acc is null or m.accountId = :acc) "
            + "and (:q is null or :q = '' or lower(m.subject) like lower(concat('%', :q, '%')) "
            + "     or lower(m.fromAddr) like lower(concat('%', :q, '%')) "
            + "     or lower(m.snippet) like lower(concat('%', :q, '%'))) ")
    Page<MailMessage> search(@Param("uid") Long userId, @Param("acc") Long accountId,
                             @Param("folder") String folder, @Param("q") String q, Pageable pageable);

    List<MailMessage> findByUserIdAndThreadKeyOrderBySentAtAsc(Long userId, String threadKey);

    /** 收件人自动补全：从历史邮件的发件人里取去重地址 */
    @Query("select distinct m.fromAddr from MailMessage m where m.userId = :uid "
            + "and m.fromAddr is not null and (:q is null or :q = '' or lower(m.fromAddr) like lower(concat('%', :q, '%'))) "
            + "order by m.fromAddr")
    List<String> findContacts(@Param("uid") Long userId, @Param("q") String q, Pageable pageable);
}
