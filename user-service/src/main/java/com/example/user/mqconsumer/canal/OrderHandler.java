package com.example.user.mqconsumer.canal;
import com.example.dto.canal.CanalEvent;
import com.example.dto.canal.CanalMessage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * orders 分表变更处理（key: seata_order.orders_0 ~ orders_3）。
 * ShardingSphere 分表后 binlog 事件携带的是物理表名，因此注册 4 个物理表 key。
 * 本期只输出日志验证链路。
 */
@Slf4j
@Component
public class OrderHandler implements TableSyncHandler {

    static final Set<String> KEYS = Set.of(
            "seata_order.orders_0",
            "seata_order.orders_1",
            "seata_order.orders_2",
            "seata_order.orders_3");

    @Override
    public Set<String> supportedKeys() {
        return KEYS;
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
