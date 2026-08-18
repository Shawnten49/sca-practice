package com.example.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体（MyBatis-Plus，跨服务共享：user-service 读写、task-service 缓存投影复用）。
 * id 由调用方预生成（IdType.INPUT）；points 为积分、credits 为信用点，均为冗余汇总字段。
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

    private Integer credits;

    /**
     * 身份证号（逻辑列，由 ShardingSphere !ENCRYPT + !MASK 处理）：
     * 写入为明文（框架加密后存 id_card_cipher）；读取为脱敏碎片（如 110***********1234）。
     * 非空约定：未录入时为空字符串，永不返回 null。
     */
    private String idCard;

    private LocalDateTime createTime;
}
