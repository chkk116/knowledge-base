-- v125：用户私有 AI 模型
-- 普通用户可配置自己的模型（与管理员维护的系统模型 kb_ai_model 完全隔离）：
--   * 管理员「AI 模型管理」只读写 kb_ai_model，看不到用户模型；
--   * 用户只能读写 kb_user_ai_model 中 user_id = 自己的行；
--   * 问答时若用户选了自己的模型，优先用它；否则回落到系统模型。
CREATE TABLE IF NOT EXISTS kb_user_ai_model (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    provider        VARCHAR(32)  NOT NULL,
    name            VARCHAR(100) NOT NULL,
    base_url        VARCHAR(300) NOT NULL,
    api_key         TEXT         NOT NULL,   -- AES-GCM 加密，与系统模型同一把主密钥
    model           VARCHAR(200) NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order      INT          NOT NULL DEFAULT 0,
    context_window  INT          NOT NULL DEFAULT 128000,
    created_at      TIMESTAMP    DEFAULT now(),
    updated_at      TIMESTAMP    DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_user_ai_model_user ON kb_user_ai_model(user_id);
