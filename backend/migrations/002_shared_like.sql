-- 迁移 002：新增空间文档点赞表 kb_shared_like
-- 背景：生产 ddl-auto=validate，Hibernate 不会自动建表，必须手动执行本脚本后再发版。
-- 知识库帖子点赞仍走 kb_like（结构不变），空间文档点赞走本表，互不影响。
-- 执行：psql -h 127.0.0.1 -p 5433 -U <pguser> -d kb -f 002_shared_like.sql

CREATE TABLE IF NOT EXISTS kb_shared_like (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    doc_id      BIGINT      NOT NULL,          -- 指 SharedDocument 主键 id
    created_at  TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uk_shared_like_userid_docid UNIQUE (user_id, doc_id)
);

CREATE INDEX IF NOT EXISTS idx_shared_like_doc ON kb_shared_like (doc_id);

COMMENT ON TABLE  kb_shared_like IS '空间文档（SharedDocument）点赞记录';
COMMENT ON COLUMN kb_shared_like.doc_id IS '被点赞的空间文档 id';
