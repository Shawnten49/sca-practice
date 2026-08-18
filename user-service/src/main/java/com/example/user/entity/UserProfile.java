package com.example.user.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户画像（MongoDB 文档实体）。
 * userId 直接作为 _id，天然唯一；tags/extra 自由扩展，无需改表结构。
 */
@Data
@Document("user_profile")
public class UserProfile {

    @Id
    private Long userId;

    private String nickname;

    private List<String> tags;

    private Map<String, Object> extra;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
