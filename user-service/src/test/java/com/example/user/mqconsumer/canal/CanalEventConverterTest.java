package com.example.user.mqconsumer.canal;
import com.example.dto.canal.CanalEvent;
import com.example.dto.canal.CanalMessage;

import com.alibaba.otter.canal.protocol.CanalEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.user.mqconsumer.canal.CanalTestMessages.col;
import static com.example.user.mqconsumer.canal.CanalTestMessages.message;
import static com.example.user.mqconsumer.canal.CanalTestMessages.row;
import static com.example.user.mqconsumer.canal.CanalTestMessages.rowEntry;
import static org.assertj.core.api.Assertions.assertThat;

class CanalEventConverterTest {

    private final CanalEventConverter converter = new CanalEventConverter();

    @Test
    void convertsInsertEntryWithPositionAndPkRowKey() throws Exception {
        CanalEntry.Entry entry = rowEntry("seata_user", "users", CanalEntry.EventType.INSERT,
                "mysql-bin.000001", 1234L,
                List.of(row(null, List.of(col("id", "3", true), col("nickname", "alice", false)))));

        List<CanalEvent> events = converter.toEvents(message(42L, entry));

        assertThat(events).hasSize(1);
        CanalEvent event = events.get(0);
        assertThat(event.rowKey()).isEqualTo("3");
        CanalMessage m = event.message();
        assertThat(m.database()).isEqualTo("seata_user");
        assertThat(m.table()).isEqualTo("users");
        assertThat(m.type()).isEqualTo("INSERT");
        assertThat(m.isDdl()).isFalse();
        assertThat(m.logFileName()).isEqualTo("mysql-bin.000001");
        assertThat(m.logFileOffset()).isEqualTo(1234L);
        assertThat(m.pkNames()).containsExactly("id");
        assertThat(m.data()).hasSize(1);
        assertThat(m.data().get(0)).containsEntry("id", "3").containsEntry("nickname", "alice");
        assertThat(m.old()).isNull();
    }

    @Test
    void updateCarriesOldValuesAndPkRowKey() throws Exception {
        CanalEntry.Entry entry = rowEntry("seata_user", "users", CanalEntry.EventType.UPDATE,
                "mysql-bin.000001", 2345L,
                List.of(row(List.of(col("id", "3", true), col("points", "100", false)),
                        List.of(col("id", "3", true), col("points", "200", false, true)))));

        List<CanalEvent> events = converter.toEvents(message(42L, entry));

        assertThat(events).hasSize(1);
        CanalMessage m = events.get(0).message();
        assertThat(m.type()).isEqualTo("UPDATE");
        assertThat(m.data().get(0)).containsEntry("points", "200");
        assertThat(m.old()).hasSize(1);
        // old 只保留 updated=true 的列（points 变更了才会出现在 old 中）
        assertThat(m.old().get(0)).containsEntry("points", "100");
        assertThat(m.old().get(0)).hasSize(1);
        assertThat(events.get(0).rowKey()).isEqualTo("3");
    }

    @Test
    void updateOldOnlyContainsChangedColumns() throws Exception {
        // 只改了 nickname，points 未变：old 中不应出现 points
        CanalEntry.Entry entry = rowEntry("seata_user", "users", CanalEntry.EventType.UPDATE,
                "mysql-bin.000001", 2500L,
                List.of(row(
                        List.of(col("id", "3", true),
                                col("points", "100", false),
                                col("nickname", "old-name", false)),
                        List.of(col("id", "3", true),
                                col("points", "100", false),
                                col("nickname", "new-name", false, true)))));

        List<CanalEvent> events = converter.toEvents(message(42L, entry));

        CanalMessage m = events.get(0).message();
        assertThat(m.old()).hasSize(1);
        assertThat(m.old().get(0)).containsEntry("nickname", "old-name");
        assertThat(m.old().get(0)).doesNotContainKey("points");
    }

    @Test
    void deleteUsesBeforeImageAsData() throws Exception {
        CanalEntry.Entry entry = rowEntry("seata_user", "users", CanalEntry.EventType.DELETE,
                "mysql-bin.000001", 3456L,
                List.of(row(List.of(col("id", "3", true), col("nickname", "alice", false)), null)));

        List<CanalEvent> events = converter.toEvents(message(42L, entry));

        CanalMessage m = events.get(0).message();
        assertThat(m.type()).isEqualTo("DELETE");
        assertThat(m.data()).hasSize(1);
        assertThat(m.data().get(0)).containsEntry("id", "3").containsEntry("nickname", "alice");
        assertThat(m.old()).isNull();
        assertThat(events.get(0).rowKey()).isEqualTo("3");
    }

    @Test
    void multiRowEventSharesPositionButGetsDistinctRowKeys() throws Exception {
        CanalEntry.Entry entry = rowEntry("seata_user", "users", CanalEntry.EventType.UPDATE,
                "mysql-bin.000001", 9999L,
                List.of(
                        row(List.of(col("id", "1", true), col("points", "10", false)),
                                List.of(col("id", "1", true), col("points", "11", false))),
                        row(List.of(col("id", "2", true), col("points", "20", false)),
                                List.of(col("id", "2", true), col("points", "21", false)))));

        List<CanalEvent> events = converter.toEvents(message(42L, entry));

        assertThat(events).hasSize(2);
        // 两条事件位点相同（同一条多行 SQL），行级 key 必须不同，否则会被误去重
        assertThat(events.get(0).message().logFileOffset()).isEqualTo(events.get(1).message().logFileOffset());
        assertThat(events.get(0).rowKey()).isEqualTo("1");
        assertThat(events.get(1).rowKey()).isEqualTo("2");
    }

    @Test
    void tableWithoutPkFallsBackToRowOrdinal() throws Exception {
        CanalEntry.Entry entry = rowEntry("seata_user", "audit_log", CanalEntry.EventType.INSERT,
                "mysql-bin.000001", 100L,
                List.of(
                        row(null, List.of(col("content", "a", false))),
                        row(null, List.of(col("content", "b", false)))));

        List<CanalEvent> events = converter.toEvents(message(42L, entry));

        assertThat(events).hasSize(2);
        assertThat(events.get(0).rowKey()).isEqualTo("r0");
        assertThat(events.get(1).rowKey()).isEqualTo("r1");
        assertThat(events.get(0).message().pkNames()).isEmpty();
    }

    @Test
    void ddlEntryProducedAsDdlEvent() throws Exception {
        CanalEntry.Entry entry = CanalTestMessages.ddlEntry("seata_user", "users",
                "ALTER TABLE users ADD COLUMN x INT", "mysql-bin.000001", 200L);

        List<CanalEvent> events = converter.toEvents(message(42L, entry));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).message().isDdl()).isTrue();
        assertThat(events.get(0).message().type()).isEqualTo("ALTER");
    }

    @Test
    void transactionEntriesAreIgnored() throws Exception {
        CanalEntry.Entry entry = rowEntry("seata_user", "users", CanalEntry.EventType.INSERT,
                "mysql-bin.000001", 300L,
                List.of(row(null, List.of(col("id", "1", true)))));

        List<CanalEvent> events = converter.toEvents(message(42L,
                CanalTestMessages.transactionEntry(CanalEntry.EntryType.TRANSACTIONBEGIN, "mysql-bin.000001", 100L),
                entry,
                CanalTestMessages.transactionEntry(CanalEntry.EntryType.TRANSACTIONEND, "mysql-bin.000001", 400L)));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).message().table()).isEqualTo("users");
    }
}
