package com.example.user.mqconsumer.canal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Canal 投递到 MQ 的 binlog 变更事件。
 * 字段与 Canal JSON 对齐（database/table/type/data/old/isDdl/logFileName/logFileOffset...），
 * mysqlType/sqlType 等不关心的字段通过 ignoreUnknown 忽略。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanalMessage(
        String database,
        String table,
        String type,
        boolean isDdl,
        long id,
        String logFileName,
        long logFileOffset,
        List<String> pkNames,
        List<Map<String, Object>> data,
        List<Map<String, Object>> old) {

    /** 路由 key：database.table。 */
    public String routeKey() {
        return database + "." + table;
    }
}
