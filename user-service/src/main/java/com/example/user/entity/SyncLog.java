package com.example.user.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Canal 消费幂等去重表实体（sync_log）：以 binlog 位点为唯一键。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sync_log")
public class SyncLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String logFileName;

    private Long logFileOffset;

    /** 行级去重键：主键值拼接（如 "3"），无主键表为消息内行号（如 "r0"）。 */
    private String rowKey;

    private LocalDateTime createTime;
}
