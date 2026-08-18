package com.example.task.converter;

import com.example.dto.UserDTO;
import com.example.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/** task 用户实体 → 共享 UserDTO（缓存契约）。 */
@Mapper(componentModel = "spring")
public interface UserConverter {

    UserConverter INSTANCE = Mappers.getMapper(UserConverter.class);

    UserDTO toDTO(User user);
}
