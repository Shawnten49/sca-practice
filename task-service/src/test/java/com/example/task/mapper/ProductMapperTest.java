package com.example.task.mapper;

import com.example.entity.Product;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMapperTest {

    @Test
    void shardTimeRangeAndCursorPagination() throws Exception {
        SqlSessionFactory factory = H2MapperFactory.create(
                "CREATE TABLE product (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, "
                        + "brand VARCHAR(64) NOT NULL, price DECIMAL(10,2) NOT NULL, "
                        + "description VARCHAR(512) NOT NULL DEFAULT '', create_time DATETIME NOT NULL)",
                "INSERT INTO product VALUES (1, 'p1', 'b1', 10.50, '', '2026-08-18 10:00:00')",
                "INSERT INTO product VALUES (2, 'p2', 'b2', 20.00, '', '2026-08-18 11:00:00')",
                "INSERT INTO product VALUES (3, 'p3', 'b3', 30.00, '', '2026-08-18 12:00:00')",
                "INSERT INTO product VALUES (4, 'p4', 'b4', 40.00, '', '2026-08-18 13:00:00')",
                "INSERT INTO product VALUES (5, 'p5', 'b5', 50.00, '', '2026-08-15 09:00:00')");

        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 17, 0, 0);
        try (SqlSession session = factory.openSession()) {
            ProductMapper mapper = session.getMapper(ProductMapper.class);

            // shardTotal=3, shardIndex=1 → MOD(id,3)=1 → 1,4
            List<Product> page1 = mapper.selectRecentByShard(cutoff, 0L, 1, 3, 1);
            assertThat(page1).extracting(Product::getId).containsExactly(1L);

            List<Product> page2 = mapper.selectRecentByShard(cutoff, 1L, 1, 3, 1);
            assertThat(page2).extracting(Product::getId).containsExactly(4L);

            assertThat(mapper.selectRecentByShard(cutoff, 4L, 1, 3, 1)).isEmpty();

            // shardIndex=0 → MOD(id,3)=0 → 3（id=5 超时被过滤）
            List<Product> zero = mapper.selectRecentByShard(cutoff, 0L, 0, 3, 10);
            assertThat(zero).extracting(Product::getId).containsExactly(3L);
        }
    }
}
