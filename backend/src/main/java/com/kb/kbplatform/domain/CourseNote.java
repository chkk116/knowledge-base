package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 学员个人学习笔记：每人对每章最多一条，私有，仅本人可见。
 * 表由 JPA（ddl-auto: update）自动创建。
 */
@Entity
@Table(name = "kb_course_note", indexes = {
        @Index(name = "idx_note_user_ch", columnList = "userId,chapterId", unique = true)
})
public class CourseNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long chapterId;

    @Column(columnDefinition = "text")
    private String content;

    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
