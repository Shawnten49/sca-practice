-- 用户信用点字段：默认 0，不能为负（MySQL 8.0.16+ 强制执行 CHECK）
ALTER TABLE users
    ADD COLUMN credits INT NOT NULL DEFAULT 0 CHECK (credits >= 0);
