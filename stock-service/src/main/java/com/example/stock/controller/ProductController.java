package com.example.stock.controller;

import com.example.common.Result;
import com.example.stock.domain.Product;
import com.example.stock.dto.ProductSaveRequest;
import com.example.stock.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 商品接口：保存写 MySQL（Canal 异步同步 ES）；按名称查询走 ES。 */
@Tag(name = "商品", description = "商品保存与按名称查询")
@RestController
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping("/product/save")
    @Operation(summary = "保存商品（MySQL 落库，Canal 异步同步 ES）")
    public Result<Product> save(@RequestBody ProductSaveRequest request) {
        return Result.ok(productService.save(request));
    }

    @GetMapping("/product")
    @Operation(summary = "按商品名称查询（ES 全文检索）")
    public Result<List<Product>> query(@RequestParam String name) {
        return Result.ok(productService.searchByName(name));
    }
}
