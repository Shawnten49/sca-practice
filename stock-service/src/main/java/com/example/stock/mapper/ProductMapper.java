package com.example.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.stock.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 商品 Mapper（MyBatis-Plus）。
 * 核心 SQL 人工维护在 resources/mapper/ProductMapper.xml 中；
 * 自定义方法与 BaseMapper 内置方法刻意不同名，避免与自动生成的 CRUD 冲突。
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /** 保存商品（显式列插入，id 由调用方预生成），SQL 见 ProductMapper.xml */
    int insertProduct(Product product);

    /** 按 id 查询商品，返回 Optional，SQL 见 ProductMapper.xml */
    Optional<Product> selectProductById(Long id);

    /** 按名称模糊查询（ES 降级兜底路径），SQL 见 ProductMapper.xml */
    List<Product> selectByNameLike(@Param("name") String name);
}
