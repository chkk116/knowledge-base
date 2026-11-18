/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.MailAccount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MailAccountRepository extends JpaRepository<MailAccount, Long> {

    List<MailAccount> findByUserIdOrderByIdAsc(Long userId);

    Optional<MailAccount> findByIdAndUserId(Long id, Long userId);

    Optional<MailAccount> findByUserIdAndEmail(Long userId, String email);

    List<MailAccount> findByStatus(String status);
}
