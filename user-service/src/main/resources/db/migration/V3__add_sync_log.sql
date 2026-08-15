-- Canal 消费幂等去重表：以 binlog 位点 (log_file_name, log_file_offset) 为唯一键
CREATE TABLE IF NOT EXISTS sync_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    log_file_name  VARCHAR(64)  NOT NULL,
    log_file_offset BIGINT      NOT NULL,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sync_log_position (log_file_name, log_file_offset)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
