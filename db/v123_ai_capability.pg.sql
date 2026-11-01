-- v123 AI 能力配置（文生图 / 文生视频 / OCR）
-- 与 kb_ai_model（对话模型池）完全独立：每项能力自己存平台/端点/Key/模型名，
-- 互不干扰；未配置时业务侧仍回退到原逻辑（媒体取模型池、OCR 走视觉模型链）。
-- api_key 存 AES-GCM 密文（AiKeyCipher），绝不存明文。

CREATE TABLE IF NOT EXISTS kb_ai_capability (
    id          BIGSERIAL PRIMARY KEY,
    capability  VARCHAR(16)  NOT NULL UNIQUE,   -- image | video | ocr
    provider    VARCHAR(32),
    base_url    VARCHAR(255),
    api_key     TEXT,
    model       VARCHAR(128),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
