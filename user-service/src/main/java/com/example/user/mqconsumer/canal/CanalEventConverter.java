package com.example.user.mqconsumer.canal;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Canal 的 {@link Message}（Entry 列表）转换为按行拆分的事件列表。
 *
 * <p>要点：
 * <ul>
 *   <li>只处理 ROWDATA 条目；TRANSACTIONBEGIN/TRANSACTIONEND 直接忽略；</li>
 *   <li>一条 ROWDATA 条目可能携带多行 RowData，逐行拆成独立事件（每行一个位点 + 行级 key）；</li>
 *   <li>data/old 语义与 Canal flatMessage 对齐：INSERT data=新值；UPDATE data=新值 old=旧值；
 *       DELETE data=旧值（被删行）；</li>
 *   <li>行级去重键优先用主键值拼接（多行 SQL 共享位点时靠它区分），无主键退化为消息内行号。</li>
 * </ul>
 */
@Component
public class CanalEventConverter {

    public List<CanalEvent> toEvents(Message canalMessage) throws Exception {
        List<CanalEvent> events = new ArrayList<>();
        long batchId = canalMessage.getId();
        int rowNo = 0;

        for (CanalEntry.Entry entry : canalMessage.getEntries()) {
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                continue;
            }
            CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            if (rowChange.getIsDdl()) {
                events.add(toDdlEvent(batchId, entry, rowChange));
                continue;
            }
            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                events.add(toRowEvent(batchId, entry, rowChange, rowData, rowNo));
                rowNo++;
            }
        }
        return events;
    }

    private CanalEvent toRowEvent(long batchId, CanalEntry.Entry entry,
                                  CanalEntry.RowChange rowChange,
                                  CanalEntry.RowData rowData, int rowNo) {
        CanalEntry.Header header = entry.getHeader();
        CanalEntry.EventType eventType = rowChange.getEventType();
        List<CanalEntry.Column> before = rowData.getBeforeColumnsList();
        List<CanalEntry.Column> after = rowData.getAfterColumnsList();

        // 与 flatMessage 对齐：INSERT/UPDATE 用 after；DELETE 用 before
        List<CanalEntry.Column> dataColumns = switch (eventType) {
            case DELETE -> before;
            default -> after.isEmpty() ? before : after;
        };
        Map<String, Object> dataMap = toMap(dataColumns);
        Map<String, Object> oldMap = switch (eventType) {
            case UPDATE -> toMap(before);
            default -> null;
        };

        List<String> pkNames = new ArrayList<>();
        for (CanalEntry.Column column : dataColumns) {
            if (column.getIsKey()) {
                pkNames.add(column.getName());
            }
        }

        String rowKey = buildRowKey(dataColumns, rowNo);
        CanalMessage message = new CanalMessage(
                header.getSchemaName(),
                header.getTableName(),
                eventType.name(),
                false,
                batchId,
                header.getLogfileName(),
                header.getLogfileOffset(),
                pkNames,
                dataMap == null ? null : List.of(dataMap),
                oldMap == null ? null : List.of(oldMap));
        return new CanalEvent(message, rowKey);
    }

    private CanalEvent toDdlEvent(long batchId, CanalEntry.Entry entry, CanalEntry.RowChange rowChange) {
        CanalEntry.Header header = entry.getHeader();
        CanalMessage message = new CanalMessage(
                header.getSchemaName(),
                header.getTableName(),
                rowChange.getEventType().name(),
                true,
                batchId,
                header.getLogfileName(),
                header.getLogfileOffset(),
                List.of(),
                null,
                null);
        return new CanalEvent(message, "");
    }

    /** 主键值按列顺序逗号拼接；无主键列时退回消息内行号（r0/r1...）。 */
    private String buildRowKey(List<CanalEntry.Column> columns, int rowNo) {
        List<String> pkValues = new ArrayList<>();
        for (CanalEntry.Column column : columns) {
            if (column.getIsKey()) {
                pkValues.add(column.getValue());
            }
        }
        return pkValues.isEmpty() ? "r" + rowNo : String.join(",", pkValues);
    }

    private Map<String, Object> toMap(List<CanalEntry.Column> columns) {
        if (columns.isEmpty()) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (CanalEntry.Column column : columns) {
            map.put(column.getName(), column.getIsNull() ? null : column.getValue());
        }
        return map;
    }
}
