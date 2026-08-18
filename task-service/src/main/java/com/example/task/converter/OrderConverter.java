package com.example.task.converter;

import com.example.dto.OrderDTO;
import com.example.entity.Order;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/** task 订单实体 → 共享 OrderDTO（缓存契约）。 */
@Mapper(componentModel = "spring")
public interface OrderConverter {

    OrderConverter INSTANCE = Mappers.getMapper(OrderConverter.class);

    OrderDTO toDTO(Order order);
}
