package com.example.order.config;

import com.example.order.client.LeafIdGenerator;
import com.example.order.client.LocalLeafIdGenerator;
import com.sankuai.inf.leaf.IDGen;
import com.sankuai.inf.leaf.segment.SegmentIDGenImpl;
import com.sankuai.inf.leaf.segment.dao.IDAllocDao;
import com.sankuai.inf.leaf.segment.dao.impl.IDAllocDaoImpl;
import com.sankuai.inf.leaf.snowflake.SnowflakeIDGenImpl;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地 SDK 模式装配：专用 leaf 数据源 + leaf-core 号段/雪花实例。
 *
 * <p>DB/ZK 不可用时会启动失败（快速失败，不做静默降级）。
 * 注意：leaf 专用数据源<strong>不注册为 Spring Bean</strong>，
 * 否则会顶掉 spring.datasource 自动装配的主数据源（导致 Flyway/MyBatis 误连 leaf 库）。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "leaf.mode", havingValue = "local")
public class LeafLocalConfig {

    @Bean
    public LeafIdGenerator localLeafIdGenerator(LeafProperties leafProperties) {
        LeafProperties.Local local = leafProperties.getLocal();
        HikariDataSource leafDataSource = buildLeafDataSource(local);
        try {
            // 号段：SegmentIDGenImpl + MyBatis DAO（与 leaf-server 的 SegmentService 相同装配）
            IDAllocDao dao = new IDAllocDaoImpl(leafDataSource);
            SegmentIDGenImpl segmentIdGen = new SegmentIDGenImpl();
            segmentIdGen.setDao(dao);
            initOrFail("segment", segmentIdGen);

            // 雪花：ZooKeeper 分配 workerId（port 用于注册节点标识）
            SnowflakeIDGenImpl snowflakeIdGen = new SnowflakeIDGenImpl(local.getZkAddress(), local.getPort());
            initOrFail("snowflake", snowflakeIdGen);

            return new LocalLeafIdGenerator(segmentIdGen, snowflakeIdGen);
        } catch (RuntimeException e) {
            leafDataSource.close();
            throw e;
        }
    }

    private HikariDataSource buildLeafDataSource(LeafProperties.Local local) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setJdbcUrl(local.getJdbcUrl());
        dataSource.setUsername(local.getUsername());
        dataSource.setPassword(local.getPassword());
        dataSource.setMaximumPoolSize(5);
        dataSource.setMinimumIdle(1);
        return dataSource;
    }

    private void initOrFail(String mode, IDGen idGen) {
        if (!idGen.init()) {
            throw new IllegalStateException("Leaf " + mode + " 本地初始化失败，请检查 leaf.local 配置（DB/ZK）");
        }
        log.info("Leaf {} 本地初始化成功", mode);
    }
}
