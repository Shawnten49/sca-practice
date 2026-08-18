package com.example.converter;

import com.example.dto.response.StockResponse;
import com.example.entity.Stock;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/** 库存实体 → StockResponse（接口出参），跨服务复用。 */
@Mapper(componentModel = "spring")
public interface StockConverter {

    StockConverter INSTANCE = Mappers.getMapper(StockConverter.class);

    StockResponse toResponse(Stock stock);
}
