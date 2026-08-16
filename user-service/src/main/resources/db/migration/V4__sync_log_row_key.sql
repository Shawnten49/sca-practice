-- 幂等键升级：binlog 位点 (log_file_name, log_file_offset) 对同一条多行 SQL 是共享的，
-- 增加行级 row_key（主键值拼接；无主键表为消息内行号）后，唯一键才能精确到"行"。
ALTER TABLE sync_log
    ADD COLUMN row_key VARCHAR(128) NOT NULL DEFAULT '' AFTER log_file_offset,
    DROP INDEX uk_sync_log_position,
    ADD UNIQUE KEY uk_sync_log_position (log_file_name, log_file_offset, row_key);
