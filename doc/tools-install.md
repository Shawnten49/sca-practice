# 开发环境工具安装说明（macOS）

> 适用范围：sca-practice 项目本地开发环境。按"先基础、后核心、再进阶"组织：JDK / Maven → MySQL / Nacos / Redis / RocketMQ / Sentinel Dashboard / Seata Server（核心六件套）→ RocketMQ Dashboard → 一键启动脚本 → 进阶可选组件 → 端口速查。
>
> 所有版本与命令均按本机（macOS + Apple Silicon，`/opt/homebrew`）实测为准；Intel 机器把路径换成 `/usr/local` 即可。脚本统一放在项目根目录的 `scripts/` 下，以下命令默认在项目根目录执行。

## 1. 总览表

| 软件 | 本机版本 | 核心端口 | 安装路径 | 一键脚本 |
|---|---|---|---|---|
| JDK | Temurin 21.0.12 LTS | — | brew / 官方安装包 | — |
| Maven | 3.9.16 | — | `~/tools/maven` | — |
| MySQL | 8.4.11 | 3306 | brew（mysql@8.4） | `brew services` |
| Nacos | 3.0.3 | 8848 API / 8847 控制台 / 9848 gRPC | `~/tools/nacos` | `scripts/start-nacos.sh` |
| Redis | 8.10.0 | 6379 | brew（redis） | `brew services` |
| RocketMQ | 5.5.0 | 9876 NameServer / 10911 Broker | `~/tools/rocketmq-5.5.0` | `scripts/start-rocketmq.sh` |
| RocketMQ Dashboard | 2.1.0 | 7070 | `~/tools/rocketmq-dashboard` | `scripts/start-rocketmq-dashboard.sh` |
| Sentinel Dashboard | 1.8.9 | 8858 | `~/tools/sentinel` | `scripts/start-sentinel.sh` |
| Seata Server | 2.6.0 | 8091 | `~/tools/seata/apache-seata-2.6.0-*/seata-server` | `scripts/start-seata-server.sh` |

## 2. 基础环境：JDK + Maven

- **JDK 21（Temurin LTS）**：项目基于 Spring Boot 3.5.x / SCA 2025.0.x，需要 JDK 17+，推荐 21。安装后验证 `java -version`；
- **Maven 3.9.x**：解压到 `~/tools/maven` 后配置 `MAVEN_HOME` / PATH，验证 `mvn -v`；
- **建议配置阿里云镜像**（国内下载依赖快）：编辑 `~/.m2/settings.xml`，mirror 指向 `https://maven.aliyun.com/repository/public`；
- **IntelliJ 提示**：打开项目必须用 pom.xml 以 Maven 项目方式导入（不要直接 Open 文件夹），否则 Java 类会报"程序包不存在"。

## 3. MySQL 8.4

```bash
# 1. 安装（brew）
brew install mysql@8.4

# 2. 启动 + 开机自启
brew services start mysql@8.4

# 3. 验证（本机 root 无密码）
/opt/homebrew/opt/mysql@8.4/bin/mysql -u root -e "SELECT VERSION();"
```

- **注意 keg-only**：mysql@8.4 不会自动进 PATH，用完整路径或执行 `echo 'export PATH="/opt/homebrew/opt/mysql@8.4/bin:$PATH"' >> ~/.zshrc`；
- 配置 / 数据目录：`/opt/homebrew/etc/my.cnf`、`/opt/homebrew/var/mysql`；
- 业务服务连接串里加 `createDatabaseIfNotExist=true`，启动服务时会自动建库（项目使用 seata_order / seata_stock / seata_user 三个库）；
- **常见坑**：本地密码为空时连接串写 `password: ""`；生产不要沿用无密码 root。

## 4. Nacos 3.0.3（注册中心 + 配置中心）

```bash
# 1. 下载（3.x 需要 JDK 17+）
cd ~/tools
curl -L -o nacos-server-3.0.3.zip \
  https://github.com/alibaba/nacos/releases/download/3.0.3/nacos-server-3.0.3.zip
unzip nacos-server-3.0.3.zip && mv nacos ~/tools/nacos

# 2. 启动（单机模式；推荐用项目脚本）
bash scripts/start-nacos.sh

# 3. 验证
curl http://127.0.0.1:8848/nacos/v1/console/health/readiness   # 返回 ok
# 控制台: http://127.0.0.1:8847/nacos  默认账号 nacos / nacos（首次登录改密）
```

- **端口说明**：API 主端口 **8848**；gRPC 通信端口 **9848/9849**（= 8848+1000/+1001）；Web 控制台 3.x 默认 **8080**，本机已改为 **8847**（配置文件 `conf/application.properties` 的 `nacos.console.port`）；
- **鉴权**：3.x 默认开启，需在 `conf/application.properties` 配好 `nacos.core.auth.plugin.nacos.token.secret.key`（Base64 密钥，不能为空——为空会导致启动脚本交互式卡住）等 3 个鉴权属性，并初始化 admin 密码；
- **数据存储**：单机模式配置存内嵌 Derby（`data/derby-data`），不需要 MySQL；集群/生产再连外部数据库；
- **常见坑**：`9848 Address already in use` 是旧 Nacos 进程还占着端口；停止请用 `bin/shutdown.sh`，不要靠删目录。

