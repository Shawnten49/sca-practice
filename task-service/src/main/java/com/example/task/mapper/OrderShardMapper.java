package com.example.task.mapper;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.example.dto.OrderDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 订单分片表读取（seata_order，orders_0 ~ orders_3）。 */
@DS("order")
@Mapper
public interface OrderShardMapper {

    /**
     * 按物理分片表游标分页读取最近订单。
     *
     * @param table 物理表名（来自配置白名单，仅用于 ${} 拼接）
     */
    List<OrderDTO> selectRecent(@Param("table") String table,
                                @Param("cutoff") LocalDateTime cutoff,
                                @Param("lastId") Long lastId,
                                @Param("batchSize") int batchSize);
}
