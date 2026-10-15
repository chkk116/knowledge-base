package com.kb.kbplatform.repository;

import java.time.LocalDateTime;

/** Lightweight projection for the cross-space library index; intentionally omits Yjs snapshots. */
public interface SharedDocumentSummary {
    Long getId();
    Long getShareId();
    String getTitle();
    String getContent();
    String getTags();
    Long getOwnerId();
    LocalDateTime getCreatedAt();
    LocalDateTime getUpdatedAt();
}
