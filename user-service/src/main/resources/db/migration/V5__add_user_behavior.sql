-- 用户行为分片表：逻辑表 user_behavior → 物理表 user_behavior_0 ~ user_behavior_3（分片键 user_id % 4）
-- 由 Flyway 直连默认库（seata_user）创建，ShardingSphere 只负责路由

CREATE TABLE IF NOT EXISTS user_behavior_0 (
    id          BIGINT       NOT NULL COMMENT '雪花ID（全局唯一，服务端生成）',
    user_id     BIGINT       NOT NULL COMMENT '用户ID（分片键）',
    action      VARCHAR(64)  NOT NULL COMMENT '行为类型，如 login / order / click',
    description VARCHAR(255) DEFAULT NULL COMMENT '行为描述',
    create_time DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '发生时间',
    PRIMARY KEY (id),
    KEY idx_user_create (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户行为分片表 0';

CREATE TABLE IF NOT EXISTS user_behavior_1 (
    id          BIGINT       NOT NULL COMMENT '雪花ID（全局唯一，服务端生成）',
    user_id     BIGINT       NOT NULL COMMENT '用户ID（分片键）',
    action      VARCHAR(64)  NOT NULL COMMENT '行为类型，如 login / order / click',
    description VARCHAR(255) DEFAULT NULL COMMENT '行为描述',
    create_time DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '发生时间',
    PRIMARY KEY (id),
    KEY idx_user_create (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户行为分片表 1';

CREATE TABLE IF NOT EXISTS user_behavior_2 (
    id          BIGINT       NOT NULL COMMENT '雪花ID（全局唯一，服务端生成）',
    user_id     BIGINT       NOT NULL COMMENT '用户ID（分片键）',
    action      VARCHAR(64)  NOT NULL COMMENT '行为类型，如 login / order / click',
    description VARCHAR(255) DEFAULT NULL COMMENT '行为描述',
    create_time DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '发生时间',
    PRIMARY KEY (id),
    KEY idx_user_create (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户行为分片表 2';

CREATE TABLE IF NOT EXISTS user_behavior_3 (
    id          BIGINT       NOT NULL COMMENT '雪花ID（全局唯一，服务端生成）',
    user_id     BIGINT       NOT NULL COMMENT '用户ID（分片键）',
    action      VARCHAR(64)  NOT NULL COMMENT '行为类型，如 login / order / click',
    description VARCHAR(255) DEFAULT NULL COMMENT '行为描述',
    create_time DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '发生时间',
    PRIMARY KEY (id),
    KEY idx_user_create (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户行为分片表 3';
