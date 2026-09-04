package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.AiMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiMessageRepository extends JpaRepository<AiMessage, Long> {

    List<AiMessage> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    long countByConversationId(Long conversationId);

    void deleteByConversationId(Long conversationId);
}
