package com.example.user.controller;

import com.example.common.Result;
import com.example.user.dto.response.CreditsResponse;
import com.example.user.service.UserCreditsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户信用点查询/调整接口。参数校验下沉到 service，controller 保持薄。 */
@Tag(name = "用户信用点", description = "信用点查询与调整")
@RestController
@RequestMapping("/user/credits")
public class UserCreditsController {

    private final UserCreditsService userCreditsService;

    public UserCreditsController(UserCreditsService userCreditsService) {
        this.userCreditsService = userCreditsService;
    }

    @GetMapping
    @Operation(summary = "查询用户信用点")
    public Result<CreditsResponse> credits(@RequestParam Long userId) {
        return Result.ok(userCreditsService.getCredits(userId));
    }

    @PostMapping("/update")
    @Operation(summary = "调整用户信用点（可为负）")
    public Result<CreditsResponse> update(@RequestParam Long userId, @RequestParam Integer delta) {
        return Result.ok(userCreditsService.updateCredits(userId, delta));
    }
}
