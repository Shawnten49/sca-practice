package com.example.order.client;

import com.example.exception.BusinessException;
import com.example.exception.ErrorCode;
import com.sankuai.inf.leaf.IDGen;
import com.sankuai.inf.leaf.common.Result;
import com.sankuai.inf.leaf.common.Status;

/**
 * 本地 SDK 模式实现：直接调用 leaf-core（JVM 内取号，无 HTTP 层）。
 *
 * <p>号段/雪花两个 IDGen 由 {@code LeafLocalConfig} 装配并完成 init；
 * 本类只负责 key 校验、结果状态检查与统一异常口径。
 */
public class LocalLeafIdGenerator implements LeafIdGenerator {

    private final IDGen segmentIdGen;
    private final IDGen snowflakeIdGen;

    public LocalLeafIdGenerator(IDGen segmentIdGen, IDGen snowflakeIdGen) {
        this.segmentIdGen = segmentIdGen;
        this.snowflakeIdGen = snowflakeIdGen;
    }

    @Override
    public long segmentId(String key) {
        return getId(segmentIdGen, key);
    }

    @Override
    public long snowflakeId(String key) {
        return getId(snowflakeIdGen, key);
    }

    private long getId(IDGen idGen, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key 不能为空");
        }
        Result result = idGen.get(key);
        if (result == null || result.getStatus() != Status.SUCCESS) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "ID 服务返回异常");
        }
        return result.getId();
    }
}
