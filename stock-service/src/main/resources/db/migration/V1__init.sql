-- stock-service 业务表（Flyway 管理）
CREATE TABLE IF NOT EXISTS stock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT UNIQUE NOT NULL,
    quantity INT NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Seata AT 模式回滚表（业务库必须存在，唯一键用于幂等）

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

-- 初始库存（幂等：已存在则不重复插入）
INSERT INTO stock (product_id, quantity)
SELECT 1, 100 WHERE NOT EXISTS (SELECT 1 FROM stock WHERE product_id = 1);
