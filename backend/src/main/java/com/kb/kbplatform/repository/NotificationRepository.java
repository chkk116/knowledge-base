/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);
    long countByUserIdAndReadFalse(Long userId);
    long deleteByUserIdAndIdIn(Long userId, List<Long> ids);   // 批量删除（仅限本人）
    long deleteByUserId(Long userId);                           // 清空本人全部

    /** 取消点赞撤回通知（N29） */
    void deleteByUserIdAndTypeAndDocIdAndActor(Long userId, String type, Long docId, String actor);

    /** 全部标记已读：一条 UPDATE 完成，不再把历史通知全部加载进内存逐实体保存 */
    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :uid and n.read = false")
    int markAllRead(@Param("uid") Long userId);

    /** 批量未读数（订阅推送用）：一条分组查询算完所有接收者的未读数，避免逐用户 count（外部审查 2026-10-13） */
    @Query("SELECT n.userId, COUNT(n) FROM Notification n WHERE n.userId IN :ids AND n.read = false GROUP BY n.userId")
    List<Object[]> countUnreadByUserIds(@org.springframework.data.repository.query.Param("ids") Collection<Long> ids);
}
