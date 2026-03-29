-- =========================================================
-- H2 -> MySQL 迁移：库与账号初始化
-- 说明：
-- 1) 本地联调场景：engine 与 web 账号互相可读写对方库
-- 2) 当前口径：engine_app 使用空密码（仅限本地联调）
-- 3) 若 web 账号已存在，按“现有 web 账号授权”段落执行
-- =========================================================

CREATE DATABASE IF NOT EXISTS mr_engine
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;

-- 引擎运行账号（本地联调：对 mr_engine / ry-vue 互通可读写）
CREATE USER IF NOT EXISTS 'engine_app'@'%' IDENTIFIED BY '';
ALTER USER 'engine_app'@'%' IDENTIFIED BY '';
GRANT ALL PRIVILEGES ON mr_engine.* TO 'engine_app'@'%';

-- 现有 web 账号授权（当前按本地配置使用 root）
GRANT ALL PRIVILEGES ON `ry-vue`.* TO 'root'@'%';
GRANT ALL PRIVILEGES ON mr_engine.* TO 'root'@'%';

-- 引擎账号增加对 web 库全权限（本地联调）
GRANT ALL PRIVILEGES ON `ry-vue`.* TO 'engine_app'@'%';

FLUSH PRIVILEGES;
