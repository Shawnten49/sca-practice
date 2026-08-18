package com.example.stock.service;

import com.example.exception.BusinessException;
import com.example.exception.ErrorCode;
import com.example.id.SnowflakeIdGenerator;
import com.example.converter.ProductConverter;
import com.example.stock.dto.request.ProductCreateRequest;
import com.example.dto.response.ProductResponse;
import com.example.entity.Product;
import com.example.stock.es.ProductDocument;
import com.example.stock.mapper.ProductMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品服务：
 * - 保存：只写 MySQL（雪花 id），ES 由 Canal 异步同步（最终一致）；
 * - 按名称查询：主路径 ES match（ik_smart 查询分词）；ES 故障时降级 MySQL LIKE 并告警。
 */
@Slf4j
@Service
public class ProductService {

    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_BRAND_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 512;

    private final ProductMapper productMapper;
    private final ElasticsearchOperations operations;
    private final ProductConverter productConverter;

    /** 本机单实例固定 machineId；多实例部署时改为配置注入，避免雪花冲突 */
    private final SnowflakeIdGenerator idGenerator = new SnowflakeIdGenerator(4L);

    public ProductService(ProductMapper productMapper, ElasticsearchOperations operations,
                          ProductConverter productConverter) {
        this.productMapper = productMapper;
        this.operations = operations;
        this.productConverter = productConverter;
    }

    /** 保存商品：雪花 id + 显式列插入，create_time 由 DB 填充，插入后回查返回完整商品。 */
    public ProductResponse save(ProductCreateRequest request) {
        validate(request);
        Product product = Product.builder()
                .id(idGenerator.nextId())
                .name(request.name().trim())
                .brand(request.brand().trim())
                .price(request.price())
                .description(request.description() == null ? "" : request.description().trim())
                .build();
        productMapper.insertProduct(product);
        Product saved = productMapper.selectProductById(product.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "product not found after save: " + product.getId()));
        return productConverter.toResponse(saved);
    }

    /** 按名称查询：ES match 主路径，异常降级 MySQL LIKE。 */
    public List<ProductResponse> searchByName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        String keyword = name.trim();
        try {
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.match(m -> m.field("name").query(keyword)))
                    .build();
            return operations.search(query, ProductDocument.class).stream()
                    .map(SearchHit::getContent)
                    .map(this::toProduct)
                    .map(productConverter::toResponse)
                    .toList();
        } catch (Exception e) {
            log.warn("ES 查询失败，降级 MySQL LIKE: name={}", keyword, e);
            return productMapper.selectByNameLike(keyword).stream()
                    .map(productConverter::toResponse)
                    .toList();
        }
    }

    private void validate(ProductCreateRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (request.name().length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name 长度不能超过 " + MAX_NAME_LENGTH);
        }
        if (request.brand() == null || request.brand().isBlank()) {
            throw new IllegalArgumentException("brand 不能为空");
        }
        if (request.brand().length() > MAX_BRAND_LENGTH) {
            throw new IllegalArgumentException("brand 长度不能超过 " + MAX_BRAND_LENGTH);
        }
        if (request.price() == null || request.price().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("price 不能为空且必须 >= 0");
        }
        if (request.description() != null && request.description().length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("description 长度不能超过 " + MAX_DESCRIPTION_LENGTH);
        }
    }

    private Product toProduct(ProductDocument doc) {
        return Product.builder()
                .id(doc.getId())
                .name(doc.getName())
                .brand(doc.getBrand())
                .price(BigDecimal.valueOf(doc.getPrice()))
                .description(doc.getDescription())
                .createTime(doc.getCreateTime())
                .build();
    }
}
