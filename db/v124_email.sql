-- v124：邮箱绑定与邮件功能所需字段
-- 新增：email_verified（邮箱是否已通过验证码验证/绑定）、email_notify_enabled（是否接收通知邮件）
-- 存量用户默认 false（未绑定 / 不接收），不影响既有逻辑。

ALTER TABLE kb_user ADD COLUMN IF NOT EXISTS email_verified boolean NOT NULL DEFAULT false;
ALTER TABLE kb_user ADD COLUMN IF NOT EXISTS email_notify_enabled boolean NOT NULL DEFAULT false;
