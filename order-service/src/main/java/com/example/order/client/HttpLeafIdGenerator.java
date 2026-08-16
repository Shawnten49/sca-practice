package com.example.order.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** HTTP 模式实现：调用独立 Leaf 服务（默认）。 */
@Component
@ConditionalOnProperty(name = "leaf.mode", havingValue = "http", matchIfMissing = true)
public class HttpLeafIdGenerator implements LeafIdGenerator {

    private final LeafIdClient leafIdClient;

    public HttpLeafIdGenerator(LeafIdClient leafIdClient) {
        this.leafIdClient = leafIdClient;
    }

    @Override
    public long segmentId(String key) {
        return leafIdClient.segmentId(key);
    }

    @Override
    public long snowflakeId(String key) {
        return leafIdClient.snowflakeId(key);
    }
}
