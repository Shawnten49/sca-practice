package com.example.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Leaf 服务连接配置（application.yml 的 leaf 段）。 */
@ConfigurationProperties(prefix = "leaf")
public class LeafProperties {

    /** 调用模式：http（默认，独立 Leaf 服务）| local（本地 SDK，leaf-core） */
    private String mode = "http";

    /** Leaf 服务地址 */
    private String url = "http://127.0.0.1:8085";

    /** 连接超时 */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** 读取超时 */
    private Duration readTimeout = Duration.ofSeconds(3);

    /** 本地 SDK 模式配置（leaf.local 段） */
    private Local local = new Local();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Local getLocal() {
        return local;
    }

    public void setLocal(Local local) {
        this.local = local;
    }

    /** 本地 SDK 模式：号段用专用数据源连 leaf 库，雪花用 ZooKeeper 分配 workerId。 */
    public static class Local {

        /** leaf 号段库 JDBC 地址 */
        private String jdbcUrl;

        private String username;

        private String password;

        /** ZooKeeper 地址（雪花模式） */
        private String zkAddress;

        /** 本服务端口，用于雪花模式在 ZK 注册节点标识 */
        private int port = 8083;

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getZkAddress() {
            return zkAddress;
        }

        public void setZkAddress(String zkAddress) {
            this.zkAddress = zkAddress;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }
}
