/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.SysSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SysSettingRepository extends JpaRepository<SysSetting, String> {
    Optional<SysSetting> findByKey(String key);
}
