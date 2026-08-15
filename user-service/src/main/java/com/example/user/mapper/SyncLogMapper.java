package com.example.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.user.domain.SyncLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * Canal 消费去重表 Mapper。
 * insertIgnore 用 INSERT IGNORE：返回 1 表示抢占成功（可执行业务），0 表示该位点已处理过。
 */
@Mapper
public interface SyncLogMapper extends BaseMapper<SyncLog> {

    int insertIgnore(SyncLog record);
}
