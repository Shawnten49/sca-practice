package com.example.stock.es;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * 启动时幂等创建商品索引（含 ik 分词 mapping）。
 * mapping 变更需重建索引：先删旧索引再启动（学习环境），生产应走 alias 重建。
 */
@Slf4j
@Component
public class ProductIndexInitializer implements ApplicationRunner {

    private final ElasticsearchOperations operations;

    public ProductIndexInitializer(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    @Override
    public void run(ApplicationArguments args) {
        IndexOperations indexOps = operations.indexOps(ProductDocument.class);
        if (indexOps.exists()) {
            log.info("ES 索引已存在，跳过创建: product");
            return;
        }
        indexOps.create();
        indexOps.putMapping(indexOps.createMapping(ProductDocument.class));
        log.info("ES 索引创建完成: product（name 使用 ik_max_word / ik_smart）");
    }
}
