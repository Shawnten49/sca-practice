package com.example.stock.mqconsumer.canal;
import com.example.dto.canal.CanalEvent;
import com.example.dto.canal.CanalMessage;

import com.example.stock.es.ProductDocument;
import com.example.stock.es.ProductRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ProductCanalHandler 单元测试：CanalMessage → ES 文档映射与 upsert/delete 行为。
 */
class ProductCanalHandlerTest {

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductCanalHandler handler = new ProductCanalHandler(productRepository);

    @Test
    void insertEventSavesDocumentWithMappedFields() {
        CanalMessage message = canalMessage("INSERT", Map.of(
                "id", "100",
                "name", "华为 Mate 70 Pro",
                "brand", "华为",
                "price", "6999.00",
                "description", "麒麟芯片旗舰手机",
                "create_time", "2026-08-18 10:00:00"));

        handler.handle(message);

        verify(productRepository).save(any(ProductDocument.class));
        verify(productRepository, never()).deleteById(any());
    }

    @Test
    void updateEventSavesDocumentWithMappedFields() {
        CanalMessage message = canalMessage("UPDATE", Map.of(
                "id", "100",
                "name", "华为 Mate 70 Pro 512G",
                "brand", "华为",
                "price", "7999.00",
                "description", "麒麟芯片旗舰手机",
                "create_time", "2026-08-18 10:00:00"));

        handler.handle(message);

        verify(productRepository).save(any(ProductDocument.class));
    }

    @Test
    void deleteEventDeletesById() {
        CanalMessage message = canalMessage("DELETE", Map.of("id", "100"));

        handler.handle(message);

        verify(productRepository).deleteById(100L);
        verify(productRepository, never()).save(any());
    }

    @Test
    void supportedKeysContainsProductRoute() {
        assertThat(handler.supportedKeys()).containsExactly("seata_stock.product");
    }

    private CanalMessage canalMessage(String type, Map<String, Object> row) {
        return new CanalMessage(
                "seata_stock", "product", type, false, 1L,
                "mysql-bin.000001", 100L,
                List.of("id"), List.of(row), null);
    }
}
