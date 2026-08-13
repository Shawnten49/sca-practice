-- ============================================================
-- init-seata.sql · 第 7 课 Seata 业务库初始化脚本
--
-- 作用：
--   1) seata_order 库：orders 业务表 + undo_log（AT 模式回滚表）
--   2) seata_stock 库：stock 业务表 + undo_log（AT 模式回滚表）
--   3) 初始化一条库存数据（product_id=1, quantity=100）
--
-- 执行方式：
--   mysql -u root < sql/init-seata.sql
--
-- 注意：undo_log 是 AT 模式回滚的命根子，业务库必须各有一张；
--       唯一键 ux_undo_log(xid, branch_id) 用于幂等，不能删。
-- ============================================================

CREATE DATABASE IF NOT EXISTS seata_order DEFAULT CHARACTER SET utf8mb4;
USE seata_order;

CREATE TABLE IF NOT EXISTS orders (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  count INT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS undo_log (
  branch_id BIGINT NOT NULL,
  xid VARCHAR(128) NOT NULL,
  context VARCHAR(128) NOT NULL,
  rollback_info LONGBLOB NOT NULL,
  log_status INT NOT NULL,
  log_created DATETIME(6) NOT NULL,
  log_modified DATETIME(6) NOT NULL,
  UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE DATABASE IF NOT EXISTS seata_stock DEFAULT CHARACTER SET utf8mb4;
USE seata_stock;

CREATE TABLE IF NOT EXISTS stock (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_id BIGINT UNIQUE NOT NULL,
  quantity INT NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS undo_log (
  branch_id BIGINT NOT NULL,
  xid VARCHAR(128) NOT NULL,
  context VARCHAR(128) NOT NULL,
  rollback_info LONGBLOB NOT NULL,
  log_status INT NOT NULL,
  log_created DATETIME(6) NOT NULL,
  log_modified DATETIME(6) NOT NULL,
  UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO stock (product_id, quantity) VALUES (1, 100);
