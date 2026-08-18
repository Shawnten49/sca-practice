package com.example.stock.converter;

import com.example.stock.dto.response.ProductResponse;
import com.example.stock.entity.Product;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/** Product 实体 → ProductResponse（MapStruct）。 */
@Mapper(componentModel = "spring")
public interface ProductConverter {

    ProductConverter INSTANCE = Mappers.getMapper(ProductConverter.class);

    ProductResponse toResponse(Product product);
}
