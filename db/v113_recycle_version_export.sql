-- ============================================================
-- 知识库 v113 迁移：回收站/软删除 + 版本历史 + Word 导出
-- 说明：应用使用 spring.jpa.hibernate.ddl-auto: update，
--       启动时会自动补齐下列新增列/表；本文件用于手动补库或记录变更。
--       在已运行的库上重复执行前，请先确认列/表是否已存在（ADD COLUMN 不支持 IF NOT EXISTS）。
-- ============================================================

-- 1) 文档软删除：kb_document 增加 deleted / deleted_at 列与回收站索引
--    （JPA @SQLRestriction("deleted = false") 已默认过滤回收站文档；
--      回收站列表走 DocumentRepository 的 nativeQuery 显式绕过该限制）
ALTER TABLE kb_document
    ADD COLUMN deleted   TINYINT(1)   NOT NULL DEFAULT 0,
    ADD COLUMN deleted_at DATETIME     DEFAULT NULL,
    ADD INDEX idx_doc_deleted (deleted, deleted_at);

-- 2) 共享文档版本快照表：每次「保存版本」或编辑保存前留档，支持历史回溯
CREATE TABLE IF NOT EXISTS kb_shared_doc_version (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    sharedDocId BIGINT       DEFAULT NULL,
    title       VARCHAR(255) DEFAULT NULL,
    content     LONGTEXT,
    yjs_state   LONGBLOB,        -- 协同 Yjs 状态快照（精确还原用）
    editorId    BIGINT       DEFAULT NULL,
    createdAt   DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    INDEX idx_sdocver_doc (sharedDocId, createdAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3) 普通文档版本快照表（保存/编辑时自动留档，可预览与回滚）
--    （若 ddl-auto 已创建则可忽略；此处用 IF NOT EXISTS 防止重复建表报错）
CREATE TABLE IF NOT EXISTS kb_document_version (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    docId     BIGINT       DEFAULT NULL,
    title     VARCHAR(255) DEFAULT NULL,
    content   TEXT,
    category  VARCHAR(255) DEFAULT NULL,
    tags      VARCHAR(255) DEFAULT NULL,
    editorId  BIGINT       DEFAULT NULL,
    createdAt DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    INDEX idx_docver_doc (docId, createdAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4) 操作审计表（kb_audit_log）由 AuditService 复用既有实体，本次无结构变更，无需迁移。

-- 回收站/软删除、版本历史、Word 导出的后端逻辑均已完成，前端页面也已接入：
--   回收站       /recycle                （本人；管理员可见全部并可代恢复/彻底删除）
--   文档版本历史  /post/:id/versions      （作者或管理员可预览/回滚）
--   共享文档版本  协作编辑器「🕑 版本」抽屉（保存/回滚，恢复后中继自动重新下发协同态）
--   Word 导出    文档详情「⬇ Word」按钮  （后端返回 HTML 封装 .doc，中文无乱码）
--   PDF  导出    文档详情「⬇ PDF」按钮   （前端 window.print 弹窗打印另存，零依赖）
