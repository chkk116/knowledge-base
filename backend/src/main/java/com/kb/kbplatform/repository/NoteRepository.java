package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.CourseNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NoteRepository extends JpaRepository<CourseNote, Long> {

    Optional<CourseNote> findByUserIdAndChapterId(Long userId, Long chapterId);
}
