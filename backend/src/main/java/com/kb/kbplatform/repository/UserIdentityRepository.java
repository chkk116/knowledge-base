/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.UserIdentity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

    Optional<UserIdentity> findByProviderAndExternalId(String provider, String externalId);

    List<UserIdentity> findByUserIdAndProvider(Long userId, String provider);

    Optional<UserIdentity> findFirstByUserIdAndProvider(Long userId, String provider);

    void deleteByUserIdAndProvider(Long userId, String provider);
}
