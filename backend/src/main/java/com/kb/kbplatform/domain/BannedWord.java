package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 违禁词（和谐词）：归属某个词库（BannedWordLibrary）。
 * SensitiveWordFilter 仅加载「已启用」词库下的词条到内存，管理页增删词或启停词库后热刷新缓存。
 */
@Entity
@Table(name = "kb_banned_word", uniqueConstraints = @UniqueConstraint(name = "uk_banned_word", columnNames = "word"))
public class BannedWord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String word;

    @Column(name = "library_id")
    private Long libraryId;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public BannedWord() {}

    public BannedWord(String word, Long libraryId) {
        this.word = word;
        this.libraryId = libraryId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getWord() { return word; }
    public void setWord(String word) { this.word = word; }
    public Long getLibraryId() { return libraryId; }
    public void setLibraryId(Long libraryId) { this.libraryId = libraryId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
