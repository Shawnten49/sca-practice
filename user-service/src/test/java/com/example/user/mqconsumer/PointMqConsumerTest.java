package com.example.user.mqconsumer;

import com.example.dto.PointAddMessage;
import com.example.user.domain.UserPoints;
import com.example.user.mapper.UserMapper;
import com.example.user.mapper.UserPointsMapper;
import com.example.user.service.UserPointsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PointMqConsumerTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final UserPointsMapper userPointsMapper = mock(UserPointsMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final UserPointsService userPointsService = mock(UserPointsService.class);
    private final PointMqConsumer consumer = spy(
            new PointMqConsumer(redisTemplate, userPointsMapper, userMapper, transactionTemplate, userPointsService));

    private final PointAddMessage message = new PointAddMessage(123L, 1L, 1L, 2, 200);

    private void stubValueOps() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    /** 让 mock 的 TransactionTemplate 立即执行回调，便于验证事务内逻辑。 */
    private void stubTransactionRunsCallback() {
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void skipsDuplicateWhenSetNxFails() {
        stubValueOps();
        when(valueOps.setIfAbsent(eq("mq:dedup:point:123"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        consumer.onMessage(message);

        verify(consumer, never()).addPoints(any());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void processesFirstMessage() {
        stubValueOps();
        when(valueOps.setIfAbsent(eq("mq:dedup:point:123"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        consumer.onMessage(message);

        verify(consumer).addPoints(message);
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void releasesKeyWhenProcessingFails() {
        stubValueOps();
        when(valueOps.setIfAbsent(eq("mq:dedup:point:123"), eq("1"), any(Duration.class)))
                .thenReturn(true);
        doThrow(new RuntimeException("db down")).when(consumer).addPoints(message);

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(RuntimeException.class);

        verify(redisTemplate).delete("mq:dedup:point:123");
    }

    @Test
    void addPointsPersistsRecordAndIncreasesPoints() {
        stubTransactionRunsCallback();
        when(userPointsMapper.insertUserPoints(any(UserPoints.class))).thenReturn(1);

        consumer.addPoints(message);

        ArgumentCaptor<UserPoints> captor = ArgumentCaptor.forClass(UserPoints.class);
        verify(userPointsMapper).insertUserPoints(captor.capture());
        UserPoints record = captor.getValue();
        assertThat(record.getUserId()).isEqualTo(1L);
        assertThat(record.getOrderId()).isEqualTo(123L);
        assertThat(record.getPoints()).isEqualTo(200);

        verify(userMapper).increasePoints(1L, 200);
        verify(userPointsService).invalidatePoints(1L);
    }

    @Test
    void addPointsSkipsIncreaseWhenInsertIgnored() {
        stubTransactionRunsCallback();
        // INSERT IGNORE 命中唯一索引：返回 0，不累加 users.points
        when(userPointsMapper.insertUserPoints(any(UserPoints.class))).thenReturn(0);

        consumer.addPoints(message);

        verify(userPointsMapper).insertUserPoints(any(UserPoints.class));
        verify(userMapper, never()).increasePoints(anyLong(), anyInt());
        verify(userPointsService, never()).invalidatePoints(anyLong());
    }

    @Test
    void addPointsPropagatesWhenIncreaseFails() {
        stubTransactionRunsCallback();
        when(userPointsMapper.insertUserPoints(any(UserPoints.class))).thenReturn(1);
        when(userMapper.increasePoints(anyLong(), anyInt())).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> consumer.addPoints(message))
                .isInstanceOf(RuntimeException.class);

        verify(userPointsMapper).insertUserPoints(any(UserPoints.class));
    }
}
