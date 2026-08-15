package com.example.user.controller;

import com.example.common.Result;
import com.example.user.dto.PointsVO;
import com.example.user.service.UserPointsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户积分查询/更新接口（沿用项目简单风格）。 */
@RestController
public class UserPointsController {

    private final UserPointsService userPointsService;

    public UserPointsController(UserPointsService userPointsService) {
        this.userPointsService = userPointsService;
    }

    @GetMapping("/user/points")
    public Result<PointsVO> points(@RequestParam Long userId) {
        validateUserId(userId);
        return Result.ok(userPointsService.getPoints(userId));
    }

    @PostMapping("/user/points/update")
    public Result<PointsVO> update(@RequestParam Long userId, @RequestParam Integer delta) {
        validateUserId(userId);
        return Result.ok(userPointsService.updatePoints(userId, delta));
    }

    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId 必须是正整数");
        }
    }
}
