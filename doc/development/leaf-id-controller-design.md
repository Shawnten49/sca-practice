# order-service 接入 Leaf：LeafIDController 设计方案

> 需求：order-service 新增 `LeafIDController`，提供两个接口——Leaf 号段模式取 ID、Leaf 雪花算法取 ID。
> 前提：本地已按第 15 课搭建 Leaf 服务（`http://127.0.0.1:8085`，号段模式依赖 MySQL `leaf_alloc` 表，雪花模式依赖 ZooKeeper 2181）。

## 1. 需求概述

- order-service 通过 HTTP 调用 Leaf 服务，对外暴露两个获取分布式 ID 的接口；
- 接口返回统一 `Result<Long>`（sca-common 已有包装，`code=0` 表示成功），错误由全局异常处理器输出 ProblemDetail（RFC 7807）；
- 与项目现有风格一致：不引入新的 RPC 组件（Leaf 未注册进 Nacos，用 RestTemplate 直连最简）。

## 2. 接口设计

| 接口 | 方法/路径 | 参数 | 返回 |
|---|---|---|---|
| 号段模式取 ID | `GET /leaf/segment` | `key`（可选，默认 `order_id`） | `Result<Long>` |
| 雪花模式取 ID | `GET /leaf/snowflake` | `key`（可选，默认 `leaf`） | `Result<Long>` |

说明：

- `key` 即 Leaf 的 `biz_tag`；号段模式的 key 决定从哪条号段取号（`leaf_alloc` 表按 `biz_tag` 区分）；
- 雪花模式的 key 只是 Leaf 接口入参（标识一次调用），不参与 ID 生成，默认给 `leaf`；
- 参数为空时抛 `IllegalArgumentException`，由全局异常处理器转为 400（防止把空 key 透传给 Leaf）；
- 响应示例：
  ```json
  // 成功
  {"code":0,"message":"success","data":1001}

  // 失败（Leaf 不可用/返回异常）：HTTP 500 ProblemDetail
  {"status":500,"detail":"系统繁忙，请稍后再试","code":"INTERNAL_ERROR","timestamp":"..."}
  ```

## 3. 组件设计

```
LeafIDController
   └─ LeafIdClient（@Component，封装 RestTemplate）
         ├─ long segmentId(String key)      → GET {leaf.url}/api/segment/get/{key}
         ├─ long snowflakeId(String key)    → GET {leaf.url}/api/snowflake/get/{key}
         └─ 解析 Leaf 返回的裸数字字符串为 Long，并做合法性校验
   └─ LeafProperties（@ConfigurationProperties("leaf")）
         ├─ url（默认 http://127.0.0.1:8085）
         ├─ connect-timeout（默认 2s）
         └─ read-timeout（默认 3s）
```

**RestTemplate 配置**：在 order-service 新建 `RestTemplateConfig`，用 `SimpleClientHttpRequestFactory` 设置连接/读取超时，防止 Leaf 挂掉时请求长时间悬挂。order-service 目前没有 RestTemplate Bean，本次一并补上（只用于 Leaf 调用，不影响其他逻辑）。

**校验逻辑**（LeafIdClient 内）：

- key 空 → 抛 `IllegalArgumentException`（转 400）；
- Leaf 返回体转 `Long` 失败，或 HTTP 状态非 2xx → 抛 `BusinessException(ErrorCode.INTERNAL_ERROR)`（转 500），并记录 error 日志（含 key、Leaf URL、状态码）；
- 正常返回 `long`。

## 4. 配置

`order-service/src/main/resources/application.yml` 新增：

```yaml
leaf:
  url: http://127.0.0.1:8085
  connect-timeout: 2s
  read-timeout: 3s
```

## 5. 异常与降级策略（明确不做本地降级）

- Leaf 不可用或返回异常 → 快速失败：抛 `BusinessException(INTERNAL_ERROR)`，由全局异常处理器返回 500 ProblemDetail；
- **不**静默降级到项目已有的本地 `SnowflakeIdGenerator`。理由：
  1. ID 源混用（Leaf workerId=0 vs 本地 worker-id=1）虽然位域不同理论上不撞号，但跨源 ID 会破坏"号段连续 / 趋势递增"的一致性语义，后续排查困难；
  2. ID 服务是基础组件，故障应该暴露给调用方与监控（告警、限流降级），而不是悄悄换源掩盖问题；
  3. 如确有降级诉求，应在独立的后续方案中设计（含混源冲突评估与告警），本期不引入。
- 可选扩展（不在本期）：调用失败重试一次、接入 Sentinel 对 Leaf 调用做熔断。

## 6. 测试方案

1. `LeafIdClientTest`（MockRestServiceServer，不依赖真实 Leaf）：
   - Leaf 返回 `"1001"` → 断言返回 `1001L`；
   - Leaf 返回 500 → 断言抛 `BusinessException`；
   - Leaf 返回非数字 → 断言抛 `BusinessException`；
   - key 为空 → 断言抛 `IllegalArgumentException`。
2. `LeafIDControllerTest`（MockMvc + mock `LeafIdClient`）：
   - 断言 `/leaf/segment` 返回 `Result<Long>` 且 `code=0`、`data` 正确；
   - 断言默认 key 行为（不传 key 时用 `order_id` / `leaf`）。
3. 手工验证：启动 Leaf（`bash scripts/start-leaf.sh`）后：
   ```bash
   curl 'http://127.0.0.1:8083/leaf/segment'        # {"code":0,...,"data":1006}
   curl 'http://127.0.0.1:8083/leaf/snowflake'      # {"code":0,...,"data":2089...}
   ```

## 7. 实现步骤（方案通过后执行）

1. `application.yml` 增加 `leaf.*` 配置；
2. 新增 `config/LeafProperties`、`config/RestTemplateConfig`、`client/LeafIdClient`、`controller/LeafIDController`；
3. 新增 `LeafIdClientTest`、`LeafIDControllerTest`；
4. `mvn -pl order-service -am test` 全量通过；
5. 手工 curl 验证两个接口（含 Leaf 停止时的 500 场景）。

## 8. 涉及文件

| 文件 | 说明 |
|---|---|
| `order-service/src/main/resources/application.yml` | 新增 `leaf.*` 配置 |
| `order-service/.../config/LeafProperties.java` | Leaf 连接配置 |
| `order-service/.../config/RestTemplateConfig.java` | RestTemplate（带超时） |
| `order-service/.../client/LeafIdClient.java` | 封装两个取 ID 调用 |
| `order-service/.../controller/LeafIDController.java` | 两个对外接口 |
| `order-service/src/test/.../LeafIdClientTest.java` / `LeafIDControllerTest.java` | 单测 |

> 无需新增 Maven 依赖：RestTemplate 来自 spring-web（starter-web 已带），Result/异常来自 sca-common，springdoc 已配置可自动生成 Swagger 文档。
