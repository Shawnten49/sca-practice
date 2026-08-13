package com.example.user.service;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    @SentinelResource(
            value = "getUserInfo",
            blockHandler = "getUserInfoBlock",
            fallback = "getUserInfoFallback"
    )
    public String getUserInfo(String userId) {
        if ("err".equals(userId)) {
            throw new RuntimeException("user data error");
        }
        return "user:" + userId;
    }

    public String getUserInfoBlock(String userId, BlockException ex) {
        return "【限流】稍后再试：" + ex.getClass().getSimpleName();
    }

    public String getUserInfoFallback(String userId, Throwable t) {
        return "【降级】缓存兜底数据";
    }
}