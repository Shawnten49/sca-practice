package com.example.converter;

import com.example.dto.UserDTO;
import com.example.dto.response.UserResponse;
import com.example.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/** 用户实体 → 共享 UserDTO（缓存契约）/ UserResponse（接口出参），跨服务复用。 */
@Mapper(componentModel = "spring")
public interface UserConverter {

    UserConverter INSTANCE = Mappers.getMapper(UserConverter.class);

    UserDTO toDTO(User user);

    UserResponse toResponse(User user);
}
