-- v118 AI 问答反馈与知识缺口日志（PostgreSQL）
-- 生产 KB_DDL_AUTO=validate：必须先执行本文件，再启动新后端，否则实体与表不一致会启动失败。
-- 每条「AI 回答」落一行；hit 表示本次检索是否命中知识库（通用模式恒为 false，不计入命中率）。
-- rating：NULL=未评价，1=赞，-1=踩；reason 仅在踩时填写。

CREATE TABLE IF NOT EXISTS kb_ai_qa_log (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    question      TEXT,
    answer        TEXT,
    mode          VARCHAR(20),
    hit           BOOLEAN      NOT NULL DEFAULT FALSE,
    source_count  INTEGER      NOT NULL DEFAULT 0,
    sources_json  TEXT,
    rating        INTEGER,
    reason        VARCHAR(500),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    rated_at      TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_qa_log_user    ON kb_ai_qa_log (user_id);
CREATE INDEX IF NOT EXISTS idx_qa_log_created ON kb_ai_qa_log (created_at);
CREATE INDEX IF NOT EXISTS idx_qa_log_rating  ON kb_ai_qa_log (rating);
CREATE INDEX IF NOT EXISTS idx_qa_log_hit     ON kb_ai_qa_log (hit);
