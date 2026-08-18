package com.example.task.model;

import java.time.LocalDateTime;

/** 用户行（仅业务展示字段，不含敏感列）。 */
public record UserRow(Long id, String nickname, Integer points, LocalDateTime createTime) {
}
