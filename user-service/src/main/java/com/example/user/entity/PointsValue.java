package com.example.user.entity;

/**
 * 积分缓存值：points 为 null 表示"用户不存在"空值标记
 * （Caffeine 不允许 null value，用包装 record 承载空值）。
 */
public record PointsValue(Integer points) {

    public static PointsValue of(Integer points) {
        return new PointsValue(points);
    }

    public static PointsValue empty() {
        return new PointsValue(null);
    }

    public boolean isEmpty() {
        return points == null;
    }
}
