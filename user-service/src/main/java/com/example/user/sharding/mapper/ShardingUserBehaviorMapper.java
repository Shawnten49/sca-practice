package com.example.user.sharding.mapper;

import com.example.user.sharding.domain.UserBehavior;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户行为 Mapper（Sharding 机制）。
 *
 * <p>只被 sharding 工厂（shardingSqlSessionFactory）加载，SQL 见
 * resources/sharding-mapper/ShardingUserBehaviorMapper.xml；
 * 不加 @Mapper，由 {@code @MapperScan("com.example.user.sharding.mapper")} 按包绑定到 sharding 工厂。
 */
public interface ShardingUserBehaviorMapper {

    /** 插入行为（分片键 user_id 路由到单张物理表）。 */
    int insertUserBehavior(UserBehavior record);

    /** 按 id 查询（广播到 4 张物理表合并），创建后回显 create_time 用。 */
    UserBehavior selectById(@Param("id") Long id);

    /** 按用户查询最近行为（命中单分片）。 */
    List<UserBehavior> selectByUserId(@Param("userId") Long userId, @Param("limit") Integer limit);
}
