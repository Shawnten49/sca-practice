package com.example.task.mapper;

import com.example.entity.Order;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderShardMapperTest {

    private static final String[] DDL = {
            "CREATE TABLE orders_0 ("
                    + "id BIGINT PRIMARY KEY,"
                    + "user_id BIGINT NOT NULL,"
                    + "product_id BIGINT NOT NULL,"
                    + "count INT NOT NULL,"
                    + "create_time DATETIME NOT NULL)",
            "INSERT INTO orders_0 VALUES (1, 101, 201, 2, '2026-08-18 10:00:00')",
            "INSERT INTO orders_0 VALUES (2, 102, 202, 1, '2026-08-18 11:00:00')",
            "INSERT INTO orders_0 VALUES (3, 103, 203, 3, '2026-08-18 13:00:00')",
            "INSERT INTO orders_0 VALUES (4, 104, 204, 5, '2026-08-15 09:00:00')"
    };

    @Test
    void cursorPaginationAdvancesByLastId() throws Exception {
        SqlSessionFactory factory = H2MapperFactory.create(DDL);
        try (SqlSession session = factory.openSession()) {
            OrderShardMapper mapper = session.getMapper(OrderShardMapper.class);
            LocalDateTime cutoff = LocalDateTime.of(2026, 8, 17, 0, 0);

            List<Order> page1 = mapper.selectRecent("orders_0", cutoff, 0L, 2);
            assertThat(page1).extracting(Order::getId).containsExactly(1L, 2L);

            List<Order> page2 = mapper.selectRecent("orders_0", cutoff, 2L, 2);
            assertThat(page2).extracting(Order::getId).containsExactly(3L);

            assertThat(mapper.selectRecent("orders_0", cutoff, 3L, 2)).isEmpty();
        }
    }

    @Test
    void excludesRowsOlderThanCutoff() throws Exception {
        SqlSessionFactory factory = H2MapperFactory.create(DDL);
        try (SqlSession session = factory.openSession()) {
            OrderShardMapper mapper = session.getMapper(OrderShardMapper.class);
            LocalDateTime cutoff = LocalDateTime.of(2026, 8, 18, 12, 0);

            List<Order> recent = mapper.selectRecent("orders_0", cutoff, 0L, 10);
            assertThat(recent).extracting(Order::getId).containsExactly(3L);
        }
    }
}
