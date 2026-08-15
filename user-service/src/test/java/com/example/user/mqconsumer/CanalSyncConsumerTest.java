package com.example.user.mqconsumer;

import com.example.user.mqconsumer.canal.CanalMessage;
import com.example.user.mqconsumer.canal.IdempotencyFacade;
import com.example.user.mqconsumer.canal.OrderHandler;
import com.example.user.mqconsumer.canal.TableSyncHandler;
import com.example.user.mqconsumer.canal.UserHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class CanalSyncConsumerTest {

    private static final String USER_INSERT_JSON = """
            {"data":[{"id":"1","nickname":"demo","points":"100"}],"database":"seata_user",
             "es":1786816662000,"gtid":"","id":1,"isDdl":false,"old":null,"pkNames":["id"],
             "sql":"","table":"users","ts":1786816662633,"type":"INSERT","logFileName":"mysql-bin.000001","logFileOffset":1234}
            """;

    private static final String ORDER_UPDATE_JSON = """
            {"data":[{"id":"4","user_id":"1","product_id":"1","count":"1"}],"database":"seata_order",
             "old":[{"count":"2"}],"pkNames":["id"],"isDdl":false,"table":"orders",
             "type":"UPDATE","logFileName":"mysql-bin.000002","logFileOffset":5678}
            """;

    private static final String UNKNOWN_TABLE_JSON = """
            {"database":"seata_stock","table":"stock","isDdl":false,"type":"UPDATE","pkNames":["id"]}
            """;

    private static final String DDL_JSON = """
            {"database":"seata_user","table":"users","isDdl":true,"type":"ALTER","sql":"ALTER TABLE users ADD COLUMN x INT"}
            """;

    private static final String ORDER_ITEMS_JSON = """
            {"data":[{"id":"9","order_id":"4","product_id":"1"}],"database":"seata_order",
             "isDdl":false,"pkNames":["id"],"table":"order_items","type":"INSERT",
             "logFileName":"mysql-bin.000003","logFileOffset":999}
            """;

    private final CanalSyncConsumer consumerWithRealHandlers =
            new CanalSyncConsumer(new ObjectMapper(),
                    List.of(new UserHandler(), new OrderHandler()),
                    mock(IdempotencyFacade.class));

    @Test
    void routesUserTableEventToUserHandler(CapturedOutput output) {
        consumerWithRealHandlers.onMessage(USER_INSERT_JSON);

        assertThat(output).contains("seata_user.users", "type=INSERT", "rows=1", "mysql-bin.000001:1234");
    }

    @Test
    void routesOrderTableEventToOrderHandler(CapturedOutput output) {
        consumerWithRealHandlers.onMessage(ORDER_UPDATE_JSON);

        assertThat(output).contains("seata_order.orders", "type=UPDATE", "rows=1");
    }

    @Test
    void skipsUnregisteredTable(CapturedOutput output) {
        consumerWithRealHandlers.onMessage(UNKNOWN_TABLE_JSON);

        assertThat(output).contains("未注册的表变更事件，跳过: seata_stock.stock");
    }

    @Test
    void skipsDdlEvent(CapturedOutput output) {
        consumerWithRealHandlers.onMessage(DDL_JSON);

        assertThat(output).contains("DDL 事件不处理，跳过: seata_user.users");
    }

    @Test
    void skipsMalformedJsonWithoutThrowing(CapturedOutput output) {
        consumerWithRealHandlers.onMessage("not-a-json");

        assertThat(output).contains("解析 Canal 消息失败");
    }

    @Test
    void dispatchesBySupportedKeyUsingRouteTable() {
        TableSyncHandler user = mock(TableSyncHandler.class);
        TableSyncHandler order = mock(TableSyncHandler.class);
        when(user.supportedKey()).thenReturn("seata_user.users");
        when(user.idempotent()).thenReturn(true);
        when(order.supportedKey()).thenReturn("seata_order.orders");
        CanalSyncConsumer consumer = new CanalSyncConsumer(new ObjectMapper(),
                List.of(user, order), mock(IdempotencyFacade.class));

        consumer.onMessage(USER_INSERT_JSON);

        verify(user).handle(any(CanalMessage.class));
        verify(order, never()).handle(any());
    }

    @Test
    void nonIdempotentHandlerGoesThroughDedupFacade() {
        TableSyncHandler orderItems = mock(TableSyncHandler.class);
        when(orderItems.supportedKey()).thenReturn("seata_order.order_items");
        when(orderItems.idempotent()).thenReturn(false);
        IdempotencyFacade facade = mock(IdempotencyFacade.class);
        CanalSyncConsumer consumer = new CanalSyncConsumer(new ObjectMapper(),
                List.of(orderItems), facade);

        consumer.onMessage(ORDER_ITEMS_JSON);

        verify(facade).executeWithDedup(any(CanalMessage.class), any(Runnable.class));
        verify(orderItems, never()).handle(any());
    }
}
