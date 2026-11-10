-- 团队空间邀请链接（企微式「点链接加入空间」）
-- ⚠️ 生产 KB_DDL_AUTO=validate：新增实体必须先手工建表，再发 jar。
-- 一个空间同时只保留一条未撤销邀请；重新生成会把旧的置为 revoked，旧链接立即失效。
CREATE TABLE IF NOT EXISTS kb_space_invite (
    id          BIGSERIAL PRIMARY KEY,
    share_id    BIGINT NOT NULL,
    token       VARCHAR(64) NOT NULL UNIQUE,
    permission  VARCHAR(16) NOT NULL DEFAULT 'READ',
    creator_id  BIGINT NOT NULL,
    expires_at  TIMESTAMP,
    max_uses    INT NOT NULL DEFAULT 0,
    used_count  INT NOT NULL DEFAULT 0,
    revoked     BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_space_invite_share ON kb_space_invite (share_id);
