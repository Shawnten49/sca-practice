package com.example.user.mqconsumer;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.example.user.mqconsumer.canal.CanalEventConverter;
import com.example.user.mqconsumer.canal.CanalPacketParser;
import com.example.user.mqconsumer.canal.CanalTestMessages;
import com.example.user.mqconsumer.canal.FieldChangeFilter;
import com.example.user.mqconsumer.canal.IdempotencyFacade;
import com.example.user.mqconsumer.canal.OrderHandler;
import com.example.user.mqconsumer.canal.TableSyncHandler;
import com.example.user.mqconsumer.canal.UserHandler;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static com.example.user.mqconsumer.canal.CanalTestMessages.col;
import static com.example.user.mqconsumer.canal.CanalTestMessages.message;
import static com.example.user.mqconsumer.canal.CanalTestMessages.row;
import static com.example.user.mqconsumer.canal.CanalTestMessages.rowEntry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class CanalSyncConsumerTest {

    /** mock 门面：立即执行传入的业务 Runnable，让真实 Handler 的日志输出可被断言。 */
    private static IdempotencyFacade executingFacade() {
        IdempotencyFacade facade = mock(IdempotencyFacade.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(2)).run();
            return null;
        }).when(facade).executeWithDedup(any(), anyString(), any(Runnable.class));
        return facade;
    }

    private final CanalSyncConsumer consumerWithRealHandlers =
            new CanalSyncConsumer(new CanalPacketParser(), new CanalEventConverter(),
                    List.of(new UserHandler(new FieldChangeFilter()), new OrderHandler()),
                    executingFacade());

    private static MessageExt messageExt(CanalEntry.Entry... entries) {
        MessageExt ext = new MessageExt();
        ext.setTopic("canal-topic");
        ext.setQueueId(0);
        ext.setMsgId("test-msg");
        ext.setBody(CanalTestMessages.packetOf(message(1L, entries)));
        return ext;
    }

    @Test
    void routesUserTableEventToUserHandler(CapturedOutput output) {
        CanalEntry.Entry entry = rowEntry("seata_user", "users", CanalEntry.EventType.INSERT,
                "mysql-bin.000001", 1234L,
                List.of(row(null, List.of(col("id", "1", true), col("nickname", "demo", false)))));

        consumerWithRealHandlers.onMessage(messageExt(entry));

        assertThat(output).contains("seata_user.users", "type=INSERT", "rows=1", "mysql-bin.000001:1234");
    }

    @Test
    void skipsUserUpdateWhenPointsUnchanged(CapturedOutput output) {
        CanalEntry.Entry entry = rowEntry("seata_user", "users", CanalEntry.EventType.UPDATE,
                "mysql-bin.000001", 1500L,
                List.of(row(
                        List.of(col("id", "1", true),
                                col("points", "100", false),
                                col("nickname", "old", false)),
                        List.of(col("id", "1", true),
                                col("points", "100", false),
                                col("nickname", "new", false, true)))));

        consumerWithRealHandlers.onMessage(messageExt(entry));

        assertThat(output).contains("字段未变更，跳过: seata_user.users");
        assertThat(output).doesNotContain("收到表变更 seata_user.users");
    }

    @Test
    void routesOrderTableEventToOrderHandler(CapturedOutput output) {
        CanalEntry.Entry entry = rowEntry("seata_order", "orders", CanalEntry.EventType.UPDATE,
                "mysql-bin.000002", 5678L,
                List.of(row(
                        List.of(col("id", "4", true), col("count", "2", false)),
                        List.of(col("id", "4", true), col("count", "1", false)))));

        consumerWithRealHandlers.onMessage(messageExt(entry));

        assertThat(output).contains("seata_order.orders", "type=UPDATE", "rows=1");
    }

    @Test
    void skipsUnregisteredTable(CapturedOutput output) {
        CanalEntry.Entry entry = rowEntry("seata_stock", "stock", CanalEntry.EventType.UPDATE,
                "mysql-bin.000001", 100L,
                List.of(row(null, List.of(col("id", "1", true)))));

        consumerWithRealHandlers.onMessage(messageExt(entry));

        assertThat(output).contains("未注册的表变更事件，跳过: seata_stock.stock");
    }

    @Test
    void skipsDdlEvent(CapturedOutput output) {
        CanalEntry.Entry entry = CanalTestMessages.ddlEntry("seata_user", "users",
                "ALTER TABLE users ADD COLUMN x INT", "mysql-bin.000001", 200L);

        consumerWithRealHandlers.onMessage(messageExt(entry));

        assertThat(output).contains("DDL 事件不处理，跳过: seata_user.users");
    }

    @Test
    void skipsMalformedMessageWithoutThrowing(CapturedOutput output) {
        MessageExt ext = new MessageExt();
        ext.setTopic("canal-topic");
        ext.setMsgId("bad-msg");
        ext.setBody("not-a-canal-packet".getBytes());

        consumerWithRealHandlers.onMessage(ext);

        assertThat(output).contains("解析 Canal 消息失败");
    }

    @Test
    void dispatchesBySupportedKeyUsingRouteTable() {
        TableSyncHandler user = mock(TableSyncHandler.class);
        TableSyncHandler order = mock(TableSyncHandler.class);
        when(user.supportedKey()).thenReturn("seata_user.users");
        when(user.idempotent()).thenReturn(true);
        when(user.shouldHandle(any())).thenReturn(true);
        when(order.supportedKey()).thenReturn("seata_order.orders");
        CanalSyncConsumer consumer = new CanalSyncConsumer(new CanalPacketParser(), new CanalEventConverter(),
                List.of(user, order), mock(IdempotencyFacade.class));

        CanalEntry.Entry entry = rowEntry("seata_user", "users", CanalEntry.EventType.INSERT,
                "mysql-bin.000001", 300L,
                List.of(row(null, List.of(col("id", "1", true)))));
        consumer.onMessage(messageExt(entry));

        verify(user).handle(any());
        verify(order, never()).handle(any());
    }

    @Test
    void nonIdempotentHandlerGoesThroughDedupFacadeWithRowKey() {
        TableSyncHandler orderItems = mock(TableSyncHandler.class);
        when(orderItems.supportedKey()).thenReturn("seata_order.order_items");
        when(orderItems.idempotent()).thenReturn(false);
        when(orderItems.shouldHandle(any())).thenReturn(true);
        IdempotencyFacade facade = mock(IdempotencyFacade.class);
        CanalSyncConsumer consumer = new CanalSyncConsumer(new CanalPacketParser(), new CanalEventConverter(),
                List.of(orderItems), facade);

        CanalEntry.Entry entry = rowEntry("seata_order", "order_items", CanalEntry.EventType.INSERT,
                "mysql-bin.000003", 999L,
                List.of(row(null, List.of(col("id", "9", true), col("order_id", "4", false)))));
        consumer.onMessage(messageExt(entry));

        verify(facade).executeWithDedup(any(), anyString(), any(Runnable.class));
        verify(orderItems, never()).handle(any());
    }
}
