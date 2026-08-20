package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    List<Comment> findByDocIdOrderByCreatedAtAsc(Long docId);

    List<Comment> findByUsernameOrderByCreatedAtDesc(String username);
}
