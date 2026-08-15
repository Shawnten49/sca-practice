package com.example.user.config;

import com.example.user.service.UserPointsService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PointsCacheEvictListenerTest {

    private final PointsCacheEvictQueue evictQueue = mock(PointsCacheEvictQueue.class);
    private final UserPointsService userPointsService = mock(UserPointsService.class);
    private final PointsCacheEvictListener listener =
            new PointsCacheEvictListener(evictQueue, userPointsService);

    @Test
    void consumeInvalidatesCacheNow() {
        listener.consume(1L);

        verify(userPointsService).invalidateNow(1L);
    }

    @Test
    void consumeIgnoresNull() {
        listener.consume(null);

        verify(userPointsService, never()).invalidateNow(anyLong());
    }
}
