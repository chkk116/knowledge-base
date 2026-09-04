package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.BannedWord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BannedWordRepository extends JpaRepository<BannedWord, Long> {
    Optional<BannedWord> findByWord(String word);
    boolean existsByWord(String word);
    void deleteByWord(String word);

    // 按词库分页查询
    Page<BannedWord> findByLibraryId(Long libraryId, Pageable pageable);
    long countByLibraryId(Long libraryId);
    void deleteByLibraryId(Long libraryId);

    // 仅加载启用中词库的词（供实时过滤缓存）
    List<BannedWord> findByLibraryIdIn(List<Long> libraryIds);
}
