package com.example.order.controller;

import com.example.common.Result;
import com.example.order.client.LeafIdClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Leaf 分布式 ID 获取接口（HTTP 调用本地 Leaf 服务 :8085）。 */
@RestController
public class LeafIDController {

    private final LeafIdClient leafIdClient;

    public LeafIDController(LeafIdClient leafIdClient) {
        this.leafIdClient = leafIdClient;
    }

    /** Leaf 号段模式取 ID（key 默认 order_id）。 */
    @GetMapping("/leaf/segment")
    public Result<Long> segment(@RequestParam(defaultValue = "order_id") String key) {
        return Result.ok(leafIdClient.segmentId(key));
    }

    /** Leaf 雪花模式取 ID（key 默认 leaf）。 */
    @GetMapping("/leaf/snowflake")
    public Result<Long> snowflake(@RequestParam(defaultValue = "leaf") String key) {
        return Result.ok(leafIdClient.snowflakeId(key));
    }
}
