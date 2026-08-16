package com.example.user.mqconsumer.canal;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.CanalPacket;
import com.alibaba.otter.canal.protocol.Message;
import com.google.protobuf.ByteString;
import org.springframework.stereotype.Component;

/**
 * 解析 Canal 非 flat 消息（flatMessage=false）。
 *
 * <p>此时 RocketMQ 消息体是 protobuf 序列化的 {@link CanalPacket.Packet}：
 * type=MESSAGES，body 为 {@link CanalPacket.Messages}，其中每条消息又是
 * {@link CanalEntry.Entry} 的字节。binlog 位点（logfileName/logfileOffset）
 * 保存在每条 Entry 的 Header 中，这是 flatMessage JSON 模式拿不到的位置信息。
 */
@Component
public class CanalPacketParser {

    /**
     * 解析 MQ 消息体，还原 Canal {@link Message}（batchId + Entry 列表）。
     * Entry 中可能包含 TRANSACTIONBEGIN/END、DDL 等，由转换层过滤。
     *
     * @throws Exception 消息体不是合法的 CanalPacket，或包含未知类型
     */
    public Message parse(byte[] body) throws Exception {
        CanalPacket.Packet packet = CanalPacket.Packet.parseFrom(body);
        if (packet.getType() != CanalPacket.PacketType.MESSAGES) {
            throw new IllegalArgumentException("Unexpected canal packet type: " + packet.getType());
        }
        CanalPacket.Messages messages = CanalPacket.Messages.parseFrom(packet.getBody());
        Message canalMessage = new Message(messages.getBatchId());
        for (ByteString raw : messages.getMessagesList()) {
            canalMessage.addEntry(CanalEntry.Entry.parseFrom(raw));
        }
        return canalMessage;
    }
}
