package com.example.stock.service;

import com.example.exception.BusinessException;
import com.example.exception.ErrorCode;
import com.example.exception.InsufficientStockException;
import com.example.converter.StockConverter;
import com.example.dto.response.StockResponse;
import com.example.entity.Stock;
import com.example.stock.mapper.StockMapper;
import org.springframework.stereotype.Service;

@Service
public class StockService {

    private final StockMapper stockMapper;
    private final StockConverter stockConverter;

    public StockService(StockMapper stockMapper, StockConverter stockConverter) {
        this.stockMapper = stockMapper;
        this.stockConverter = stockConverter;
    }

    /**
     * 扣减库存：原子条件更新防超卖（SQL 见 StockMapper.xml）；
     * HTTP 与 Dubbo 两条入口共用这一份逻辑。
     */
    public void deduct(Long productId, Integer count) {
        if (count == null || count <= 0) {
            throw new IllegalArgumentException("count 必须大于 0");
        }
        int updated = stockMapper.deductStock(productId, count);
        if (updated == 0) {
            throw new InsufficientStockException("库存不足");
        }
    }

    public StockResponse query(Long productId) {
        Stock stock = stockMapper.selectStockByProductId(productId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "商品不存在: productId=" + productId));
        return stockConverter.toResponse(stock);
    }
}
