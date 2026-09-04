package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.SysSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SysSettingRepository extends JpaRepository<SysSetting, String> {
    Optional<SysSetting> findByKey(String key);
}
