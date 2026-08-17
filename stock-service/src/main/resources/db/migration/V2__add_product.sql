-- 商品表：数据权威在 MySQL，Canal 捕获 binlog 后同步到 ES（查询副本）
CREATE TABLE IF NOT EXISTS product (
    id          BIGINT PRIMARY KEY,              -- 雪花 ID，服务端生成
    name        VARCHAR(128) NOT NULL,           -- 商品名称（搜索主字段）
    brand       VARCHAR(64)  NOT NULL,           -- 品牌
    price       DECIMAL(10,2) NOT NULL,          -- 价格（保留两位小数）
    description VARCHAR(512) NOT NULL DEFAULT '',-- 描述
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_product_name (name)                  -- MySQL 兜底查询/按名精确查
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
