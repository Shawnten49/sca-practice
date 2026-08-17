package com.example.user.sharding.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** 用户行为实体（Sharding 机制，逻辑表 user_behavior → user_behavior_0~3）。 */
@Data
public class UserBehavior {

    /** 雪花主键（服务端生成，全局唯一） */
    private Long id;

    /** 用户ID（分片键，user_id % 4） */
    private Long userId;

    /** 行为类型，如 login / order / click */
    private String action;

    /** 行为描述，可空 */
    private String description;

    /** 发生时间（数据库默认 CURRENT_TIMESTAMP(6)，应用层不传） */
    private LocalDateTime createTime;
}
