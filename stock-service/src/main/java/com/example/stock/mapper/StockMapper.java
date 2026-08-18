package com.example.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.stock.entity.Stock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

/**
 * 库存 Mapper（MyBatis-Plus）。
 * 核心 SQL 人工维护在 resources/mapper/StockMapper.xml 中；
 * 自定义方法与 BaseMapper 内置方法刻意不同名，避免与自动生成的 CRUD 冲突。
 */
@Mapper
public interface StockMapper extends BaseMapper<Stock> {

    /**
     * 原子条件扣减：quantity = quantity - #{count} 且要求扣减前 quantity &gt;= #{count}。
     * 返回受影响行数：1 成功，0 库存不足（防超卖）。
     */
    int deductStock(@Param("productId") Long productId, @Param("count") Integer count);

    /** 按商品号查询库存，返回 Optional，SQL 见 StockMapper.xml */
    Optional<Stock> selectStockByProductId(Long productId);
}
