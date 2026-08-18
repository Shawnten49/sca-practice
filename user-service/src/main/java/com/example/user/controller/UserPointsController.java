package com.example.user.controller;

import com.example.common.Result;
import com.example.user.dto.response.PointsResponse;
import com.example.user.service.UserPointsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户积分查询/调整接口。参数校验下沉到 service，controller 保持薄。 */
@Tag(name = "用户积分", description = "积分查询与调整")
@RestController
@RequestMapping("/user/points")
public class UserPointsController {

    private final UserPointsService userPointsService;

    public UserPointsController(UserPointsService userPointsService) {
        this.userPointsService = userPointsService;
    }

    @GetMapping
    @Operation(summary = "查询用户积分")
    public Result<PointsResponse> points(@RequestParam Long userId) {
        return Result.ok(userPointsService.getPoints(userId));
    }

    @PostMapping("/update")
    @Operation(summary = "调整用户积分（可为负）")
    public Result<PointsResponse> update(@RequestParam Long userId, @RequestParam Integer delta) {
        return Result.ok(userPointsService.updatePoints(userId, delta));
    }
}
