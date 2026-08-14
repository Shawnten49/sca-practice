package com.example.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.order.domain.Order;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

/**
 * 订单 Mapper（MyBatis-Plus）。
 * 核心 SQL 人工维护在 resources/mapper/OrderMapper.xml 中；
 * 自定义方法与 BaseMapper 内置方法刻意不同名（insertOrder / selectOrderById），
 * 避免自定义 SQL 与 MyBatis-Plus 自动生成的 CRUD 语句冲突。
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /** 插入订单：id 由调用方预生成（IdWorker），SQL 见 OrderMapper.xml */
    int insertOrder(Order order);

    /** 按 id 查询订单（事务消息回查等场景），返回 Optional，SQL 见 OrderMapper.xml */
    Optional<Order> selectOrderById(Long id);
}
