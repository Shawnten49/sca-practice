package com.example.stock.mqconsumer.canal;

import com.example.stock.es.ProductDocument;
import com.example.stock.es.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/**
 * product 表变更处理（key: seata_stock.product）→ 同步 ES。
 * INSERT/UPDATE → 按 id upsert；DELETE → 按 id 删除；ES 操作天然幂等（at-least-once 无副作用）。
 */
@Slf4j
@Component
public class ProductCanalHandler implements TableSyncHandler {

    static final Set<String> KEYS = Set.of("seata_stock.product");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ProductRepository productRepository;

    public ProductCanalHandler(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public Set<String> supportedKeys() {
        return KEYS;
    }

    @Override
    public void handle(CanalMessage message) {
        if ("DELETE".equalsIgnoreCase(message.type())) {
            handleDelete(message);
            return;
        }
        for (Map<String, Object> row : message.data()) {
            productRepository.save(toDocument(row));
        }
    }

    private void handleDelete(CanalMessage message) {
        for (Map<String, Object> row : message.data()) {
            Object idValue = row.get("id");
            if (idValue == null) {
                log.warn("DELETE 事件缺少主键 id，跳过: {}", message.routeKey());
                continue;
            }
            productRepository.deleteById(Long.valueOf(String.valueOf(idValue)));
        }
    }

    /** Canal 列值均为字符串（NULL 为 null），字段名 snake_case → 文档字段。 */
    private ProductDocument toDocument(Map<String, Object> row) {
        return ProductDocument.builder()
                .id(Long.valueOf(str(row.get("id"))))
                .name(str(row.get("name")))
                .brand(str(row.get("brand")))
                .price(new BigDecimal(str(row.get("price"))).doubleValue())
                .description(str(row.get("description")))
                .createTime(parseCreateTime(row.get("create_time")))
                .build();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private LocalDateTime parseCreateTime(Object value) {
        if (value == null) {
            return LocalDateTime.now();
        }
        return LocalDateTime.parse(String.valueOf(value), DATE_TIME_FORMATTER);
    }
}
