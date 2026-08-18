package com.example.user.service;

import com.example.user.converter.UserProfileConverter;
import com.example.user.dto.request.UserProfileSaveRequest;
import com.example.user.dto.response.UserProfileResponse;
import com.example.user.entity.UserProfile;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProfileServiceTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final UserProfileService userProfileService =
            new UserProfileService(mongoTemplate, UserProfileConverter.INSTANCE);

    private UserProfile profile(Long userId) {
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setNickname("demo");
        profile.setTags(List.of("vip"));
        profile.setExtra(Map.of("level", 5));
        profile.setCreateTime(LocalDateTime.now());
        profile.setUpdateTime(LocalDateTime.now());
        return profile;
    }

    @Test
    void saveUpsertsAndReturnsRequeriedProfile() {
        when(mongoTemplate.findById(1L, UserProfile.class)).thenReturn(profile(1L));

        UserProfileResponse response = userProfileService.save(
                new UserProfileSaveRequest(1L, "demo", List.of("vip"), Map.of("level", 5)));

        verify(mongoTemplate).upsert(any(Query.class), any(Update.class), eq(UserProfile.class));
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.nickname()).isEqualTo("demo");
        assertThat(response.tags()).containsExactly("vip");
        assertThat(response.extra()).containsEntry("level", 5);
    }

    @Test
    void queryHitReturnsProfile() {
        when(mongoTemplate.findById(1L, UserProfile.class)).thenReturn(profile(1L));

        UserProfileResponse response = userProfileService.query(1L);

        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.tags()).containsExactly("vip");
    }

    @Test
    void queryMissReturnsNull() {
        when(mongoTemplate.findById(999L, UserProfile.class)).thenReturn(null);

        assertThat(userProfileService.query(999L)).isNull();
    }

    @Test
    void invalidUserIdRejected() {
        assertThatThrownBy(() -> userProfileService.query(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveRejectsBlankNickname() {
        assertThatThrownBy(() -> userProfileService.save(
                new UserProfileSaveRequest(1L, "  ", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nickname");
    }

    @Test
    void saveRejectsTooManyTags() {
        List<String> tags = java.util.stream.IntStream.rangeClosed(1, 51)
                .mapToObj(i -> "tag" + i).toList();
        assertThatThrownBy(() -> userProfileService.save(
                new UserProfileSaveRequest(1L, "demo", tags, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tags");
    }

    @Test
    void saveRejectsTooManyExtraKeys() {
        Map<String, Object> extra = new java.util.HashMap<>();
        for (int i = 1; i <= 21; i++) {
            extra.put("k" + i, i);
        }
        assertThatThrownBy(() -> userProfileService.save(
                new UserProfileSaveRequest(1L, "demo", null, extra)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extra");
    }
}
