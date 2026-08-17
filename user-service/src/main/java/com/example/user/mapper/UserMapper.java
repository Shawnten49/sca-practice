package com.example.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.user.domain.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

/**
 * 用户 Mapper（MyBatis-Plus）。
 * 核心 SQL 人工维护在 resources/mapper/UserMapper.xml 中；
 * 自定义方法与 BaseMapper 内置方法刻意不同名，避免与自动生成的 CRUD 冲突。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** 按 id 查询用户，返回 Optional，SQL 见 UserMapper.xml */
    Optional<User> selectUserById(Long id);

    /** 保存用户（显式列插入，id 由调用方预生成），SQL 见 UserMapper.xml */
    int insertUser(User user);

    /** 累加用户积分：points = points + #{points}，SQL 见 UserMapper.xml */
    int increasePoints(@Param("userId") Long userId, @Param("points") Integer points);

    /** 累加用户信用点（非负守卫）：credits = credits + #{delta}，扣成负返回 0，SQL 见 UserMapper.xml */
    int increaseCredits(@Param("userId") Long userId, @Param("delta") Integer delta);
}
