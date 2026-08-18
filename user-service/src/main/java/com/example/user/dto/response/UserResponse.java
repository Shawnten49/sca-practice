package com.example.user.dto.response;

import java.time.LocalDateTime;

/** 用户接口出参（idCard 为脱敏碎片，非密文）。 */
public record UserResponse(Long id, String nickname, Integer points, Integer credits,
                           String idCard, LocalDateTime createTime) {
}
