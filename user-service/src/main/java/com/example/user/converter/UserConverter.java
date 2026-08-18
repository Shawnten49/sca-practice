package com.example.user.converter;

import com.example.user.dto.response.UserResponse;
import com.example.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/** User 实体 → UserResponse（MapStruct）。 */
@Mapper(componentModel = "spring")
public interface UserConverter {

    UserConverter INSTANCE = Mappers.getMapper(UserConverter.class);

    UserResponse toResponse(User user);
}
