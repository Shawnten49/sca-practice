package com.example.user.mqconsumer.canal;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 字段级变更过滤器：判断一条行变更事件是否涉及关心的字段。
 *
 * <p>依赖 Canal UPDATE 消息语义：old 只包含真正变更的列（转换层按 Column.updated 过滤），
 * 因此 old 中存在该字段即代表它发生了变化。
 *
 * <ul>
 *   <li>INSERT：新行，字段有初始值，视为需要处理；</li>
 *   <li>UPDATE：old 中存在该字段才处理；</li>
 *   <li>DELETE：不触发字段变更；</li>
 *   <li>DDL：不触发。</li>
 * </ul>
 */
@Component
public class FieldChangeFilter {

    public boolean fieldChanged(CanalMessage message, String field) {
        if (message.isDdl()) {
            return false;
        }
        return switch (message.type()) {
            case "INSERT" -> true;
            case "UPDATE" -> oldHasField(message, field);
            case "DELETE" -> false;
            default -> false;
        };
    }

    private boolean oldHasField(CanalMessage message, String field) {
        if (message.old() == null || message.old().isEmpty()) {
            return false;
        }
        Map<String, Object> oldRow = message.old().get(0);
        return oldRow != null && oldRow.containsKey(field);
    }
}
