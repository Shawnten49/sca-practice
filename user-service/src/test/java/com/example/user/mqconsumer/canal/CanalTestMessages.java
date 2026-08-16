package com.example.user.mqconsumer.canal;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.CanalPacket;
import com.alibaba.otter.canal.protocol.Message;

import java.util.Arrays;
import java.util.List;

/**
 * 测试工具：构造与 Canal 非 flat 模式一致的 protobuf 消息（CanalPacket.Packet）。
 */
public final class CanalTestMessages {

    private CanalTestMessages() {
    }

    /** 将 Message 序列化为 RocketMQ 消息体（与 CanalRocketMQProducer 的 serializer 对齐）。 */
    public static byte[] packetOf(Message message) {
        CanalPacket.Messages.Builder messages = CanalPacket.Messages.newBuilder()
                .setBatchId(message.getId());
        for (CanalEntry.Entry entry : message.getEntries()) {
            messages.addMessages(entry.toByteString());
        }
        CanalPacket.Packet packet = CanalPacket.Packet.newBuilder()
                .setType(CanalPacket.PacketType.MESSAGES)
                .setVersion(1)
                .setBody(messages.build().toByteString())
                .build();
        return packet.toByteArray();
    }

    public static Message message(long id, CanalEntry.Entry... entries) {
        return new Message(id, Arrays.asList(entries));
    }

    /** 构造一条 ROWDATA 条目（可含多行 RowData，模拟同一条多行 SQL）。 */
    public static CanalEntry.Entry rowEntry(String schema, String table, CanalEntry.EventType eventType,
                                            String logFile, long offset, List<CanalEntry.RowData> rows) {
        CanalEntry.RowChange.Builder rowChange = CanalEntry.RowChange.newBuilder()
                .setEventType(eventType);
        rows.forEach(rowChange::addRowDatas);
        return entry(schema, table, logFile, offset, rowChange.build());
    }

    public static CanalEntry.Entry ddlEntry(String schema, String table, String sql, String logFile, long offset) {
        CanalEntry.RowChange rowChange = CanalEntry.RowChange.newBuilder()
                .setIsDdl(true)
                .setEventType(CanalEntry.EventType.ALTER)
                .setSql(sql)
                .build();
        return entry(schema, table, logFile, offset, rowChange);
    }

    public static CanalEntry.Entry transactionEntry(CanalEntry.EntryType entryType, String logFile, long offset) {
        return CanalEntry.Entry.newBuilder()
                .setHeader(CanalEntry.Header.newBuilder()
                        .setLogfileName(logFile)
                        .setLogfileOffset(offset)
                        .build())
                .setEntryType(entryType)
                .build();
    }

    private static CanalEntry.Entry entry(String schema, String table, String logFile, long offset,
                                          CanalEntry.RowChange rowChange) {
        return CanalEntry.Entry.newBuilder()
                .setHeader(CanalEntry.Header.newBuilder()
                        .setSchemaName(schema)
                        .setTableName(table)
                        .setLogfileName(logFile)
                        .setLogfileOffset(offset)
                        .build())
                .setEntryType(CanalEntry.EntryType.ROWDATA)
                .setStoreValue(rowChange.toByteString())
                .build();
    }

    public static CanalEntry.RowData row(List<CanalEntry.Column> before, List<CanalEntry.Column> after) {
        CanalEntry.RowData.Builder builder = CanalEntry.RowData.newBuilder();
        if (before != null) {
            builder.addAllBeforeColumns(before);
        }
        if (after != null) {
            builder.addAllAfterColumns(after);
        }
        return builder.build();
    }

    public static CanalEntry.Column col(String name, String value, boolean key) {
        return col(name, value, key, false);
    }

    public static CanalEntry.Column col(String name, String value, boolean key, boolean updated) {
        return CanalEntry.Column.newBuilder()
                .setName(name)
                .setValue(value)
                .setIsKey(key)
                .setUpdated(updated)
                .build();
    }
}
