package com.example.user.sharding.converter;

import com.example.user.sharding.dto.response.UserBehaviorResponse;
import com.example.user.sharding.entity.UserBehavior;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/** UserBehavior 实体 → UserBehaviorResponse（MapStruct）。 */
@Mapper(componentModel = "spring")
public interface UserBehaviorConverter {

    UserBehaviorConverter INSTANCE = Mappers.getMapper(UserBehaviorConverter.class);

    UserBehaviorResponse toResponse(UserBehavior behavior);
}
