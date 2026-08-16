package com.example.id;

public class SnowflakeIdGenerator implements IdGenerator {
    private static final long START_EPOCH = 1767196800L;   // 2026-01-01
    private static final long MACHINE_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final long MACHINE_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = MACHINE_BITS + SEQUENCE_BITS;
    private static final long MAX_BACKWARD_MS = 5000L;        // 容忍回拨上限

    private final long machineId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(long machineId) {
        if (machineId < 0 || machineId > (1L << MACHINE_BITS) - 1) {
            throw new IllegalArgumentException("machineId 超出范围: " + machineId);
        }
        this.machineId = machineId;
    }

    @Override
    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        if (now < lastTimestamp) {                       // 时钟回拨
            long offset = lastTimestamp - now;
            if (offset > MAX_BACKWARD_MS) {
                throw new IllegalStateException("时钟回拨过大: " + offset + "ms");
            }
            while (now < lastTimestamp) {                // 小回拨：等追平
                now = System.currentTimeMillis();
            }
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {                         // 本毫秒序列号用尽
                while (now <= lastTimestamp) {
                    now = System.currentTimeMillis();    // 等下一毫秒
                }
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        return ((now - START_EPOCH) << TIMESTAMP_SHIFT)
                | (machineId << MACHINE_SHIFT)
                | sequence;
    }

}
