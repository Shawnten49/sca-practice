package com.example.stock.service;

import com.example.stock.converter.ProductConverter;
import com.example.stock.dto.response.ProductResponse;
import com.example.entity.Product;
import com.example.stock.dto.request.ProductCreateRequest;
import com.example.stock.es.ProductDocument;
import com.example.stock.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProductService 单元测试：保存（雪花 id + 入库）、按名称查询（ES 主路径 + MySQL 降级）。
 */
class ProductServiceTest {

    private ProductMapper productMapper;
    private ElasticsearchOperations operations;
    private ProductService productService;

    @BeforeEach
    void setUp() {
        productMapper = mock(ProductMapper.class);
        operations = mock(ElasticsearchOperations.class);
        productService = new ProductService(productMapper, operations, ProductConverter.INSTANCE);
    }

    @Test
    void saveInsertsWithSnowflakeIdAndReturnsRequeriedProduct() {
        ProductCreateRequest request = new ProductCreateRequest(" 华为 Mate 70 Pro ", "华为",
                new BigDecimal("6999.00"), " 麒麟芯片旗舰手机 ");
        when(productMapper.selectProductById(any(Long.class)))
                .thenAnswer(invocation -> Optional.of(Product.builder()
                        .id(invocation.getArgument(0))
                        .name("华为 Mate 70 Pro").brand("华为")
                        .price(new BigDecimal("6999.00")).description("麒麟芯片旗舰手机")
                        .createTime(LocalDateTime.now())
                        .build()));

        ProductResponse saved = productService.save(request);

        verify(productMapper).insertProduct(any(Product.class));
        assertThat(saved.id()).isNotNull();
        assertThat(saved.name()).isEqualTo("华为 Mate 70 Pro");
        assertThat(saved.description()).isEqualTo("麒麟芯片旗舰手机");
    }

    @Test
    void saveRejectsBlankName() {
        ProductCreateRequest request = new ProductCreateRequest(" ", "华为", new BigDecimal("6999.00"), null);

        assertThatThrownBy(() -> productService.save(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name 不能为空");
    }

    @Test
    void saveRejectsNegativePrice() {
        ProductCreateRequest request = new ProductCreateRequest("华为 Mate 70 Pro", "华为",
                new BigDecimal("-1"), null);

        assertThatThrownBy(() -> productService.save(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price");
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchByNameQueriesEsAndMapsDocuments() {
        ProductDocument doc = ProductDocument.builder()
                .id(100L).name("华为 Mate 70 Pro").brand("华为")
                .price(6999.0).description("麒麟芯片旗舰手机")
                .createTime(LocalDateTime.now())
                .build();
        SearchHit<ProductDocument> hit = mock(SearchHit.class);
        when(hit.getContent()).thenReturn(doc);
        SearchHits<ProductDocument> hits = mock(SearchHits.class);
        when(hits.stream()).thenReturn(Stream.of(hit));
        when(operations.search(any(NativeQuery.class), eq(ProductDocument.class))).thenReturn(hits);

        List<ProductResponse> result = productService.searchByName("华为");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(100L);
        assertThat(result.get(0).name()).isEqualTo("华为 Mate 70 Pro");
        assertThat(result.get(0).price()).isEqualByComparingTo("6999.00");
        verify(operations).search(any(NativeQuery.class), eq(ProductDocument.class));
    }

    @Test
    void searchByNameFallsBackToMySqlWhenEsFails() {
        when(operations.search(any(NativeQuery.class), eq(ProductDocument.class)))
                .thenThrow(new RuntimeException("es down"));
        when(productMapper.selectByNameLike("华为")).thenReturn(List.of(
                Product.builder().id(1L).name("华为 Mate 60").brand("华为")
                        .price(new BigDecimal("4999.00")).build()));

        List<ProductResponse> result = productService.searchByName("华为");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(1L);
        verify(productMapper).selectByNameLike("华为");
    }

    @Test
    void searchByNameRejectsBlankKeyword() {
        assertThatThrownBy(() -> productService.searchByName("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name 不能为空");
    }
}
