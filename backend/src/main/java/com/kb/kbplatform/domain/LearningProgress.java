package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "kb_learning_progress")
public class LearningProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long courseId;

    @Column(nullable = false)
    private Long chapterId;

    private boolean finished = false;

    private int progress = 0;

    private LocalDateTime lastAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public boolean isFinished() { return finished; }
    public void setFinished(boolean finished) { this.finished = finished; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public LocalDateTime getLastAt() { return lastAt; }
    public void setLastAt(LocalDateTime lastAt) { this.lastAt = lastAt; }
}
