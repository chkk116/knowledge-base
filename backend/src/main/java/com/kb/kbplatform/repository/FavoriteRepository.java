package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Favorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    boolean existsByUserIdAndDocId(Long userId, Long docId);

    void deleteByUserIdAndDocId(Long userId, Long docId);

    List<Favorite> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 作者文档被收藏总数（仅统计已发布文档） */
    @Query(value = "SELECT COUNT(*) FROM kb_favorite f WHERE f.doc_id IN " +
                   "(SELECT id FROM kb_document WHERE owner_id = :owner AND status = 'PUBLISHED')", nativeQuery = true)
    long countFavByOwner(@Param("owner") Long owner);
}
