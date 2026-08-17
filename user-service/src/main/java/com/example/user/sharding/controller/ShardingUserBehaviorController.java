package com.example.user.sharding.controller;

import com.example.common.Result;
import com.example.user.sharding.domain.UserBehavior;
import com.example.user.sharding.dto.UserBehaviorCreateRequest;
import com.example.user.sharding.service.ShardingUserBehaviorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户行为接口（Sharding 机制专属入口）。
 * 创建/查询均走 ShardingSphere-JDBC 分表，开发者从类名/包路径即可感知。
 */
@RestController
public class ShardingUserBehaviorController {

    private final ShardingUserBehaviorService shardingUserBehaviorService;

    public ShardingUserBehaviorController(ShardingUserBehaviorService shardingUserBehaviorService) {
        this.shardingUserBehaviorService = shardingUserBehaviorService;
    }

    @PostMapping("/user-behavior")
    public Result<UserBehavior> create(@RequestBody UserBehaviorCreateRequest request) {
        return Result.ok(shardingUserBehaviorService.create(
                request.userId(), request.action(), request.description()));
    }

    @GetMapping("/user-behavior")
    public Result<List<UserBehavior>> query(@RequestParam Long userId,
                                            @RequestParam(required = false) Integer limit) {
        return Result.ok(shardingUserBehaviorService.listByUserId(userId, limit));
    }
}
