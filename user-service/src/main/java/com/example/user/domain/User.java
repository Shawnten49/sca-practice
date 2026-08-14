package com.example.user.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体（MyBatis-Plus）。
 * id 由调用方预生成（IdType.INPUT）；points 为冗余汇总字段，当前由种子数据提供。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("users")
public class User {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String nickname;

    private Integer points;

    private LocalDateTime createTime;
}
