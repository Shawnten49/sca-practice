package com.example.user.sharding.dto.request;

/** 创建用户行为请求体。 */
public record UserBehaviorCreateRequest(Long userId, String action, String description) {
}
