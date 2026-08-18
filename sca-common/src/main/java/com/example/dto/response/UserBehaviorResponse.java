package com.example.dto.response;

import java.time.LocalDateTime;

/** 用户行为接口出参（跨服务共享）。 */
public record UserBehaviorResponse(Long id, Long userId, String action,
                                   String description, LocalDateTime createTime) {
}
