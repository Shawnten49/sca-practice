package com.example.user.mqconsumer;

import com.example.dto.PointAddMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PointMqConsumerTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final PointMqConsumer consumer = spy(new PointMqConsumer(redisTemplate));

    private final PointAddMessage message = new PointAddMessage(123L, 1L, 1L, 2, 200);

    private void stubValueOps() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
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
}
