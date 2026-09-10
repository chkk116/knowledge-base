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

    /** 批量判重：只回传这批词里已存在的，替代 findAll() 把整张词表拉进内存 */
    @org.springframework.data.jpa.repository.Query("select w.word from BannedWord w where w.word in :words")
    java.util.Set<String> findExistingWords(@org.springframework.data.repository.query.Param("words") java.util.Collection<String> words);
}
