-- v116：移除「知识空间(kb_space)」概念（与共享文档的「协作空间」在顶层重合，改为仅用文档树组织）
-- 说明：v114 曾引入 kb_space 表 + kb_document.space_id；本脚本回退这两部分，
--       但保留 kb_document.parent_id / sort_order（文档树层级仍在，文章改用分类树 + parentId 嵌套组织）。

-- 1) 删除知识空间表（若存在）
DROP TABLE IF EXISTS kb_space;

-- 2) 删除 kb_document 上的 space_id 列（若存在）
-- 使用存储过程兼容不同 MySQL 版本：仅当列存在时才 DROP，避免直接 DROP COLUMN 在无列时报错。
SET @db = DATABASE();
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'kb_document' AND column_name = 'space_id'
);
SET @sql = IF(@col_exists > 0,
    'ALTER TABLE kb_document DROP COLUMN space_id',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
