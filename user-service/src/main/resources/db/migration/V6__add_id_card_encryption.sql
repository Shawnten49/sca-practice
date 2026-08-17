-- 身份证号加密列：物理列只存 AES 密文（Base64），逻辑列 id_card 由 ShardingSphere !ENCRYPT 规则映射。
-- 非空、默认空串：老数据由 MySQL 在 ALTER 时自动回填 ''，查询层永不返回 null。
ALTER TABLE users
    ADD COLUMN id_card_cipher VARCHAR(128) NOT NULL DEFAULT '' COMMENT '身份证号密文(AES)';
