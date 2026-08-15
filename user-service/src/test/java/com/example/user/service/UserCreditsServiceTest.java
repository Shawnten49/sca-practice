package com.example.user.service;

import com.example.exception.BusinessException;
import com.example.user.config.CreditsProperties;
import com.example.user.domain.User;
import com.example.user.dto.CreditsVO;
import com.example.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserCreditsServiceTest {

    private CreditsCache creditsCache;
    private UserMapper userMapper;
    private UserCreditsService service;

    @BeforeEach
    void setUp() {
        creditsCache = mock(CreditsCache.class);
        userMapper = mock(UserMapper.class);
        service = new UserCreditsService(creditsCache, userMapper, new CreditsProperties());
    }

    @Test
    void getCreditsReturnsCredits() {
        when(creditsCache.getWithMutex(1L)).thenReturn(100);

        CreditsVO vo = service.getCredits(1L);

        assertThat(vo.userId()).isEqualTo(1L);
        assertThat(vo.credits()).isEqualTo(100);
    }

    @Test
    void getCreditsUserNotFoundThrows404() {
        when(creditsCache.getWithMutex(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.getCredits(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("99");
    }

    @Test
    void getCreditsRejectsInvalidUserId() {
        assertThatThrownBy(() -> service.getCredits(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getCredits(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateCreditsIncreasesAndInvalidates() {
        when(userMapper.increaseCredits(1L, 100)).thenReturn(1);
        when(creditsCache.getWithMutex(1L)).thenReturn(200);

        CreditsVO vo = service.updateCredits(1L, 100);

        assertThat(vo.credits()).isEqualTo(200);
        verify(creditsCache).invalidate(1L);
    }

    @Test
    void updateCreditsUserNotFoundThrows404() {
        when(userMapper.increaseCredits(99L, 100)).thenReturn(0);
        when(userMapper.selectUserById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCredits(99L, 100))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户不存在");

        verify(creditsCache, never()).invalidate(99L);
    }

    @Test
    void updateCreditsInsufficientThrows409() {
        when(userMapper.increaseCredits(1L, -500)).thenReturn(0);
        when(userMapper.selectUserById(1L))
                .thenReturn(Optional.of(User.builder().id(1L).nickname("demo").points(100).credits(50).build()));

        assertThatThrownBy(() -> service.updateCredits(1L, -500))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("信用点不足");

        verify(creditsCache, never()).invalidate(1L);
    }

    @Test
    void updateCreditsRejectsZeroAndOverLimitDelta() {
        assertThatThrownBy(() -> service.updateCredits(1L, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateCredits(1L, 200000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateCredits(1L, Integer.MIN_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateCreditsRejectsInvalidUserId() {
        assertThatThrownBy(() -> service.updateCredits(0L, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
