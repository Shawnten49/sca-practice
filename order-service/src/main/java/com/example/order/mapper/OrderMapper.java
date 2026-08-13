package com.example.order.mapper;

import com.example.order.domain.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OrderMapper {

    private final JdbcTemplate jdbcTemplate;

    public OrderMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Order order) {
        jdbcTemplate.update(
                "insert into orders (id, user_id, product_id, count) values (?, ?, ?, ?)",
                order.getOrderId(), order.getUserId(), order.getProductId(), order.getCount());
    }

    public Order selectById(Long id) {
        var results = jdbcTemplate.query(
                "SELECT id, user_id, product_id, count FROM orders WHERE id = ?",
                rowMapper, id);
        return results.isEmpty() ? null : results.get(0);
    }

    private final RowMapper<Order> rowMapper = (rs, rowNum) ->
            new Order(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("product_id"), rs.getInt("count"));
}