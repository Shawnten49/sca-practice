package com.example.user.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 用户画像出参。 */
public record UserProfileResponse(Long userId, String nickname, List<String> tags,
                                  Map<String, Object> extra,
                                  LocalDateTime createTime, LocalDateTime updateTime) {
}
