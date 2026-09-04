-- 共享文档协同操作日志表
-- 用途：可靠投递（按日志重投恢复）、审计、历史回放
-- 策略：追加写，不删除；yjs_state 快照仍保留做快速加载
CREATE TABLE IF NOT EXISTS kb_doc_op_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id          BIGINT NOT NULL COMMENT '文档ID（kb_shared_document.id）',
    user_id         BIGINT DEFAULT NULL COMMENT '产生该更新的用户ID（awareness/匿名更新可能为空）',
    op_type         VARCHAR(32) NOT NULL DEFAULT 'UPDATE' COMMENT '操作类型：UPDATE/SYNC/AWARENESS/INIT/PERSIST',
    update_blob     LONGBLOB COMMENT 'Yjs update 二进制（UPDATE/SYNC 类型）',
    state_vector    BLOB COMMENT '落库时刻文档状态向量（用于增量同步/去重）',
    clock           BIGINT DEFAULT NULL COMMENT 'Yjs 逻辑时钟（clientID + clock，用于时序/去重）',
    client_id       BIGINT DEFAULT NULL COMMENT 'Yjs clientID',
    content_length  INT DEFAULT NULL COMMENT '本次更新字节长度',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '操作时间（毫秒精度）',
    INDEX idx_doc_time (doc_id, created_at),
    INDEX idx_doc_user (doc_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='共享文档协同操作日志';

-- 普通索引：按 doc + client_id + clock 查询/回放时使用，不强制唯一（clock 可能由业务生成，避免重投冲突）
ALTER TABLE kb_doc_op_log
    ADD INDEX idx_doc_client_clock (doc_id, client_id, clock);
