-- 通知/站内信表（kb_notification）：与后端 Notification 实体对应
-- 若 Hibernate ddl-auto:update 未自动建表（如后端未重新编译部署），手动执行本脚本：
--   mysql -u kb -p kb < db/init_notification.sql
CREATE TABLE IF NOT EXISTS kb_notification (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  user_id     BIGINT,
  `type`      VARCHAR(255),
  actor       VARCHAR(255),
  doc_id      BIGINT,
  comment_id  BIGINT,
  summary     TEXT,
  `read`      TINYINT(1)   NOT NULL DEFAULT 0,
  created_at  DATETIME,
  PRIMARY KEY (id),
  KEY idx_notification_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
