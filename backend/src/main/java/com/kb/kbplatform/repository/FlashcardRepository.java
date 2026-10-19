/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Flashcard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FlashcardRepository extends JpaRepository<Flashcard, Long> {

    /** 全部卡片，按到期时间升序（到期的自然排在前面） */
    List<Flashcard> findByUserIdOrderByDueAtAsc(Long userId);

    Optional<Flashcard> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    long countByUserIdAndDueAtLessThanEqual(Long userId, LocalDateTime at);

    List<Flashcard> findByUserIdAndDueAtLessThanEqualOrderByDueAtAsc(Long userId, LocalDateTime at);
}
