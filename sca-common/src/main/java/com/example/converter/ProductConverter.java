package com.example.converter;

import com.example.dto.ProductDTO;
import com.example.dto.response.ProductResponse;
import com.example.entity.Product;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/** 商品实体 → 共享 ProductDTO（缓存契约）/ ProductResponse（接口出参），跨服务复用。 */
@Mapper(componentModel = "spring")
public interface ProductConverter {

    ProductConverter INSTANCE = Mappers.getMapper(ProductConverter.class);

    ProductDTO toDTO(Product product);

    ProductResponse toResponse(Product product);
}
