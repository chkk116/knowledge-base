-- v115 违禁词管理：新增 kb_banned_word 表
-- 生产环境（ddl-auto=validate）必须先执行本文件再启动，否则启动会因实体与表不一致而失败。
-- 开发环境（ddl-auto=update）可自动建表，但建议仍显式执行以保证列定义一致。

CREATE TABLE IF NOT EXISTS kb_banned_word (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    word       VARCHAR(255) NOT NULL,
    category   VARCHAR(64)  DEFAULT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_banned_word (word)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 说明：
-- 1. SensitiveWordFilter 启动时会从本表加载词库；若表为空且 classpath 下存在 sensitive-words.txt，
--    会自动 seed 一次（导入历史词），之后管理页的增删即为唯一真源。
-- 2. 评论发表仍受本表词库约束（命中即拒绝），与改动前行为一致，只是词源从静态文件变为可管理。
-- 3. 文档提交/更新/导入路径已不再自动拦截（交由审核工具条「一键违禁词检查」按需处理）。
