-- v122 多模态模型（文生图 / 文生视频）
-- AiModel 新增 kind：CHAT（对话）| IMAGE（文生图）| VIDEO（文生视频）。
-- 生产 KB_DDL_AUTO=validate：必须先执行本文件，再启动新后端。

ALTER TABLE kb_ai_model ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'CHAT';
