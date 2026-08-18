package com.example.dto.response;

/** 积分返回体（跨服务共享）。 */
public record PointsResponse(Long userId, Integer points) {
}
