-- v120 资产逾期提醒（PostgreSQL）
-- 生产 KB_DDL_AUTO=validate：必须先执行本文件，再启动新后端。
-- overdue_notified_at：同一笔借用每天最多提醒一次，避免每天重复轰炸。

ALTER TABLE kb_asset_borrow ADD COLUMN IF NOT EXISTS overdue_notified_at DATE;
