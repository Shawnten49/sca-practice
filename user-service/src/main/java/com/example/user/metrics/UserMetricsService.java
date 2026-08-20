package com.example.user.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class UserMetricsService {
    private final Counter userQueryCount;      // 用户查询数：Counter 只增不减
    private final Timer userQueryTimer;        // 用户查询耗时：Timer 自带直方图，能算 P99

    public UserMetricsService(MeterRegistry registry) {
        this.userQueryCount = Counter.builder("sca.users.queries")
                .description("用户查询总数")
                .register(registry);
        this.userQueryTimer = Timer.builder("sca.users.query.duration")
                .description("用户查询耗时")
                .publishPercentileHistogram()   // 生成直方图桶
                .register(registry);
    }

    public void recordUserQuery(long costMillis) {
        userQueryCount.increment();
        userQueryTimer.record(Duration.ofMillis(costMillis));
    }

}
