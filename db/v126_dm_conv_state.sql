-- v126：私信会话的用户侧状态（归档 / 删除）
-- 会话本身由消息聚合，这里只存「某个用户对某个对端」的归档/删除标记：
--   archived = true  → 从主列表隐藏，进「归档」
--   deleted  = true  → 该用户视角删除（软删，不影响对方）
CREATE TABLE IF NOT EXISTS kb_dm_conv_state (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT    NOT NULL,
    peer_id     BIGINT    NOT NULL,
    archived    BOOLEAN   NOT NULL DEFAULT FALSE,
    deleted     BOOLEAN   NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMP DEFAULT now(),
    CONSTRAINT uk_dm_conv_state UNIQUE (user_id, peer_id)
);
-- user_id 查询由唯一约束 uk_dm_conv_state(user_id, peer_id) 的最左前缀覆盖，无需额外索引
