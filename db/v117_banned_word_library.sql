-- v117 违禁词库：新增「词库」表 + 词条归属词库 + 真实分页支持
-- 生产环境（ddl-auto=validate）必须先执行本文件再启动，否则实体与表不一致会启动失败。
-- 开发环境（ddl-auto=update）可自动建表，但建议仍显式执行以保证列定义一致。

CREATE TABLE IF NOT EXISTS kb_banned_word_library (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    enabled     TINYINT(1)   NOT NULL DEFAULT 1,
    builtin     TINYINT(1)   NOT NULL DEFAULT 0,
    priority    INT          NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_lib_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 词条表：用 library_id 取代原自由文本 category，并加索引便于按词库分页查询
ALTER TABLE kb_banned_word ADD COLUMN library_id BIGINT DEFAULT NULL;
ALTER TABLE kb_banned_word ADD INDEX idx_bw_lib (library_id);

-- 兼容历史数据：把已有词条（含 v115 自动 seed 的「初始词库」）归入一个默认词库
INSERT INTO kb_banned_word_library (name, description, enabled, builtin, priority)
SELECT '默认词库', '系统默认词库（由历史违禁词自动归集）', 1, 1, 0
WHERE NOT EXISTS (SELECT 1 FROM kb_banned_word_library);

UPDATE kb_banned_word
SET library_id = (SELECT id FROM kb_banned_word_library WHERE name = '默认词库' LIMIT 1)
WHERE library_id IS NULL;

-- 移除旧的自由文本分类列（已被「词库」概念取代）
ALTER TABLE kb_banned_word DROP COLUMN category;

-- 说明：
-- 1. 词库（kb_banned_word_library）用于把违禁词按业务维度分组，并支持「启停加载」：
--    仅 enabled=1 的词库会进入实时过滤缓存，停用即对该词库下线，无需删词。
-- 2. 管理页增删词条 / 启停词库后，SensitiveWordFilter 热刷新缓存，评论拦截与审核检查即时生效。
-- 3. 词条分页改为服务端分页（按 library_id 查询），大词库也不会一次性拉全表。
