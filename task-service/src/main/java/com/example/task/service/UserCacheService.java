package com.example.task.service;

import com.example.common.CacheKeys;
import com.example.task.config.TaskProperties;
import com.example.task.mapper.UserMapper;
import com.example.dto.UserDTO;
import com.example.converter.UserConverter;
import com.example.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/** 用户缓存刷新：全量用户按分片 + 游标分页读取并写 Redis（不含敏感字段）。 */
@Slf4j
@Service
public class UserCacheService {

    private static final String KEY_PREFIX = CacheKeys.USER_PREFIX;

    private final UserMapper userMapper;
    private final TaskCacheWriter cacheWriter;
    private final TaskProperties properties;
    private final UserConverter userConverter;

    public UserCacheService(UserMapper userMapper, TaskCacheWriter cacheWriter,
                            TaskProperties properties,
                             UserConverter userConverter) {
        this.userMapper = userMapper;
        this.cacheWriter = cacheWriter;
        this.properties = properties;
        this.userConverter = userConverter;
    }

    /** 刷新全部用户，返回本实例处理的总条数。 */
    public long refreshAll(int shardIndex, int shardTotal) {
        long lastId = 0L;
        long total = 0L;
        while (true) {
            List<User> batch = userMapper.selectByShard(
                    lastId, shardIndex, shardTotal, properties.getBatchSize());
            if (batch.isEmpty()) {
                break;
            }
            cacheWriter.writeBatch(KEY_PREFIX, batch.stream().map(UserConverter.INSTANCE::toDTO).toList(), UserDTO::id, properties.getUserTtl());
            total += batch.size();
            lastId = batch.get(batch.size() - 1).getId();
            if (batch.size() < properties.getBatchSize()) {
                break;
            }
            sleepIfNeeded();
        }
        log.info("用户刷新完成，共 {} 条（shard {}/{}）", total, shardIndex, shardTotal);
        return total;
    }

    private void sleepIfNeeded() {
        long sleepMs = properties.getBatchSleepMs();
        if (sleepMs > 0) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
