package com.example.user.service;

import com.alicp.jetcache.CacheManager;
import com.example.entity.User;
import com.example.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreditsCacheTest {

    @Test
    void cacheNameIsUserCredits() {
        CreditsCache cache = new CreditsCache(
                mock(CacheManager.class), mock(RedissonClient.class), mock(UserMapper.class));

        assertThat(cache.cacheName()).isEqualTo("user:credits");
    }

    @Test
    void loadFromDbReturnsCreditsOrNull() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectUserById(1L))
                .thenReturn(Optional.of(User.builder().id(1L).nickname("demo").credits(80).build()));
        when(mapper.selectUserById(99L)).thenReturn(Optional.empty());

        CreditsCache cache = new CreditsCache(mock(CacheManager.class), mock(RedissonClient.class), mapper);

        assertThat(cache.loadFromDb(1L)).isEqualTo(80);
        assertThat(cache.loadFromDb(99L)).isNull();
    }
}
