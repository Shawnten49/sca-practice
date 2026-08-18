package com.example.user.dto.request;

import java.util.List;
import java.util.Map;

/** 保存用户画像请求体。 */
public record UserProfileSaveRequest(Long userId, String nickname,
                                     List<String> tags, Map<String, Object> extra) {
}
