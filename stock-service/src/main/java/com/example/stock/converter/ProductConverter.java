package com.example.stock.converter;

import com.example.stock.dto.response.ProductResponse;
import com.example.stock.entity.Product;

/** Product 实体 → ProductResponse 转换。 */
public final class ProductConverter {

    private ProductConverter() {
    }

    public static ProductResponse toResponse(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getBrand(),
                product.getPrice(), product.getDescription(), product.getCreateTime());
    }
}
