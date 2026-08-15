package com.example.user.mqconsumer.canal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** users 表变更处理（key: seata_user.users）。本期只输出日志验证链路。 */
@Slf4j
@Component
public class UserHandler implements TableSyncHandler {

    static final String KEY = "seata_user.users";

    @Override
    public String supportedKey() {
        return KEY;
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
