package com.example.user.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.example.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @SentinelResource(
            value = "getUserInfo",
            blockHandler = "getUserInfoBlock",
            fallback = "getUserInfoFallback"
    )
    public String getUserInfo(String userId) {
        if ("err".equals(userId)) {
            throw new RuntimeException("user data error");
        }
        Long id;
        try {
            id = Long.valueOf(userId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("userId 必须是数字");
        }
        // 真实查询 users 表（SQL 见 UserMapper.xml）
        return userMapper.selectUserById(id)
                .map(u -> "user:" + u.getId() + " nickname=" + u.getNickname() + " points=" + u.getPoints())
                .orElse("user not found: " + userId);
    }

    public String getUserInfoBlock(String userId, BlockException ex) {
        return "【限流】稍后再试：" + ex.getClass().getSimpleName();
    }

    public String getUserInfoFallback(String userId, Throwable t) {
        return "【降级】缓存兜底数据";
    }
}