## 5. Redis 8.10

```bash
# 1. 安装 + 启动
brew install redis
brew services start redis

# 2. 验证
redis-cli ping    # 返回 PONG
```

- 配置：`/opt/homebrew/etc/redis.conf`；数据目录：`/opt/homebrew/var/db/redis`；
- **常见坑**：`brew services start` 提示成功但 `redis-cli ping` 报 Connection refused——说明进程实际没起来（配置错误或模块加载失败），用 `brew services info redis` 和 `/opt/homebrew/var/log/redis.log` 查原因，修好配置后 `brew services restart redis`；本机曾因 redisbloom 模块加载问题导致启动失败，已在 redis.conf 中修正加载项；
- 生产环境建议设置 `requirepass` 并避免公网暴露。

## 6. RocketMQ 5.5.0（NameServer + Broker）

```bash
# 1. 下载解压到 ~/tools/rocketmq-5.5.0
# 2. 启动（推荐用项目脚本，自动等端口就绪）
bash scripts/start-rocketmq.sh

# 3. 验证
~/tools/rocketmq-5.5.0/bin/mqadmin clusterList -n 127.0.0.1:9876   # 能看到 broker 即 OK
```

- 端口：NameServer **9876**，Broker **10911**（另有 10909 VIP 通道、10912 HA 端口）；
- 日志：`~/logs/rocketmqlogs/namesrv.log`、`broker.log`；Broker 数据默认在 `~/store`；
- **启动顺序铁律**：先 NameServer（9876 就绪）再 Broker——脚本已内置该顺序；
- 常见坑：默认启动脚本 JVM 给的内存较大，小内存机器可调 `bin/runserver.sh` / `runbroker.sh` 的 `JAVA_OPT`。

## 7. RocketMQ Dashboard 2.1.0（管理后台）

```bash
# 需要先编译（arm64 机器注意 node 二进制 Bad CPU type 问题，见下方要点）
cd ~/tools/rocketmq-dashboard
mvn clean package -Dmaven.test.skip=true

# 启动（推荐用项目脚本）
bash scripts/start-rocketmq-dashboard.sh
# 访问 http://127.0.0.1:7070 查看 Topic / 消费积压 / 集群列表
```

- 本机监听 **7070**（源码默认 8080、前端页面写死 8082，均已改为 7070）；
- **arm64 编译要点**：`frontend-maven-plugin` 下载的 node 二进制与 CPU 架构不匹配会报 `Bad CPU type in executable (86)`，处理方式是把本机 arm64 版 node 放进 `target/node/` 覆盖，或让插件使用系统 node；
- 页面报 `Failed to fetch broker history data / cluster list` 时先检查 NameServer/Broker 是否在线。

## 8. Sentinel Dashboard 1.8.9（流量治理控制台）

```bash
# 1. 下载 jar（不发布到 Maven 中央仓库，走 GitHub Release）
mkdir -p ~/tools/sentinel
curl -L -o ~/tools/sentinel/sentinel-dashboard-1.8.9.jar \
  https://github.com/alibaba/Sentinel/releases/download/1.8.9/sentinel-dashboard-1.8.9.jar

# 2. 启动（推荐用项目脚本）
bash scripts/start-sentinel.sh

# 3. 访问 http://127.0.0.1:8858  默认账号 sentinel / sentinel
```

- 端口：Dashboard **8858**；业务服务通过 **8719**（transport 端口）上报，客户端配置 `spring.cloud.sentinel.transport.dashboard=127.0.0.1:8858`；
- 版本必须与客户端（BOM 管理的 `spring-cloud-starter-alibaba-sentinel` 1.8.9）一致；
- 规则默认只存在内存，重启丢失——项目使用 Nacos 数据源持久化规则（`sentinel-datasource-nacos`）。

## 9. Seata Server 2.6.0（分布式事务 TC）

```bash
# 安装目录
~/tools/seata/apache-seata-2.6.0-incubating-bin/seata-server

# 启动（推荐用项目脚本；已运行会先重启）
bash scripts/start-seata-server.sh

# 验证：8091 监听 + Nacos 服务列表出现 seata-server 实例
```

- 端口：**8091**；注册中心/配置中心都用 Nacos（配置 dataId `seataServer.properties`，DEFAULT_GROUP）；
- 存储：DB 模式，MySQL 建 `seata` 库 + global_table / branch_table / lock_table / distributed_lock 四张表；Server lib 需手动放 `mysql-connector-j-8.4.0.jar`（官方包不带驱动）；
- **常见坑**：`8091 Address already in use` 是端口被旧进程占用；"假死"（端口在、连不上）多与线程池/连接池状态有关，重启即恢复——生产上盯日志与告警。

