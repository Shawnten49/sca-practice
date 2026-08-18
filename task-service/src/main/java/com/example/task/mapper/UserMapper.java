package com.example.task.mapper;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.example.task.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 用户表读取（seata_user，按分片 + 游标分页，列白名单不含敏感字段）。 */
@DS("user")
@Mapper
public interface UserMapper {

    List<User> selectByShard(@Param("lastId") Long lastId,
                                @Param("shardIndex") int shardIndex,
                                @Param("shardTotal") int shardTotal,
                                @Param("batchSize") int batchSize);
}
