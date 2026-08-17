package com.example.order.transaction;

import org.apache.shardingsphere.transaction.spi.ShardingSphereDistributedTransactionManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：ShardingSphere 构建事务规则时（transaction.defaultType: BASE）会通过
 * TypedSPILoader 预加载全部 ShardingSphereDistributedTransactionManager SPI provider，
 * 其中 XA 实现类引用 javax.transaction.*（JDK 11+ 已移除）。
 * 若 classpath 缺少 JTA API，ServiceLoader 实例化 XA provider 会直接抛
 * NoClassDefFoundError: javax/transaction/SystemException，导致启动失败。
 */
class TransactionManagerSpiLoadTest {

    @Test
    void allDistributedTransactionManagerProvidersLoadable() {
        List<String> providerTypes = new ArrayList<>();
        ServiceLoader.load(ShardingSphereDistributedTransactionManager.class)
                .forEach(provider -> providerTypes.add(String.valueOf(provider.getType())));

        // XA 对应 getType()=XA；Seata AT 集成对外注册的事务类型是 BASE（providerType=Seata 为其内部标识）
        assertThat(providerTypes).contains("XA", "BASE");
    }
}
