package com.example.order.config;

import com.example.order.dao.LocalOrderMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：LOCAL SqlSessionFactory 必须能加载 mapper-local/*.xml 并把
 * com.example.order.dao 下的接口注册进 MapperRegistry。
 * 否则启动时 LocalOrderMapper 报 "Type ... is not known to the MapperRegistry"。
 */
class LocalSqlSessionFactoryTest {

    @Test
    void localSqlSessionFactoryRegistersDaoMappersFromXml() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:local_mapper_reg;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");

        SqlSessionFactory factory = OrderDataSourceConfig.buildLocalSqlSessionFactory(dataSource, null);

        assertThat(factory.getConfiguration().hasMapper(LocalOrderMapper.class)).isTrue();
        assertThat(factory.getConfiguration()
                .hasStatement("com.example.order.dao.LocalOrderMapper.insertOrder")).isTrue();
    }
}
