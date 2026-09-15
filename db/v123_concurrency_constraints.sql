-- v123：并发最终约束 + 协作状态代次（对应审查报告 F05/F08/F09）
-- 背景：应用层的「先查再写」只能挡住顺序请求，并发下仍可能突破业务不变量。
--       这三条约束是「最后一道闸门」，重复数据在数据库层直接失败，应用侧再配合行锁/原子抢占。
-- 幂等：全部使用 IF NOT EXISTS / ADD COLUMN IF NOT EXISTS，可重复执行。
-- 适用：PostgreSQL（当前生产库）。MySQL 需改写为生成列 + 唯一索引。

-- F08：同一用户同一试卷最多只能有一条「进行中」（未提交）考试记录
-- 并发开考时第二条会违反约束，服务端回查已有记录返回，保证开考幂等
CREATE UNIQUE INDEX IF NOT EXISTS uq_exam_active_user_paper
    ON kb_exam_record (user_id, paper_id)
    WHERE submitted_at IS NULL;

-- F09：一件资产同一时刻最多只能有一条活跃（BORROWED）借用记录
-- 配合 AssetService.borrow() 的资产行锁（SELECT ... FOR UPDATE）
CREATE UNIQUE INDEX IF NOT EXISTS uq_assetborrow_active_asset
    ON kb_asset_borrow (asset_id)
    WHERE status = 'BORROWED';

-- F05：协作状态代次。Java 侧回滚/重置协同态时自增该列，
-- 中继（collab-relay）落库前比对代次，不一致即丢弃过期内存快照并强制客户端重载，
-- 避免「页面提示已恢复，旧房间又把历史内容覆盖回去」
ALTER TABLE kb_shared_document
    ADD COLUMN IF NOT EXISTS yjs_rev bigint NOT NULL DEFAULT 0;

-- 存量行补齐（ADD COLUMN 带默认值已自动回填，这里兜底 NULL）
UPDATE kb_shared_document SET yjs_rev = 0 WHERE yjs_rev IS NULL;
