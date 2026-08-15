# 需求方案：user-service 用户信用点（credits）查询/修改接口 + JetCache 三级缓存

> 状态：已确认（2026-08-15）——修改语义=增量 delta；扣减不足=409 `INSUFFICIENT_CREDITS`；delta 上限复用可配置 max-delta
> 前提依赖：JetCache 引入（`jetcache-migration-design.md` 已评审通过，尚未编码；本需求将是 JetCache 首个真实消费方）
> 关联：`user-points-requirement.md`（积分需求，作为风格与语义参照）；设计见 `user-credits-design.md`

## 1. 背景与目标

**现状**：`users` 表已有 `points`（积分）字段及查询/修改接口（三级缓存，正迁移到 JetCache）；业务需要新增一个**信用点 `credits`** 字段，用于独立于积分的一套信用额度。

**目标**：
1. `users` 表新增 `credits` 字段：默认 0，**不能为负数**；
2. user-service 新增一个 controller，提供两个接口：按 `user_id` 查询 credits、按 `user_id` 修改 credits；
3. 查询接口采用 **JetCache 三级缓存**：Caffeine（本地）→ Redis（分布式）→ MySQL（权威）。

## 2. 功能需求

### 2.1 数据字段

| 项 | 值 |
|---|---|
| 表 | `users` |
| 字段 | `credits INT NOT NULL DEFAULT 0` |
| 约束 | `credits >= 0`（数据库 CHECK + 应用层双重保证） |
| 迁移 | Flyway 新增 V2 脚本（MySQL 8.0.16+ 支持 CHECK） |

### 2.2 接口一：查询信用点

- `GET /user/credits?userId=1`
- 成功返回统一 `Result`：`{ "code": 0, "message": "success", "data": { "userId": 1, "credits": 0 } }`
- 校验：`userId` 必填、正整数；用户不存在返回 404（`BusinessException` + `ErrorCode.NOT_FOUND`）
- 三级缓存：Caffeine 命中 → 返回；否则 Redis 命中 → 回填 Caffeine → 返回；否则 MySQL 查询 → 回填 Redis + Caffeine → 返回

### 2.3 接口二：修改信用点

- `POST /user/credits/update?userId=1&delta=100`（**delta 可为负，即扣减**）
- 语义：**增量调整**，`credits = credits + delta`（待确认，见 §6）
- 校验：
  - `userId` 正整数；
  - `delta` 非 0、绝对值不超过上限（默认 100000，可配置）；
  - **扣减后不能为负**：`credits + delta >= 0`，不足时返回明确错误（区别于"用户不存在"）
- 返回最新信用点：`data: { "userId": 1, "credits": 80 }`
- 写路径：DB 原子增量 + JetCache 即时失效（本地 + 远程 + 跨节点广播）

### 2.4 缓存架构

| 维度 | 方案 |
|---|---|
| 框架 | JetCache（`cacheType=BOTH`：本地 Caffeine + 远程 Redis） |
| 缓存名 / Key | `user:credits` / `#userId` |
| 本地 TTL | 30s |
| 远程 TTL | 5min |
| 穿透 | `cacheNullValue=true`（用户不存在缓存 NULL，业务层映射 404） |
| 击穿 | `@CachePenetrationProtect`（热点单飞） |
| 一致性 | 修改接口 `@CacheInvalidate` 即时失效 + broadcast 跨节点本地失效 + 短 TTL 兜底 |

> 说明：Caffeine→Redis→MySQL 即需求所述 "cache-redis-mysql" 三层；JetCache 的 `cacheType=BOTH` 覆盖前两层，MySQL 为回源层。

## 3. 非功能需求

- 性能目标：三级缓存命中 P99 < 5ms；DB 回源 < 50ms（本机环境）
- 并发：增量更新依赖 DB 原子 `UPDATE ... SET credits = credits + ?` + 非负守卫，不丢更新
- 可观测：JetCache 命中率统计（`statIntervalMinutes`），保留必要的缓存命中/回源观测
- 兼容性：不动现有 `points` 字段与 `/user/points` 接口；`User` 实体新增 `credits` 字段不影响既有查询
- 测试：查询/修改接口单测 + 三级缓存链路测试 + 非负约束测试，现有测试保持全绿

## 4. 错误语义

| 场景 | 状态码 | 说明 |
|---|---|---|
| 用户不存在 | 404 | `ErrorCode.NOT_FOUND` |
| `userId` / `delta` 非法 | 400 | `ErrorCode.BAD_REQUEST` |
| 扣减后 credits 为负 | 409（建议） | 新增 `ErrorCode.INSUFFICIENT_CREDITS`，区别于 404 |

## 5. 验收标准

1. Flyway 迁移后 `users.credits` 存在，默认 0；直接插入负数被数据库拒绝；
2. 两个接口 curl 可调通，参数校验、404、非负错误码正常；
3. 三级缓存链路可验证：首次查日志/统计显示回源 MySQL → 二次命中 Redis → 同进程三次命中 Caffeine；
4. 修改后：DB 值正确、缓存已失效，再次查询回源得到新值；多实例下本地缓存被广播失效；
5. 扣减到负数被拒绝，credits 恒 >= 0；
6. 全部测试通过。

## 6. 待确认决策

| # | 决策点 | 建议默认 | 说明 |
|---|---|---|---|
| 1 | 修改语义 | **增量（delta，可为负）**，与 points 一致 | 备选：绝对值覆盖 `credits=xxx` |
| 2 | 扣减不足的错误码 | **409 + 新增 `INSUFFICIENT_CREDITS`** | 备选：400 |
| 3 | delta 上限 | **复用可配置 `max-delta`（默认 100000）** | 与 points 一致 |
