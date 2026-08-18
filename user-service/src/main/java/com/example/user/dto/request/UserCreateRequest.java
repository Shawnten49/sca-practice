package com.example.user.dto;

/**
 * 保存用户请求体。
 *
 * @param nickname 昵称（必填）
 * @param idCard   身份证号（可空；空/缺失统一规范化为空字符串落库，框架负责加密存储）
 */
public record UserSaveRequest(String nickname, String idCard) {
}
