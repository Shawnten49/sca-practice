package com.example.task.mapper;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.example.task.model.ProductRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 商品表读取（seata_stock，按分片 + 游标分页 + 时间范围）。 */
@DS("stock")
@Mapper
public interface ProductMapper {

    List<ProductRow> selectRecentByShard(@Param("cutoff") LocalDateTime cutoff,
                                         @Param("lastId") Long lastId,
                                         @Param("shardIndex") int shardIndex,
                                         @Param("shardTotal") int shardTotal,
                                         @Param("batchSize") int batchSize);
}
