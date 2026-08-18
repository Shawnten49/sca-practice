package com.example.api.dto;

/**
 * 库存扣减 RPC 结果契约。
 * 语义约定：业务失败仍以异常形式抛出（Seata 全局事务依赖异常回滚），
 * 成功返回本对象。
 */
public record StockDeductResult(boolean success, String message) {

    public static StockDeductResult ok() {
        return new StockDeductResult(true, "扣减成功");
    }
}
