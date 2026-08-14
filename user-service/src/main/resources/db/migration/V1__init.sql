-- user-service 业务表（Flyway 管理；表已存在时幂等跳过，不会重复执行）
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY,
    nickname VARCHAR(64) NOT NULL,
    points INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 积分流水表：order_id 唯一索引作为 DB 层幂等兜底（与 Redis SETNX 去重互补）
CREATE TABLE IF NOT EXISTS user_points (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    points INT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_points_order (order_id),
    KEY idx_user_points_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 演示种子数据（INSERT IGNORE：已存在则跳过）
INSERT IGNORE INTO users (id, nickname, points) VALUES
    (1, 'demo', 100),
    (2, 'alice', 0);
