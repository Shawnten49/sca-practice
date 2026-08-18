package com.example.user.service;

import com.example.user.converter.UserProfileConverter;
import com.example.user.dto.request.UserProfileSaveRequest;
import com.example.user.dto.response.UserProfileResponse;
import com.example.user.entity.UserProfile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 用户画像服务：MongoDB 文档存储，按 userId upsert（全量覆盖）+ 查询。 */
@Service
public class UserProfileService {

    private static final int MAX_NICKNAME_LENGTH = 64;
    private static final int MAX_TAGS = 50;
    private static final int MAX_TAG_LENGTH = 32;
    private static final int MAX_EXTRA_KEYS = 20;

    private final MongoTemplate mongoTemplate;
    private final UserProfileConverter userProfileConverter;

    public UserProfileService(MongoTemplate mongoTemplate, UserProfileConverter userProfileConverter) {
        this.mongoTemplate = mongoTemplate;
        this.userProfileConverter = userProfileConverter;
    }

    /**
     * 保存画像：按 userId upsert（存在则全量覆盖，不存在则插入），
     * createTime 仅首次插入时设置，updateTime 每次更新；保存后回查返回完整画像。
     */
    public UserProfileResponse save(UserProfileSaveRequest request) {
        validate(request);
        LocalDateTime now = LocalDateTime.now();
        List<String> tags = request.tags() == null ? List.of() : request.tags();
        Map<String, Object> extra = request.extra() == null ? Map.of() : request.extra();

        Query query = Query.query(Criteria.where("_id").is(request.userId()));
        Update update = new Update()
                .set("nickname", request.nickname())
                .set("tags", tags)
                .set("extra", extra)
                .set("updateTime", now)
                .setOnInsert("createTime", now);
        mongoTemplate.upsert(query, update, UserProfile.class);

        return query(request.userId());
    }

    /** 查询画像；不存在返回 null。 */
    public UserProfileResponse query(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId 必须为正整数");
        }
        UserProfile profile = mongoTemplate.findById(userId, UserProfile.class);
        return profile == null ? null : userProfileConverter.toResponse(profile);
    }

    private void validate(UserProfileSaveRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0) {
            throw new IllegalArgumentException("userId 必须为正整数");
        }
        if (request.nickname() == null || request.nickname().isBlank()) {
            throw new IllegalArgumentException("nickname 不能为空");
        }
        if (request.nickname().length() > MAX_NICKNAME_LENGTH) {
            throw new IllegalArgumentException("nickname 长度不能超过 " + MAX_NICKNAME_LENGTH);
        }
        validateTags(request.tags());
        validateExtra(request.extra());
    }

    private void validateTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        if (tags.size() > MAX_TAGS) {
            throw new IllegalArgumentException("tags 数量不能超过 " + MAX_TAGS);
        }
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                throw new IllegalArgumentException("tags 不能包含空标签");
            }
            if (tag.length() > MAX_TAG_LENGTH) {
                throw new IllegalArgumentException("单个标签长度不能超过 " + MAX_TAG_LENGTH);
            }
        }
    }

    private void validateExtra(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return;
        }
        if (extra.size() > MAX_EXTRA_KEYS) {
            throw new IllegalArgumentException("extra 键数量不能超过 " + MAX_EXTRA_KEYS);
        }
        for (String key : extra.keySet()) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("extra 键不能为空");
            }
        }
    }
}
