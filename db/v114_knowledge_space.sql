-- ============================================================
-- 知识库 v114 迁移：知识空间 + 文档层级（kb_space + 文档树）
--
-- 【列名必须写 snake_case —— 本项目最关键的一个坑】
--   项目未配置 hibernate 命名策略，使用 Spring Boot 默认的
--   CamelCaseToUnderscoresNamingStrategy，实体字段会自动转下划线：
--     spaceId   -> space_id
--     parentId  -> parent_id
--     sortOrder -> sort_order
--   证据：DocumentRepository 的 nativeQuery 用的就是 deleted_at / owner_id / share_id。
--   注意：旧版 v113 中写的 createdAt / sharedDocId 与 Hibernate 实际生成的不一致，
--   属历史遗留（那两张表实际由 ddl-auto 创建），不要照抄。
--
-- 【生产必读】application-prod.yml 的 ddl-auto 默认 validate（只校验不改表），
--   不执行本脚本直接换包启动会报 Schema-validation 失败、服务起不来。
--   开发环境（application.yml）是 ddl-auto: update，启动会自动建表建列，无需执行。
-- ============================================================

-- ---------- 0) 执行前预检：确认列/表是否已存在 ----------
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
--   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_document'
--     AND COLUMN_NAME IN ('space_id','parent_id','sort_order');
-- SELECT TABLE_NAME FROM information_schema.TABLES
--   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_space';
-- 若上面查到 space_id/parent_id/sort_order 已存在，请跳过第 2 步（MySQL 的
-- ADD COLUMN 不支持 IF NOT EXISTS，重复执行会报错）。

-- ---------- 1) 知识空间表 ----------
-- 空间是第一层容器（按部门 / 项目 / 产品线），空间内文档可无限层级嵌套。
CREATE TABLE IF NOT EXISTS kb_space (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    name        VARCHAR(255)  NOT NULL,
    description VARCHAR(1000) DEFAULT NULL,
    owner_id    BIGINT        DEFAULT NULL,
    sort_order  INT           NOT NULL DEFAULT 0,
    created_at  DATETIME      DEFAULT NULL,
    updated_at  DATETIME      DEFAULT NULL,
    PRIMARY KEY (id),
    INDEX idx_space_sort (sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- 2) 文档层级字段 ----------
-- space_id   ：所属知识空间（NULL = 未归类）
-- parent_id  ：父文档 id（NULL = 该空间下的根级文档）
-- sort_order ：同级内排序（小的在前）
ALTER TABLE kb_document
    ADD COLUMN space_id   BIGINT DEFAULT NULL,
    ADD COLUMN parent_id  BIGINT DEFAULT NULL,
    ADD COLUMN sort_order INT    NOT NULL DEFAULT 0,
    ADD INDEX idx_doc_space (space_id, parent_id, sort_order);

-- ---------- 3) 默认空间（幂等）+ 存量文档回填 ----------
-- 保证 v114 之前的文档不会变成"无归属孤儿"，全部落到一个默认空间里。
INSERT INTO kb_space (name, description, owner_id, sort_order, created_at, updated_at)
SELECT * FROM (
    SELECT '默认知识库',
           '系统迁移自动创建：承载 v114 之前已存在的文档',
           NULL, 0, NOW(), NOW()
) AS tmp
WHERE NOT EXISTS (
    SELECT 1 FROM (SELECT id FROM kb_space WHERE name = '默认知识库') AS x
);

UPDATE kb_document d
SET d.space_id = (
    SELECT s.id FROM kb_space s WHERE s.name = '默认知识库' ORDER BY s.id LIMIT 1
)
WHERE d.space_id IS NULL;

-- ---------- 迁移后校验 ----------
-- SELECT s.name, COUNT(d.id) FROM kb_space s LEFT JOIN kb_document d ON d.space_id = s.id GROUP BY s.id;
-- 期望：默认知识库 的文档数 = 迁移前 kb_document 中未软删除 + 回收站内的全部文档数。
