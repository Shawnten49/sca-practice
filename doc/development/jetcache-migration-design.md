# 设计方案：user-service 积分缓存迁移到 JetCache

> 状态：待评审（2026-08-15）
> 上游决策：`multi-level-cache-selection-review.md` 选定 B（JetCache）；延迟双删简化为「即时失效 + broadcast + 短 TTL」
> 范围：仅 user-service 的积分缓存；**不编码**，本文件为迁移设计

## 1. 目标

用 JetCache 替换 `UserPointsService` 手写的三级缓存链路，收敛重复的缓存样板代码，并顺带解决跨实例本地缓存一致性问题。

- 积分查询（热点，少数场景）：三级 = Caffeine(L1) → Redis(L2) → MySQL(L3)。
- 其他多数场景：两级 = Redis → MySQL，靠 `cacheType` 按方法选择即可，无需另写代码。

## 2. 依赖与版本

| 项 | 值 |
|---|---|
| 版本 | JetCache 2.7.7（`com.alicp.jetcache:jetcache-core`） |
| Starter | `jetcache-starter-redis-lettuce`（Redis 走 Lettuce，与现有 Spring Data Redis/Redisson 可共存） |
| 兼容性 | 官方 [Compatibility](https://github.com/alibaba/jetcache/blob/master/docs/EN/Compatibility.md) 支持 Spring Boot 3 |

> 注意：JetCache 的 `redis.lettuce` 用自己的 Lettuce 连接（`uri` 配置），与 `StringRedisTemplate`/Redisson 是**独立连接**，指向同一个 Redis 即可，互不影响。

## 3. 整体结构（迁移后）

```
UserPointsController
   └─ UserPointsService（业务：参数校验、404 语义、update 事务/DB 增量）
        ├─ PointsCache（新增：@Cached/@CacheInvalidate 的缓存访问器）
        │     └─ UserMapper（selectUserById / increasePoints）
        └─ PointsProperties（仅保留 max-delta 业务配置）
```

- `UserPointsService` 不再持有 Redis/Caffeine/Redisson 句柄，只做业务编排。
- `PointsCache` 是独立 Spring Bean（**避免 self-invocation 导致注解失效**），承载 `@Cached` 读与 `@CacheInvalidate` 失效。

## 4. 配置设计

### 4.1 启用注解

在主类或配置类加：

```java
@EnableMethodCache(basePackages = "com.example.user")
```

### 4.2 application.yml

```yaml
jetcache:
  statIntervalMinutes: 15
  areaInCacheName: false
  local:
    default:
      type: caffeine            # 本地一级用 Caffeine
      keyConvertor: none        # key 为 Long，直接 toString，避免引入 fastjson2
      limit: 10000
      expireAfterWriteInMillis: 30000        # 本地短 TTL（30s，与现状一致）
      broadcastChannel: default # 跨节点本地缓存失效广播（Redis pub/sub）
  remote:
    default:
      type: redis.lettuce
      keyConvertor: none
      valueEncoder: java        # 默认 JDK 序列化；生产可换 jackson/kryo
      valueDecoder: java
      uri: redis://127.0.0.1:6379/
      expireAfterWriteInMillis: 300000       # 远程 5min（与现状一致）
```

> ⚠️ `broadcastChannel` 精确键名以 2.7.7 文档为准（本地缓存跨节点失效依赖此配置，见 [JetCache 多节点本地缓存失效分析](https://blog.gitcode.com/fdd458e3b793c29c2b30c8200d891139.html)）。

## 5. 接口迁移映射

### 5.1 查询 getPoints（三级）

现状：手写 Caffeine→Redis→MySQL + 空值标记 + 单飞锁 + TTL 抖动。

迁移后拆成两段：

```java
// PointsCache：缓存访问器（@Cached 落在独立 Bean，走代理生效）
@Component
public class PointsCache {
    // 三级：本地+远程；空值缓存防穿透；@CachePenetrationProtect 防击穿
    @Cached(name = "user:points", key = "#userId",
            cacheType = CacheType.BOTH,
            expire = 300, localExpire = 30, localLimit = 10000,
            cacheNullValue = true)
    @CachePenetrationProtect
    public Integer load(Long userId) {
        return userMapper.selectUserById(userId).map(User::getPoints).orElse(null);
    }
}

// UserPointsService：业务编排 + 404 语义
public PointsVO getPoints(Long userId) {
    validateUserId(userId);
    Integer points = pointsCache.load(userId);
    if (points == null) {
        throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: userId=" + userId);
    }
    return new PointsVO(userId, points);
}
```

对照关系：

| 现状 | 迁移后 |
|---|---|
| Caffeine 本地缓存 | JetCache L1（`cacheType=BOTH` + `localExpire=30`） |
| Redis 缓存 | JetCache L2（`expire=300`） |
| `PointsValue.empty()` / `__EMPTY__` 空值标记 | `cacheNullValue=true`（内部缓存 NULL 占位） |
| Redisson `RLock` 单飞 | `@CachePenetrationProtect` |
| `randomJitter` TTL 抖动 | ⚠️ JetCache 无内置 jitter，见 §9 待确认 |

### 5.2 更新 updatePoints（写失效）

```java
public PointsVO updatePoints(Long userId, Integer delta) {
    validateUserId(userId);
    if (delta == null || delta == 0) throw ...;
    if (Math.abs((long) delta) > props.getMaxDelta()) throw ...;

    int updated = userMapper.increasePoints(userId, delta);
    if (updated == 0) throw new BusinessException(NOT_FOUND, ...);

    pointsCache.invalidate(userId);          // @CacheInvalidate：即时失效 + broadcast 跨节点
    return getPoints(userId);                // 回读最新值（走缓存回填）
}
```

### 5.3 MQ 加积分 PointMqConsumer

事务提交后调用 `pointsCache.invalidate(userId)`（替代现在的 `userPointsService.invalidatePoints`）：

```java
if (added.get()) {
    pointsCache.invalidate(msg.userId());
}
```

### 5.4 两级（多数场景）约定

需要两级时只需 `cacheType = CacheType.REMOTE`（不配 localExpire），其余不变——这就是"多数两级、少数三级"的落地方式。

## 6. 删除清单（迁移后移除）

| 文件/符号 | 理由 |
|---|---|
| `domain/PointsValue.java` | 空值语义由 `cacheNullValue` 接管 |
| `config/CaffeineConfig.java` | Caffeine 由 JetCache L1 内部管理 |
| `config/PointsCacheEvictQueue.java` | 延迟双删已简化，不再需要 |
| `config/PointsCacheEvictListener.java` | 同上 |
| `UserPointsService` 中 `tryReadRedis`/`loadWithMutex`/`loadFromDb`/`invalidateNow`/`invalidatePoints`/`submitDelayedInvalidate`/`randomJitter`/`parseRedisValue` | 全部由 JetCache 接管 |
| `PointsCacheProperties` 中 `redisTtl/redisTtlJitter/caffeineTtl/emptyTtl/doubleDeleteDelay` | TTL 移入 JetCache 配置/注解；**仅保留 `maxDelta`**（业务规则） |
| `application.yml` 中 `app.points-cache` 段（除 `max-delta`） | 同上 |
| Redisson `RLock` 相关 import | 单飞改 `@CachePenetrationProtect` |

## 7. 一致性策略（简化延迟双删后）

- 写路径：`@CacheInvalidate` **即时失效** 本地 + 远程。
- 跨实例：`broadcastChannel` 通过 Redis pub/sub 广播，让**其他节点也失效各自本地缓存**（解决旧方案"Caffeine 30s 跨实例窗口"）。
- 兜底：本地缓存本身是短 TTL（30s），pub/sub 丢失时靠 TTL 最终一致。
- 不再需要 `RDelayedQueue` 的延迟双删；Cache-Aside 竞态窗口由"短 TTL + broadcast 即时失效"压到可接受范围（本质仍是最终一致）。

## 8. 序列化与 key 生成

- **key**：`keyConvertor: none`，`Long userId` 直接 `String.valueOf`，无需 fastjson2。
- **value**：积分是 `Integer`；默认 `valueEncoder/Decoder: java`（JDK 序列化，零配置）。若追求 Redis 值可读或跨语言，换 `jackson`（需 Jackson，Spring Web 已带）。
- **key 参数名**：`@Cached(key="#userId")` 依赖方法参数名，需 `-parameters` 编译参数——`spring-boot-starter-parent` 默认已开启，无需额外配置。

## 9. 风险与待确认点

1. **TTL 抖动缺失**：JetCache 无内置 jitter。选项：① 接受固定 TTL（本地 30s + 远程 5min，雪崩风险低）；② 在 `@Cached(expire=...)` 用 SpEL 注入随机值（`expire` 支持 SpEL，可 `#{...}` 计算）。**建议 ①，必要时再 ②**。
2. **`broadcastChannel` 配置键名与是否默认开启**：以 2.7.7 文档/实测为准；若未默认开启，需显式配置，否则回退到"本地 30s TTL 最终一致"（即当前状态）。
3. **404 与 `cacheNullValue` 的语义**：`cacheNullValue=true` 会把"用户不存在"缓存为 NULL；业务层把 NULL 映射为 404（见 §5.1）。需确认 NULL 占位与真实 null 不混淆（JetCache 内部用占位对象，OK）。
4. **`@CachePenetrationProtect` 与 `@Cached` 叠加**：需在实现时验证二者兼容（JetCache 官方支持同方法叠加）。
5. **可观测性**：现有 `cache=caffeine/redis/db` 日志会消失；JetCache 提供命中率统计（`statIntervalMinutes` + 统计 API），迁移后需补等价观测（日志或指标）。
6. **update 返回值语义**：`updatePoints` 改为"失效后 `getPoints` 回读"，比现状"写后直接 `loadFromDb`"多一次缓存穿透回源，但返回全局最新值语义一致。
7. **Redis 连接**：JetCache 的 Lettuce `uri` 需与现有 Redis 一致；Redisson 单飞锁移除后，`redisson-spring-boot-starter` 是否仍被其它功能（如 RedissonLockController）使用——需确认，不能贸然移除依赖。

## 10. 测试方案

| 测试 | 内容 |
|---|---|
| `PointsCacheTest` | `load` 命中缓存不查库、`cacheNullValue` 缓存 NULL、`@CachePenetrationProtect` 并发单飞 |
| `UserPointsServiceTest` | getPoints 404 语义、updatePoints 校验/404/失效调用、回读最新值 |
| `PointMqConsumerTest` | 事务提交后调用 `pointsCache.invalidate` |
| 集成（手工/curl） | 首次 `load` 查库 → 二次命中；更新后 Redis/本地均失效；多实例下本地缓存被广播失效 |
| 回归 | 删除 `PointsCacheEvictQueue/Listener` 相关旧测试；`mvn -pl user-service -am test` 全绿 |

## 11. 实施步骤（供评审后编码，本文件不含代码）

1. 加依赖 + `@EnableMethodCache` + yaml 配置。
2. 新增 `PointsCache`（读/失效访问器）。
3. 改造 `UserPointsService`（getPoints/updatePoints）与 `PointMqConsumer`。
4. 删除 §6 清单中的旧代码与配置。
5. 补测试、跑回归、手工验证广播失效。

## 12. 待拍板

1. TTL 抖动：接受固定 TTL（推荐）还是 SpEL 随机化？
2. 序列化：保持 `java` 默认，还是换 `jackson` 让 Redis 值可读？
3. `broadcastChannel` 若需显式配置/通道命名，是否接受新增配置？
