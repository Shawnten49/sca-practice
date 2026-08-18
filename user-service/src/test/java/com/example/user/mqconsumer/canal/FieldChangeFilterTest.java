package com.example.user.mqconsumer.canal;
import com.example.dto.canal.CanalEvent;
import com.example.dto.canal.CanalMessage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FieldChangeFilterTest {

    private final FieldChangeFilter filter = new FieldChangeFilter();

    private CanalMessage message(String type, Map<String, Object> old, boolean ddl) {
        return new CanalMessage("seata_user", "users", type, ddl, 1L,
                "mysql-bin.000001", 1L, List.of("id"),
                List.of(Map.of("id", "1", "points", "100")),
                old == null ? null : List.of(old));
    }

    @Test
    void insertAlwaysHandled() {
        assertThat(filter.fieldChanged(message("INSERT", null, false), "points")).isTrue();
    }

    @Test
    void updateWithChangedFieldHandled() {
        assertThat(filter.fieldChanged(message("UPDATE", Map.of("points", "200"), false), "points")).isTrue();
    }

    @Test
    void updateWithoutChangedFieldSkipped() {
        assertThat(filter.fieldChanged(message("UPDATE", Map.of("nickname", "bob"), false), "points")).isFalse();
    }

    @Test
    void updateWithNullOldSkipped() {
        assertThat(filter.fieldChanged(message("UPDATE", null, false), "points")).isFalse();
    }

    @Test
    void deleteNotHandled() {
        assertThat(filter.fieldChanged(message("DELETE", null, false), "points")).isFalse();
    }

    @Test
    void ddlNotHandled() {
        assertThat(filter.fieldChanged(message("ALTER", null, true), "points")).isFalse();
    }
}
