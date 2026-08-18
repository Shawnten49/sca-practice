package com.example.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.user.entity.UserPoints;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

/**
 * 积分流水 Mapper（MyBatis-Plus）。
 * 核心 SQL 人工维护在 resources/mapper/UserPointsMapper.xml 中。
 */
@Mapper
public interface UserPointsMapper extends BaseMapper<UserPoints> {

    /** 插入积分流水：id 由调用方预生成（IdWorker），SQL 见 UserPointsMapper.xml */
    int insertUserPoints(UserPoints record);

    /** 按订单号查询流水（幂等回查用），返回 Optional，SQL 见 UserPointsMapper.xml */
    Optional<UserPoints> selectByOrderId(Long orderId);
}
