package com.example.dto;

import java.time.LocalDateTime;

/**
 * 用户跨服务传输对象（Redis key: task:user:{id} 的 value，Dubbo 等跨服务契约）。
 * 由 task-service 刷入缓存，供 user-service 等读取；不含敏感字段。
 */
public record UserDTO(Long id, String nickname, Integer points, LocalDateTime createTime) {
}
