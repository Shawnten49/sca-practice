# user-service 引入 Knife4j 增强接口文档设计方案

> 状态：**已评审通过**（2026-08-19）——已进入编码阶段
>
> 范围：仅 user-service 引入 Knife4j 增强 UI，不影响其他模块

## 1. 背景与目标

项目已使用 SpringDoc（`springdoc-openapi`）生成 OpenAPI 3 接口文档，原生 Swagger UI 体验一般。引入 **Knife4j** 增强 UI（`/doc.html`），获得更好的文档阅读/调试体验（分组、搜索、在线调试、中文界面）。

目标：

- user-service 接口文档升级为 Knife4j UI；
- 现有 `@Tag / @Operation` 注解与接口代码**零改动**；
- 与现有 SpringDoc 2.8.9 兼容，不影响 `/v3/api-docs` 与 `/swagger-ui.html`。

## 2. 现状

| 项 | 现状 |
|---|---|
| 文档框架 | `springdoc-openapi-starter-webmvc-ui` **2.8.9**（根 pom 统一管理 `springdoc.version`） |
| 注解 | Controller 已使用 `@Tag / @Operation`（如 UserController / UserProfileController） |
| 使用模块 | user-service 显式引入 springdoc starter |

## 3. 选型

| 关注点 | 方案 |
|---|---|
| 组件 | **Knife4j 4.5.0**：`com.github.xiaoymin:knife4j-openapi3-jakarta-spring-boot-starter` |
| 规范 | OpenAPI 3（与 SpringDoc 2.x 同体系） |
| 命名空间 | Jakarta（适配 Spring Boot 3.x） |

## 4. 兼容性分析（关键）

1. **SpringDoc 版本覆盖**：Knife4j 4.5.0 内部默认依赖 `springdoc-openapi-starter-webmvc-ui 2.3.0`，与 Spring Boot 3.5 不匹配（社区已有多例问题）。本项目根 pom 的 dependencyManagement 已把 springdoc 统一为 **2.8.9**，Maven 依赖管理优先级高于传递依赖，会自动覆盖为 2.8.9，无需额外排除。
2. **`knife4j.enable` 增强开关**：4.5.0 + Spring Boot 3.5 下开启增强曾出现文档识别异常。默认**不启用增强功能**（不设置 `knife4j.enable`），仅使用 Knife4j UI 皮肤；如需增强（文档排序、全局参数），先尝试 `enable: true`，异常则回退关闭。

## 5. 改动清单

| 文件 | 改动 |
|---|---|
| 根 `pom.xml` | `<dependencyManagement>` 增加 `knife4j-openapi3-jakarta-spring-boot-starter` 4.5.0 版本管理（`<knife4j.version>4.5.0</knife4j.version>`） |
| `user-service/pom.xml` | 新增 knife4j 依赖；保留现有 springdoc 显式依赖（版本一致，无冲突） |
| `user-service/src/main/resources/application.yml` | 新增 `springdoc` 与 `knife4j` 配置段 |

### 配置示例（application.yml）

```yaml
springdoc:
  api-docs:
    enabled: true
    path: /v3/api-docs
  swagger-ui:
    enabled: true
    path: /swagger-ui.html

knife4j:
  setting:
    language: zh_cn        # 中文界面
  # enable: true           # 增强功能，默认不开启（Spring Boot 3.5 兼容考虑）
```

## 6. 访问入口

| 地址 | 说明 |
|---|---|
| `http://localhost:8081/doc.html` | **Knife4j 增强 UI（新增）** |
| `http://localhost:8081/swagger-ui.html` | 原 SpringDoc UI（保留） |
| `http://localhost:8081/v3/api-docs` | OpenAPI JSON（保留） |

## 7. 验证方案

1. `mvn clean test` 全量测试通过（编译 + 单测不受影响）；
2. 真实 UI 验证：启动 user-service（8081，依赖 Nacos / MySQL / Redis / MongoDB 等）后访问 `/doc.html`、`/swagger-ui.html`、`/v3/api-docs`；
3. 确认 Knife4j 页面能看到现有接口（用户、积分、信用点、用户画像等）且在线调试可用。

## 8. 风险与说明

1. **增强功能兼容性**：若开启 `knife4j.enable=true` 出现文档异常，回退为不开启（UI 皮肤不受影响）；
2. **范围**：仅 user-service；如验证良好，其他模块可后续按相同方式引入；
3. **安全**：本地开发环境默认开启文档；生产部署时通过 `springdoc.api-docs.enabled=false` 等开关控制（本期不涉及）。

> 设计评审通过后进入编码阶段。
