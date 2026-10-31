-- v121 密码重置链接（PostgreSQL）
-- 管理员生成一次性链接交给用户，用户自己设置新密码；管理员不再经手明文密码。
-- token 只存 SHA-256 哈希，链接 30 分钟有效，用过即作废。

CREATE TABLE IF NOT EXISTS kb_password_reset (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    used_at     TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by  BIGINT
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_pwd_reset_token ON kb_password_reset (token_hash);
CREATE INDEX IF NOT EXISTS idx_pwd_reset_user ON kb_password_reset (user_id);
