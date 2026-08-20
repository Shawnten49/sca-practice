package com.example.user.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.example.common.CacheKeys;
import com.example.converter.UserConverter;
import com.example.dto.UserDTO;
import com.example.dto.response.UserResponse;
import com.example.entity.User;
import com.example.exception.BusinessException;
import com.example.exception.ErrorCode;
import com.example.id.SnowflakeIdGenerator;
import com.example.user.mapper.UserMapper;
import com.example.user.metrics.UserMetricsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private static final int MAX_NICKNAME_LENGTH = 64;
    /** 18 位身份证号：17 位数字 + 1 位数字/X */
    private static final String ID_CARD_PATTERN = "^\\d{17}[\\dXx]$";

    private final UserMapper userMapper;
    private final UserConverter userConverter;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final UserMetricsService userMetricsService;

    /** 本机单实例固定 machineId；多实例部署时改为配置注入，避免雪花冲突 */
    private final SnowflakeIdGenerator idGenerator = new SnowflakeIdGenerator(3L);

    public UserService(UserMapper userMapper, UserConverter userConverter,
                       StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper, UserMetricsService userMetricsService) {
        this.userMapper = userMapper;
        this.userConverter = userConverter;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.userMetricsService = userMetricsService;
    }

    @SentinelResource(
            value = "getUserInfo",
            blockHandler = "getUserInfoBlock",
            fallback = "getUserInfoFallback"
    )
    public UserResponse getUserInfo(String userId) {
        Long start = System.currentTimeMillis();
        if ("err".equals(userId)) {
            throw new RuntimeException("user data error");
        }
        Long id;
        try {
            id = Long.valueOf(userId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("userId 必须是数字");
        }
        // 真实查询 users 表（SQL 见 UserMapper.xml），实体仅在 Service 内部流转
        User user = userMapper.selectUserById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "user not found: " + userId));

        userMetricsService.recordUserQuery(System.currentTimeMillis() - start);

        return userConverter.toResponse(user);
    }

    /**
     * 保存用户：雪花 id + 显式列插入；idCard 空/缺失规范化为空串（框架加密后落库），
     * create_time 由数据库默认值填充，插入后回查返回完整用户（idCard 为脱敏碎片）。
     */
    public UserResponse saveUser(String nickname, String idCard) {
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
        User saved = userMapper.selectUserById(user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "user not found after save: " + user.getId()));
        return userConverter.toResponse(saved);
    }

    /**
     * 从缓存读取用户（task-service 定时刷新，key: task:user:{id}）。
     * 纯缓存读：未命中返回 null，不回源 DB。
     */
    public UserResponse getUserFromCache(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId 必须为正整数");
        }
        String json = stringRedisTemplate.opsForValue().get(CacheKeys.USER_PREFIX + userId);
        if (json == null) {
            return null;
        }
        try {
            UserDTO dto = objectMapper.readValue(json, UserDTO.class);
            return userConverter.toResponse(dto);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("缓存用户数据解析失败: userId=" + userId, e);
        }
    }

    public UserResponse getUserInfoBlock(String userId, BlockException ex) {
        return null;
    }

    public UserResponse getUserInfoFallback(String userId, Throwable t) {
        if (t instanceof BusinessException) {
            throw (BusinessException) t;   // 业务异常透传，GlobalExceptionHandler 返回 404
        }

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
