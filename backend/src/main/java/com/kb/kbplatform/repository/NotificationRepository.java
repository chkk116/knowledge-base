package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);
    long countByUserIdAndReadFalse(Long userId);
    long deleteByUserIdAndIdIn(Long userId, List<Long> ids);   // 批量删除（仅限本人）
    long deleteByUserId(Long userId);                           // 清空本人全部
}
