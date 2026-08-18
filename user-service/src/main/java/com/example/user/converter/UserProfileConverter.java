package com.example.user.converter;

import com.example.user.dto.request.UserProfileSaveRequest;
import com.example.user.dto.response.UserProfileResponse;
import com.example.user.entity.UserProfile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/** 用户画像：SaveRequest → 实体 / 实体 → Response（MapStruct）。 */
@Mapper(componentModel = "spring")
public interface UserProfileConverter {

    UserProfileConverter INSTANCE = Mappers.getMapper(UserProfileConverter.class);

    /** 时间戳由 Service 统一设置，不入参映射。 */
    @Mapping(target = "createTime", ignore = true)
    @Mapping(target = "updateTime", ignore = true)
    UserProfile toEntity(UserProfileSaveRequest request);

    UserProfileResponse toResponse(UserProfile profile);
}
