package com.example.user.service;

import com.example.exception.BusinessException;
import com.example.exception.ErrorCode;
import com.example.user.config.CreditsProperties;
import com.example.user.dto.CreditsVO;
import com.example.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

/**
 * 用户信用点服务：查询走 JetCache 三级缓存；修改走 DB 原子增量（非负守卫）+ 缓存失效。
 */
@Service
public class UserCreditsService {

    private final CreditsCache creditsCache;
    private final UserMapper userMapper;
    private final CreditsProperties props;

    public UserCreditsService(CreditsCache creditsCache, UserMapper userMapper, CreditsProperties props) {
        this.creditsCache = creditsCache;
        this.userMapper = userMapper;
        this.props = props;
    }

    /** 查询信用点；用户不存在返回 404。 */
    public CreditsVO getCredits(Long userId) {
        validateUserId(userId);
        Integer credits = creditsCache.get(userId);
        if (credits == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: userId=" + userId);
        }
        return new CreditsVO(userId, credits);
    }

    /** 增量修改信用点（delta 可为负）；扣减后为负返回 409。 */
    public CreditsVO updateCredits(Long userId, Integer delta) {
        validateUserId(userId);
        if (delta == null || delta == 0) {
            throw new IllegalArgumentException("delta 不能为 0");
        }
        // Math.abs(int) 对 Integer.MIN_VALUE 溢出为负，先转 long 再比较
        if (Math.abs((long) delta) > props.getMaxDelta()) {
            throw new IllegalArgumentException("delta 超出上限: " + props.getMaxDelta());
        }

        int updated = userMapper.increaseCredits(userId, delta);
        if (updated == 0) {
            // 区分「用户不存在(404)」与「扣减后为负(409)」
            boolean exists = userMapper.selectUserById(userId).isPresent();
            if (!exists) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: userId=" + userId);
            }
            throw new BusinessException(ErrorCode.INSUFFICIENT_CREDITS, "信用点不足: userId=" + userId);
        }

        creditsCache.invalidate(userId);
        return getCredits(userId);
    }

    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId 必须是正整数");
        }
    }
}
