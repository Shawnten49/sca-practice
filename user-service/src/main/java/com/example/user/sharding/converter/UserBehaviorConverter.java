package com.example.user.sharding.converter;

import com.example.user.sharding.dto.response.UserBehaviorResponse;
import com.example.user.sharding.entity.UserBehavior;

/** UserBehavior 实体 → UserBehaviorResponse 转换。 */
public final class UserBehaviorConverter {

    private UserBehaviorConverter() {
    }

    public static UserBehaviorResponse toResponse(UserBehavior behavior) {
        return new UserBehaviorResponse(behavior.getId(), behavior.getUserId(),
                behavior.getAction(), behavior.getDescription(), behavior.getCreateTime());
    }
}