## 10. 一键启动 / 状态检测脚本

脚本在项目根目录 `scripts/` 下，全部支持"已运行则重启、未运行则启动"，并轮询端口确认就绪：

| 脚本 | 作用 | 验证端口 |
|---|---|---|
| `scripts/start-nacos.sh` | Nacos 3.0.3 启动/重启 | 8848 / 8847 |
| `scripts/start-rocketmq.sh` | mqnamesrv + mqbroker 启动/重启（顺序保证） | 9876 / 10911 |
| `scripts/start-rocketmq-dashboard.sh` | Dashboard 2.1.0 启动/重启 | 7070 |
| `scripts/start-sentinel.sh` | Sentinel Dashboard 1.8.9 启动/重启 | 8858 |
| `scripts/start-seata-server.sh` | Seata Server 2.6.0 启动/重启 | 8091 |
| `scripts/check-services.sh` | 检测 Nacos/RocketMQ/Seata/Sentinel 状态，未启动则询问是否拉起 | — |

```bash
# 状态检测（交互式询问是否启动未运行的服务）
bash scripts/check-services.sh

# 免交互用法
bash scripts/check-services.sh -y   # 自动启动所有未启动的服务
bash scripts/check-services.sh -n   # 只检测不启动
```

各脚本支持环境变量覆盖默认值（如 `NACOS_HOME`、`ROCKETMQ_HOME`、`SEATA_HOME`、`SENTINEL_HOME`、`DASHBOARD_HOME`、端口变量），换目录/换端口不用改脚本。

## 11. 进阶组件（用到再装）

| 组件 | 版本 | 安装要点 |
|---|---|---|
| Elasticsearch | 8.18.x | tar 解压到 `~/tools/elasticsearch`；单机模式 `discovery.type: single-node` + 关闭安全认证；IK 分词插件版本必须与 ES 完全一致 |
| MongoDB | 8.0.x | `brew tap mongodb/brew && brew install mongodb-community@8.0`，`brew services start`，mongosh 验证 |
| XXL-JOB | 3.4.2 | 源码 mvn 打包；MySQL 建 `xxl_job` 库执行建表脚本；改 admin 数据源后 `java -jar xxl-job-admin`，端口 8080 |
| Prometheus | 3.13.x | `brew install prometheus`；`brew services start prometheus`；配置 `/opt/homebrew/etc/prometheus.yml` 加抓取任务，端口 9090 |
| Grafana | 13.x | `brew install grafana`；`brew services start grafana`；首次登录 admin/admin 改密，端口 3000 |
| Canal | 1.1.8 | tar 解压 canal.deployer；配置 MySQL binlog（ROW 模式）+ 监听表；对接 RocketMQ topic |
| JMeter | 5.6.3 | tar 解压即可用（路径不要含中文空格）；GUI 建场景，命令行 `jmeter -n -t xxx.jmx` 压测 |
| k6 | 2.x | `brew install k6`，脚本化压测，CI 友好 |

## 12. 端口速查表

| 端口 | 服务 | 备注 |
|---|---|---|
| 3306 | MySQL | 业务库 + Seata/XXL-JOB 元数据 |
| 6379 | Redis | 缓存 / 分布式锁 |
| 8848 | Nacos API | 注册/配置中心主端口 |
| 9848 / 9849 | Nacos gRPC | 8848+1000 / +1001，客户端通信 |
| 8847 | Nacos 控制台 | 3.x 默认 8080，本机已改 8847 |
| 9876 | RocketMQ NameServer | — |
| 10911 / 10909 / 10912 | RocketMQ Broker | 主端口 / VIP / HA |
| 7070 | RocketMQ Dashboard | — |
| 8858 / 8719 | Sentinel Dashboard / 客户端上报 | — |
| 8091 | Seata Server | — |
| 8081 / 8083 / 8084 / 8088 | user / order / stock / gateway 服务 | 项目业务服务端口 |
| 20880 | Dubbo provider | stock-service |
| 22222 / 22223 | Dubbo QoS | stock / order |
| 9200 | Elasticsearch | 进阶 |
| 27017 | MongoDB | 进阶 |
| 3000 / 9090 | Grafana / Prometheus | 进阶 |
| 8080 | XXL-JOB Admin | 进阶 |

## 13. 补充说明

- 本机约定：所有中间件统一放在 `~/tools/` 下，日志统一在 `~/logs/`；换机器时保持该目录约定，脚本即可直接复用；
- 脚本用 `bash xxx.sh` 执行即可（已带 `#!/usr/bin/env bash` 和可执行权限）；
- 业务服务的数据库连接串、Nacos/Seata 地址等见各服务 `src/main/resources/application.yml`。
