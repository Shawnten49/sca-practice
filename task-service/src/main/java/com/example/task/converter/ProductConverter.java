package com.example.task.converter;

import com.example.dto.ProductDTO;
import com.example.entity.Product;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/** task 商品实体 → 共享 ProductDTO（缓存契约）。 */
@Mapper(componentModel = "spring")
public interface ProductConverter {

    ProductConverter INSTANCE = Mappers.getMapper(ProductConverter.class);

    ProductDTO toDTO(Product product);
}
