package com.example.converter;

import com.example.dto.response.UserBehaviorResponse;
import com.example.entity.UserBehavior;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/** 用户行为实体 → UserBehaviorResponse（接口出参），跨服务复用。 */
@Mapper(componentModel = "spring")
public interface UserBehaviorConverter {

    UserBehaviorConverter INSTANCE = Mappers.getMapper(UserBehaviorConverter.class);

    UserBehaviorResponse toResponse(UserBehavior behavior);
}
