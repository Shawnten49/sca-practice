package com.example.stock.es;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * ES 商品仓储：save 按 id 覆盖（upsert）、deleteById 删除，天然幂等。
 */
public interface ProductRepository extends ElasticsearchRepository<ProductDocument, Long> {
}
