package com.example.dto;

import java.time.LocalDateTime;

/**
 * 订单跨服务传输对象（Redis key: task:order:{id} 的 value）。
 * 由 task-service 刷入缓存，供各业务服务读取。
 */
public record OrderDTO(Long id, Long userId, Long productId, Integer count, LocalDateTime createTime) {
}
