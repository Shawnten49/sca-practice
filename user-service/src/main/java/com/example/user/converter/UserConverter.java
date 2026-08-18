package com.example.user.converter;

import com.example.user.dto.response.UserResponse;
import com.example.user.entity.User;

/** User 实体 → UserResponse 转换。 */
public final class UserConverter {

    private UserConverter() {
    }

    public static UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getNickname(), user.getPoints(),
                user.getCredits(), user.getIdCard(), user.getCreateTime());
    }
}
