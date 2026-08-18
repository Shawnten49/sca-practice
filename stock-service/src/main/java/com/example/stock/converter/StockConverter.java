package com.example.stock.converter;

import com.example.stock.dto.response.StockResponse;
import com.example.stock.entity.Stock;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/** Stock 实体 → StockResponse（MapStruct）。 */
@Mapper(componentModel = "spring")
public interface StockConverter {

    StockConverter INSTANCE = Mappers.getMapper(StockConverter.class);

    StockResponse toResponse(Stock stock);
}
