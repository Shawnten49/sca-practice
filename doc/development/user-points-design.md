# 设计方案：用户积分查询/更新 + Caffeine → Redis → MySQL 三级缓存

> 状态：已确认（2026-08-15），对应需求方案 `user-points-requirement.md`

## 1. 总体结构

新增 3 个业务类 + 2 个配置类，复用现有 `UserMapper`（`selectUserById` + `increasePoints`，无需新增 SQL）：

```
UserPointsController（接口层，沿用简单风格）
   └─ UserPointsService（业务层：三级缓存读 / DB 原子增量更新 + 缓存失效）
        ├─ Caffeine  Cache<Long, PointsValue>   （本地一级）
        ├─ StringRedisTemplate                  （分布式二级）
        ├─ RedissonClient（RLock）              （击穿单飞锁）
        └─ UserMapper                           （MySQL 三级）
```

- `PointsValue`：`record PointsValue(Integer points)`，**null 表示"用户不存在"空值标记**（Caffeine 不支持 null value）
- Caffeine 直接用 API（`Caffeine.newBuilder()`），**不引入 Spring Cache 注解**——三级链路含空值缓存、击穿锁、逐级回填，直接编码更可控

## 2. 接口定义

**查询积分**

```
GET /user/points?userId=1
```

- 校验：userId 必填、正整数；非法 → 400（ProblemDetail）
- 成功：`HTTP 200` + `Result`：`{ "code": 0, "message": "success", "data": { "userId": 1, "points": 100 } }`
- 用户不存在：404 ProblemDetail（现有全局异常处理器，不动）

**更新积分（增量）**

```
POST /user/points/update?userId=1&delta=100     // delta 可为负（扣减）
```

- 校验：userId 正整数；delta 非 0、绝对值 ≤ max-delta（默认 100000）
- 成功：返回最新积分 `data: { "userId": 1, "points": 200 }`
- 用户不存在：404

## 3. Result 统一返回包装类（sca-common 新增）

`com.example.common.Result<T>`（record，Jackson 原生支持；sca-common 无 Lombok）：

```java
public record Result<T>(int code, String message, T data) {
    public static <T> Result<T> ok(T data)     { return new Result<>(0, "success", data); }
    public static <T> Result<T> ok()           { return ok(null); }
    public static <T> Result<T> fail(int code, String message) { ... }
}
```

- **只用于新接口的成功响应**；错误响应保持 ProblemDetail，全局异常处理器不改（order/stock/user 三服务共享）

## 4. 三级缓存设计（查询链路）

**Key 与 TTL**

| 层级 | Key / 结构 | TTL |
|---|---|---|
| Caffeine | `Long userId → PointsValue` | 30s |
| Redis | `user:points:{userId}` → 积分数字串；空值存 `__EMPTY__` | 5min ± 30s 随机抖动 |
| 空值标记（两级） | 同上 | 30s |

**查询流程（伪代码）**

```
getPoints(userId):
  v1 = caffeine.getIfPresent(userId)            // ① 本地命中（含空值标记）→ 返回
  v2 = redis.get(key)                            // ② Redis 命中 → 回填 Caffeine → 返回
  // ③ 都未命中：Redisson 锁单飞回源（防击穿）
  if lock.tryLock(0, 10s):                       // 拿到锁
      double-check Redis → 仍 miss → loadFromDb 回填两级缓存
  else:                                          // 没拿到锁
      sleep 50ms → 重读 Redis；仍 miss → 直接查 DB（保证可用性）

loadFromDb(userId):
  selectUserById → 存在：回填 Redis(5min±抖动) + Caffeine(30s)
                 → 不存在：回填两级空值标记(30s)（防穿透）
```

## 5. 更新接口设计（Cache-Aside 写路径）

```
updatePoints(userId, delta):
  校验 delta（非 0、≤ max-delta）
  updated = userMapper.increasePoints(userId, delta)   // DB 原子 UPDATE，无需分布式锁
  updated == 0 → 抛 404（用户不存在）
  redis.delete(key); caffeine.invalidate(userId)        // 先 DB 后删缓存
  points = loadFromDb(userId)                           // 查最新值并回填两级缓存
  return PointsVO(userId, points)
```

- 增量并发安全：依赖 DB 原子 `points = points + ?`
- 一致性：先写 DB、后删缓存（Cache-Aside），本实例立即失效；其他实例 Caffeine 最长 30s 最终一致

## 6. 配置项（application.yml 新增）

```yaml
app:
  points-cache:
    redis-ttl: 5m
    redis-ttl-jitter: 30s
    caffeine-ttl: 30s
    empty-ttl: 30s
    max-delta: 100000
```

`@ConfigurationProperties(prefix = "app.points-cache")` 绑定，测试可覆盖。

## 7. 依赖与文件清单

- `user-service/pom.xml`：新增 `com.github.ben-manes.caffeine:caffeine`（Spring Boot BOM 管理版本；Redisson 3.50.0 已在父 POM 管理）
- `sca-common`：新增 `com.example.common.Result`
- `user-service` 新增：
  - `domain/PointsValue.java`（record）
  - `dto/PointsVO.java`（record）
  - `config/PointsCacheProperties.java`、`config/CaffeineConfig.java`（Caffeine bean）
  - `service/UserPointsService.java`
  - `controller/UserPointsController.java`
- `application.yml`：`app.points-cache` 配置段

## 8. 测试方案

| 测试 | 内容 |
|---|---|
| `UserPointsServiceTest`（Mockito + 真实 Caffeine，mock Redis/Mapper/锁） | 三级命中逐级回填、空值缓存、击穿单飞（只回源一次）、更新成功/404、更新后缓存失效+回填 |
| `UserPointsControllerTest`（MockMvc） | 参数校验 400、成功返回 Result JSON |
| 手工验收（curl） | 看日志 `cache=caffeine / redis / db` 三级链路；更新后缓存失效验证 |
| 回归 | `mvn -pl user-service test` 全绿（现有用例不动） |

## 9. 风险与边界

1. 成功走 Result、失败走 ProblemDetail 的"双轨"响应——本次只统一新接口；全项目统一响应留作后续
2. 跨实例本地缓存 30s 最终一致窗口（已确认可接受）
3. 更新接口无分布式锁：并发增量正确（DB 原子），但"返回的最新值"来自各自写后读，极端并发下可能不是全局最新——演示场景可接受
4. 拿不到锁时兜底直查 DB：最多一次多余 DB 查询，不阻塞请求
