package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.CourseNote;
import com.kb.kbplatform.repository.NoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class NoteService {

    private final NoteRepository repo;

    public NoteService(NoteRepository repo) { this.repo = repo; }

    /** 取某人某章笔记（无则返回空串） */
    public String getNote(Long userId, Long chapterId) {
        return repo.findByUserIdAndChapterId(userId, chapterId)
                .map(CourseNote::getContent)
                .orElse("");
    }

    /** 保存笔记：同一 (userId, chapterId) 幂等 upsert */
    @Transactional
    public String saveNote(Long userId, Long chapterId, String content) {
        if (content == null) content = "";
        CourseNote n = repo.findByUserIdAndChapterId(userId, chapterId).orElseGet(() -> {
            CourseNote c = new CourseNote();
            c.setUserId(userId);
            c.setChapterId(chapterId);
            return c;
        });
        n.setContent(content);
        n.setUpdatedAt(LocalDateTime.now());
        repo.save(n);
        return content;
    }
}
