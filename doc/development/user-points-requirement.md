# 需求方案：user-service 积分查询/更新接口 + 三级缓存

> 状态：已确认（2026-08-15）
> 决策：① 更新语义=增量；② 接口风格=沿用项目现有简单风格；③ 返回结构=新增统一 Result 包装类；④ Caffeine 依赖=同意引入；⑤ TTL 默认值=Redis 5min / Caffeine 30s / 空值 30s 合适

## 1. 背景与目标

**现状**：`users` 表已有 `points` 字段，积分通过 MQ 消费（`PointMqConsumer`）以流水方式累加；但**没有对外接口**可以查询/调整用户积分。

**目标**：新增两个 REST 接口（查积分、改积分），其中**查询接口**采用 **Caffeine（本地）→ Redis（分布式）→ MySQL（权威）** 三级缓存架构；更新接口保证缓存与数据库一致。

## 2. 功能需求

### 2.1 接口一：查询用户积分

- `GET /user/points?userId=1`（简单风格，与现有 `/user` 一致）
- 成功返回统一 `Result`：`{ "code": 0, "message": "success", "data": { "userId": 1, "points": 100 } }`
- 校验：`userId` 必填、正整数；用户不存在返回 404（复用 `BusinessException` + `ErrorCode.NOT_FOUND`）

### 2.2 接口二：更新用户积分（增量）

- `POST /user/points/update?userId=1&delta=100`（delta 可为负，即扣减）
- 语义：**增量调整**，`points = points + delta`（已确认）
- 校验：`userId` 正整数；`delta` 非 0、绝对值不超过上限（默认 100000，可配置）
- 成功返回最新积分：`data: { "userId": 1, "points": 200 }`；用户不存在 404

### 2.3 缓存架构（查询接口）

查询链路：`Caffeine 命中 → 返回`，否则 `Redis 命中 → 回填 Caffeine → 返回`，否则 `MySQL 查询 → 回填 Redis + Caffeine → 返回`。

| 维度 | 方案 |
|---|---|
| Redis Key | `user:points:{userId}`，TTL 5 分钟（默认） |
| Caffeine | 本地缓存，TTL 30 秒（短 TTL 缓解跨实例一致性） |
| 穿透 | 用户不存在时缓存空值标记（TTL 30 秒），防恶意 userId 打库 |
| 击穿 | 缓存失效瞬间并发回源 → Redisson 分布式锁单飞，只让一个请求查库 |
| 雪崩 | Redis TTL 加随机抖动（5min ± 30s） |
| 一致性 | 更新接口：先写 MySQL → 删 Redis → 删本实例 Caffeine；其他实例 Caffeine 最长 30s 读到旧值（最终一致，本项目不做跨实例广播） |

## 3. 非功能需求

- 性能目标：三级缓存命中 P99 < 5ms；DB 回源 < 50ms（本机环境）
- 并发：增量更新依赖 DB 原子 `UPDATE ... SET points = points + ?`，不丢更新
- 可观测：查询接口打印缓存命中层级日志（`cache=caffeine / cache=redis / cache=db`）
- 兼容性：不动 `PointMqConsumer` 积分流水逻辑、不动现有 `/user` 接口
- 测试：查询/更新接口单元测试 + 三级缓存链路测试，现有测试保持全绿

## 4. 验收标准

1. 两个接口 curl 可调通，参数校验与 404 正常；
2. 三级缓存链路可验证：首次查日志 `cache=db` → 第二次 `cache=redis` → 同进程第三次 `cache=caffeine`；重启进程后 Redis 仍命中；
3. 更新接口后：DB 值正确、Redis/Caffeine 已失效，再次查询回源得到新值；
4. 不存在的 userId 不压垮 DB（空值缓存生效）；
5. 全部测试通过。
