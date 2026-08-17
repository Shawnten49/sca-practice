package com.example.user.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.example.exception.BusinessException;
import com.example.exception.ErrorCode;
import com.example.id.SnowflakeIdGenerator;
import com.example.user.domain.User;
import com.example.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private static final int MAX_NICKNAME_LENGTH = 64;
    /** 18 位身份证号：17 位数字 + 1 位数字/X */
    private static final String ID_CARD_PATTERN = "^\\d{17}[\\dXx]$";

    private final UserMapper userMapper;

    /** 本机单实例固定 machineId；多实例部署时改为配置注入，避免雪花冲突 */
    private final SnowflakeIdGenerator idGenerator = new SnowflakeIdGenerator(3L);

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @SentinelResource(
            value = "getUserInfo",
            blockHandler = "getUserInfoBlock",
            fallback = "getUserInfoFallback"
    )
    public User getUserInfo(String userId) {
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
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "user not found: " + userId));
    }

    /**
     * 保存用户：雪花 id + 显式列插入；idCard 空/缺失规范化为空串（框架加密后落库），
     * create_time 由数据库默认值填充，插入后回查返回完整用户（idCard 为脱敏碎片）。
     */
    public User saveUser(String nickname, String idCard) {
        validateNickname(nickname);
        String normalizedIdCard = normalizeIdCard(idCard);
        User user = User.builder()
                .id(idGenerator.nextId())
                .nickname(nickname)
                .points(0)
                .credits(0)
                .idCard(normalizedIdCard)
                .build();
        userMapper.insertUser(user);
        return userMapper.selectUserById(user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "user not found after save: " + user.getId()));
    }

    public User getUserInfoBlock(String userId, BlockException ex) {
        return null;
    }

    public User getUserInfoFallback(String userId, Throwable t) {
        return null;
    }

    private void validateNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("nickname 不能为空");
        }
        if (nickname.length() > MAX_NICKNAME_LENGTH) {
            throw new IllegalArgumentException("nickname 长度不能超过 " + MAX_NICKNAME_LENGTH);
        }
    }

    private String normalizeIdCard(String idCard) {
        if (idCard == null || idCard.isBlank()) {
            return "";
        }
        String trimmed = idCard.trim();
        if (!trimmed.matches(ID_CARD_PATTERN)) {
            throw new IllegalArgumentException("idCard 必须是 18 位身份证号");
        }
        return trimmed;
    }
}
