package com.example.user.controller;

import com.example.common.Result;
import com.example.user.dto.request.UserCreateRequest;
import com.example.dto.response.UserResponse;
import com.example.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户信息查询/保存接口。参数校验下沉到 service，controller 保持薄。 */
@Tag(name = "用户", description = "用户信息查询与保存")
@RestController
public class UserController {

    @Value("${server.port}")
    private String port;

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/info")
    public String info() {
        return "user-service running on port " + port;
    }

    /** 查询用户全部信息（idCard 为脱敏碎片）；Sentinel 降级时返回失败包装 */
    @GetMapping("/user")
    @Operation(summary = "查询用户全部信息（idCard 为脱敏碎片）")
    public Result<UserResponse> user(@RequestParam String id) {
        UserResponse user = userService.getUserInfo(id);
        return user == null ? Result.fail(500, "服务降级，请稍后再试")
                : Result.ok(user);
    }

    /** 保存用户：idCard 加密存储（ShardingSphere !ENCRYPT），返回脱敏碎片 */
    @PostMapping("/user/save")
    @Operation(summary = "保存用户（身份证加密存储）")
    public Result<UserResponse> save(@RequestBody UserCreateRequest request) {
        return Result.ok(userService.saveUser(request.nickname(), request.idCard()));
    }
}
