-- order-service 分表（第 16 课）：orders 逻辑表 → orders_0 ~ orders_3（按 id % 4 路由）
-- 本脚本由 Flyway 直连物理库执行（spring.flyway.url），ShardingSphere 负责逻辑表路由改写。
-- 分片表不再使用 AUTO_INCREMENT：订单 id 由应用侧雪花/Leaf 预生成后显式插入。

CREATE TABLE IF NOT EXISTS orders_0 (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  count INT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_orders_user_id (user_id),
  KEY idx_orders_product_id (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS orders_1 LIKE orders_0;
CREATE TABLE IF NOT EXISTS orders_2 LIKE orders_0;
CREATE TABLE IF NOT EXISTS orders_3 LIKE orders_0;

-- 旧单表数据按 id % 4 归位（开发环境数据可丢弃；无数据时为空操作，幂等）
INSERT INTO orders_0 (id, user_id, product_id, count, create_time)
SELECT id, user_id, product_id, count, create_time FROM orders WHERE id % 4 = 0;
INSERT INTO orders_1 (id, user_id, product_id, count, create_time)
SELECT id, user_id, product_id, count, create_time FROM orders WHERE id % 4 = 1;
INSERT INTO orders_2 (id, user_id, product_id, count, create_time)
SELECT id, user_id, product_id, count, create_time FROM orders WHERE id % 4 = 2;
INSERT INTO orders_3 (id, user_id, product_id, count, create_time)
SELECT id, user_id, product_id, count, create_time FROM orders WHERE id % 4 = 3;

-- 迁移完成后删除旧单表（方案已确认：开发环境不需要备份）
DROP TABLE IF EXISTS orders;
