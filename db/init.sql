-- ============================================================
-- 知识库 · 本地 MySQL 初始化脚本
-- 用法（在装了 MySQL 的机器上，用 root 执行）：
--   mysql -u root -p < db/init.sql
-- 然后输入 root 密码即可。脚本会：
--   1) 创建数据库 kb（utf8mb4）
--   2) 创建应用专用用户 kb（密码 kb，与 application.yml 一致）
--   3) 授权并刷新
-- 之后后端 application.yml 的 datasource 即可直接用 kb/kb 连接。
-- ============================================================

CREATE DATABASE IF NOT EXISTS kb
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- 应用账号（host 覆盖 localhost / 127.0.0.1 / %，避免 Windows 下 JDBC 把 localhost 当 TCP 时连不上）
CREATE USER IF NOT EXISTS 'kb'@'localhost' IDENTIFIED BY 'kb';
CREATE USER IF NOT EXISTS 'kb'@'127.0.0.1' IDENTIFIED BY 'kb';
CREATE USER IF NOT EXISTS 'kb'@'%'         IDENTIFIED BY 'kb';

ALTER USER 'kb'@'localhost'   IDENTIFIED BY 'kb';
ALTER USER 'kb'@'127.0.0.1'   IDENTIFIED BY 'kb';
ALTER USER 'kb'@'%'           IDENTIFIED BY 'kb';

GRANT ALL PRIVILEGES ON kb.* TO 'kb'@'localhost';
GRANT ALL PRIVILEGES ON kb.* TO 'kb'@'127.0.0.1';
GRANT ALL PRIVILEGES ON kb.* TO 'kb'@'%';

FLUSH PRIVILEGES;
