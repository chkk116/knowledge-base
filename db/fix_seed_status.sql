-- ============================================================
-- 知识库 · 历史遗留帖子状态兜底修复
-- ============================================================
-- 场景：示例帖 / 历史遗留帖（不是通过「写文章 → 提交审核」产生的）被测试或异常操作
--       改成了 DRAFT / PENDING_REVIEW / BLOCKED，导致从「仅展示 PUBLISHED」的首页消失或 404。
--
-- 用法（在装了 MySQL 的机器上，用 root 或 kb 账号执行）：
--   mysql -u kb -pkb kb < db/fix_seed_status.sql
--   输入密码 kb 即可（或 mysql -u root -p kb < db/fix_seed_status.sql）。
--
-- ⚠️ 注意：下面这条 UPDATE 会把「所有」非已发布帖子恢复为 PUBLISHED，
--   包括你主动设为草稿 / 待审核 / 封禁的帖子。
--   若只想恢复指定的几篇，请把 WHERE 改成精确 id，例如：
--     UPDATE kb_document SET status='PUBLISHED', updated_at=NOW() WHERE id IN (1,2,3,4,5);
--
-- 注：Hibernate 实际表名是 kb_document（实体 @Table(name="kb_document")），不是 document。
-- ============================================================

UPDATE kb_document
SET status     = 'PUBLISHED',
    updated_at = NOW()
WHERE status <> 'PUBLISHED';
