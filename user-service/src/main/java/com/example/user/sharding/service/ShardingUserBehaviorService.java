package com.example.user.sharding.service;

import com.example.id.SnowflakeIdGenerator;
import com.example.user.sharding.domain.UserBehavior;
import com.example.user.sharding.mapper.ShardingUserBehaviorMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户行为服务（Sharding 机制）。
 *
 * <p>本模块无 Seata、无分布式事务：创建/查询均为单条 SQL、单分片，autocommit 即可。
 * 注意事务边界：未命名 {@code @Transactional} 绑定的是默认数据源的事务管理器，对 sharding SQL 不生效。
 */
@Service
public class ShardingUserBehaviorService {

    private static final int MAX_ACTION_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 255;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ShardingUserBehaviorMapper shardingUserBehaviorMapper;

    /** 本机单实例固定 machineId；多实例部署时改为配置注入，避免雪花冲突 */
    private final SnowflakeIdGenerator idGenerator = new SnowflakeIdGenerator(3L);

    public ShardingUserBehaviorService(ShardingUserBehaviorMapper shardingUserBehaviorMapper) {
        this.shardingUserBehaviorMapper = shardingUserBehaviorMapper;
    }

    /** 创建行为：雪花 id + 单分片插入，返回带 create_time 的完整记录。 */
    public UserBehavior create(Long userId, String action, String description) {
        validateCreate(userId, action, description);
        UserBehavior record = new UserBehavior();
        record.setId(idGenerator.nextId());
        record.setUserId(userId);
        record.setAction(action);
        record.setDescription(description);
        shardingUserBehaviorMapper.insertUserBehavior(record);
        // create_time 由数据库默认值填充，插入后按 id 回查拿到真实值
        return shardingUserBehaviorMapper.selectById(record.getId());
    }

    /** 按用户查询最近行为（单分片）。 */
    public List<UserBehavior> listByUserId(Long userId, Integer limit) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId 必须为正整数");
        }
        int actualLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (actualLimit <= 0 || actualLimit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit 超出范围: 1~" + MAX_LIMIT);
        }
        return shardingUserBehaviorMapper.selectByUserId(userId, actualLimit);
    }

    private void validateCreate(Long userId, String action, String description) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId 必须为正整数");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action 不能为空");
        }
        if (action.length() > MAX_ACTION_LENGTH) {
            throw new IllegalArgumentException("action 长度不能超过 " + MAX_ACTION_LENGTH);
        }
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("description 长度不能超过 " + MAX_DESCRIPTION_LENGTH);
        }
    }
}
