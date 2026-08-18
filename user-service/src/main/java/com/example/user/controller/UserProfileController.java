package com.example.user.controller;

import com.example.common.Result;
import com.example.user.dto.request.UserProfileSaveRequest;
import com.example.user.dto.response.UserProfileResponse;
import com.example.user.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户画像接口：MongoDB 文档存储，tags/extra 自由扩展，无需改表结构。 */
@Tag(name = "用户画像", description = "用户画像保存与查询（MongoDB）")
@RestController
@RequestMapping("/user/profile")
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @PostMapping("/save")
    @Operation(summary = "保存用户画像（按 userId upsert 全量覆盖）")
    public Result<UserProfileResponse> save(@RequestBody UserProfileSaveRequest request) {
        return Result.ok(userProfileService.save(request));
    }

    @GetMapping
    @Operation(summary = "查询用户画像（不存在 data 为 null）")
    public Result<UserProfileResponse> query(@RequestParam Long userId) {
        return Result.ok(userProfileService.query(userId));
    }
}
