package com.example.order.client;

/** Leaf 取号接口：HTTP（独立服务）与本地 SDK（leaf-core）两种实现，由 leaf.mode 切换。 */
public interface LeafIdGenerator {

    /** 号段模式取 ID（key 对应 leaf_alloc 的 biz_tag）。 */
    long segmentId(String key);

    /** 雪花模式取 ID。 */
    long snowflakeId(String key);
}
