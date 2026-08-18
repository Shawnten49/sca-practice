package com.example.common;

/** Redis 缓存 key 前缀（task-service 写入 / 业务服务读取共用，避免字符串漂移）。 */
public final class CacheKeys {

    public static final String USER_PREFIX = "task:user:";
    public static final String ORDER_PREFIX = "task:order:";
    public static final String PRODUCT_PREFIX = "task:product:";

    private CacheKeys() {
    }
}
