package com.example.order.dao;

import com.example.order.entity.Order;

/**
 * 本地事务（LOCAL 数据源）专用订单写入 mapper。
 *
 * <p>与 {@code OrderMapper} 的区别仅在于绑定的数据源：
 * 本接口由 OrderDataSourceConfig 的 @MapperScan 绑定到 localDataSource（transaction.defaultType=LOCAL），
 * 配合 {@code @Transactional("localTransactionManager")} 只做物理库本地事务，不开启 Seata；
 * OrderMapper 绑定 BASE 数据源，配合 {@code @Transactional("seataTransactionManager")}
 * 开启 Seata 全局事务。
 *
 * <p>SQL 由 XML 维护：src/main/resources/mapper-local/LocalOrderMapper.xml
 * （放 mapper-local 目录是为了不被 BASE 工厂的 classpath*:mapper/**&#47;*.xml 扫描到）。
 */
public interface LocalOrderMapper {

    int insertOrder(Order order);
}
