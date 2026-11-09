-- AI 反馈处理状态（二期：跨设备持久化 + 补文档后回测）
-- ⚠️ KB_DDL_AUTO=validate：新增实体必须先手工建表，再发 jar。
-- action_key 与前端一致：'m:'+问题(截120) / 'n:'+差评记录id / 'r:'+未命中记录id
CREATE TABLE IF NOT EXISTS kb_ai_feedback_action (
    id          BIGSERIAL PRIMARY KEY,
    action_key  VARCHAR(160) NOT NULL UNIQUE,
    status      VARCHAR(16)  NOT NULL,
    note        VARCHAR(200),
    actor_id    BIGINT,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ai_feedback_action_status ON kb_ai_feedback_action (status);
