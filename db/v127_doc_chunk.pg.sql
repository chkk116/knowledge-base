-- v127 向量 RAG：文档分块表（语义检索语料）
-- DocChunk 实体映射本表；本地 ddl-auto=update 会自动建，生产 KB_DDL_AUTO=validate 必须先执行本文件。
-- 不用 pgvector：分块量级几千，检索全量载入内存算余弦；embedding 为 JSON 浮点数组（RagIndexService 读写）。

CREATE TABLE IF NOT EXISTS kb_doc_chunk (
  id         BIGSERIAL PRIMARY KEY,
  doc_id     BIGINT       NOT NULL,
  chunk_idx  INT          NOT NULL,
  content    TEXT         NOT NULL,
  embedding  TEXT,
  doc_title  VARCHAR(200),
  updated_at TIMESTAMP,
  CONSTRAINT uk_doc_chunk UNIQUE (doc_id, chunk_idx)
);

CREATE INDEX IF NOT EXISTS idx_doc_chunk_doc ON kb_doc_chunk (doc_id);

-- 开启方式（生产）：
--   1) 环境变量 KB_EMBED_ENABLED=true（Key 可省略——自动复用「AI 模型管理」里智谱条目的 Key）
--   2) 重启后端（validate 通过本表结构校验）
--   3) 管理员调一次：POST /api/admin/ai/models/reindex  → 全量建向量索引
