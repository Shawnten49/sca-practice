package com.example.user.mqconsumer.canal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/** users 表变更处理（key: seata_user.users）。本期只输出日志验证链路。 */
@Slf4j
@Component
public class UserHandler implements TableSyncHandler {

    static final Set<String> KEYS = Set.of("seata_user.users");

    private final FieldChangeFilter fieldChangeFilter;

    public UserHandler(FieldChangeFilter fieldChangeFilter) {
        this.fieldChangeFilter = fieldChangeFilter;
    }

    @Override
    public Set<String> supportedKeys() {
        return KEYS;
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    /** 只处理 points 字段发生变更的事件（INSERT 视为新行需要处理）。 */
    @Override
    public boolean shouldHandle(CanalMessage message) {
        return fieldChangeFilter.fieldChanged(message, "points");
    }

    @Override
    public void handle(CanalMessage message) {
        log.info("收到表变更 {} type={} rows={} pkNames={} pos={}:{}",
                message.routeKey(),
                message.type(),
                message.data() == null ? 0 : message.data().size(),
                message.pkNames(),
                message.logFileName(),
                message.logFileOffset());
    }
}
