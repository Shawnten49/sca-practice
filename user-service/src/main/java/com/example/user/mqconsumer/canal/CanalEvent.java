package com.example.user.mqconsumer.canal;

/**
 * 一条可消费的 binlog 行变更事件：{@link CanalMessage} + 行级去重键。
 *
 * @param message 面向 Handler 的消息模型（database/table/type/data/old/位点等）
 * @param rowKey  行级去重键：主键值拼接（如 "3"、"1,2"）；无主键的表退回消息内行号（"r0"、"r1"...
 */
public record CanalEvent(CanalMessage message, String rowKey) {
}
