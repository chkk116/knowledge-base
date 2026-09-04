package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.SharedDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SharedDocumentRepository extends JpaRepository<SharedDocument, Long> {

    List<SharedDocument> findByShareIdOrderByUpdatedAtDesc(Long shareId);

    Optional<SharedDocument> findByIdAndShareId(Long id, Long shareId);

    /** 某用户作为文档所有者占用的总存储字节数（content + yjs_state），用于个人配额(默认 200MB)核算；
     *  两列任一为 NULL 时用 COALESCE 归零，避免整行被 SUM 丢弃导致配额失效 */
    @Query(value = "SELECT COALESCE(SUM(COALESCE(LENGTH(content),0) + COALESCE(LENGTH(yjs_state),0)), 0) FROM kb_shared_document WHERE owner_id = ?1", nativeQuery = true)
    long sumSizeByOwnerId(Long ownerId);

    /** 某空间内全部文档占用的总存储字节数（content + yjs_state），用于空间容量上限核算；
     *  两列任一为 NULL 时用 COALESCE 归零，避免整行被 SUM 丢弃导致容量统计失真 */
    @Query(value = "SELECT COALESCE(SUM(COALESCE(LENGTH(content),0) + COALESCE(LENGTH(yjs_state),0)), 0) FROM kb_shared_document WHERE share_id = ?1", nativeQuery = true)
    long sumSizeByShareId(Long shareId);
}
