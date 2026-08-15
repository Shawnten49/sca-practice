# 评审方案：多级缓存抽象选型（自研 vs 开源）

> 状态：已确认（2026-08-15）——选定 **B（JetCache）**，延迟双删简化为「即时失效 + broadcast + 短 TTL」
> 关联：`user-points-design.md`（当前手写三级缓存）；`user-points-cache-consistency-design.md`（延迟双删）；迁移细节见 `jetcache-migration-design.md`

## 1. 背景与诉求

当前 `UserPointsService` 手写了一套 Caffeine → Redis → MySQL 三级缓存，把以下通用关注点**和积分业务耦合在一起**：

| 关注点 | 现状实现 |
|---|---|
| 三级读链路（本地→远端→DB，逐级回填） | `getPoints` + `tryReadRedis` + `loadFromDb` |
| 空值缓存（防穿透） | `PointsValue.empty()` / `__EMPTY__` |
| 热点单飞（防击穿） | Redisson `RLock` + double-check |
| TTL 随机抖动（防雪崩） | `randomJitter` |
| 写路径 Cache-Aside + 延迟双删 | `invalidateNow` / `invalidatePoints` + `RDelayedQueue` |

诉求：
1. **少数场景**需要三级（Caffeine → Redis → MySQL），**多数场景**只需两级（Redis → MySQL）；
2. 上述通用逻辑不想每处重写，希望抽成可复用能力（自研或引入开源）。

## 2. 需求能力清单（本次选型的评价维度）

| 能力 | 说明 | 必需 |
|---|---|---|
| 声明式缓存（注解） | 方法级 `@Cached/@CacheInvalidate`，少写样板 | 强烈 |
| 两级（Redis→MySQL） | 大多数场景 | ✅ |
| 三级（Caffeine→Redis→MySQL） | 少数热点场景 | ✅ |
| 空值缓存 | 防穿透 | ✅ |
| 热点单飞 | 防击穿 | ✅ |
| TTL 抖动 | 防雪崩 | 建议 |
| 写失效（Cache-Aside） | 更新后删缓存 | ✅ |
| 跨实例本地缓存失效 | 多实例时清对方 Caffeine | 建议 |
| 异步刷新 | 过期前刷新，降低击穿 | 可选 |
| 序列化 | 对象 → Redis 值 | ✅ |
| 监控/命中率 | 可观测 | 可选 |
| Spring Boot 3 兼容 | 本项目 3.5.x | ✅ |
| 维护活跃度 | 长期可用 | 建议 |

## 3. 候选方案

| 方案 | 说明 |
|---|---|
| **A. 自研多级缓存框架/模板** | 把现有逻辑抽象成可复用组件 |
| **B. JetCache**（阿里） | 通用缓存访问框架，local+remote 两级、注解式 |
| **C. layering-cache** | 专门的 Caffeine(L1)+Redis(L2) 两级框架 |
| **D. Spring Cache + 组合 CacheManager** | 标准抽象上手工拼多级 |
| **E. Redisson RLocalCachedMap** | 本地缓存 + Redis + pub/sub 失效的构件 |

## 4. 方案对比矩阵

| 能力 | A 自研 | B JetCache | C layering-cache | D Spring Cache | E RLocalCachedMap |
|---|---|---|---|---|---|
| 注解式 | 需自建 | ✅ `@Cached` 等 | ✅ `@FirstCache` | ✅ `@Cacheable` | ❌ Map API |
| 两级 Redis→MySQL | ✅ 需实现 | ✅ `cacheType=REMOTE` | ✅ | ⚠️ 需组合 | ⚠️ 仅缓存层 |
| 三级 Caffeine→Redis→MySQL | ✅ 需实现 | ✅ `cacheType=BOTH` | ✅ L1+L2 | ⚠️ 需自定义 Cache | ⚠️ 仅缓存层 |
| 空值缓存(穿透) | ✅ 已有 | ✅ `cacheNullValue` | ✅ | ⚠️ 需自定义 | ❌ |
| 单飞(击穿) | ✅ 已有 | ✅ `@CachePenetrationProtect` | ✅ | ⚠️ 需 Caffeine loader | ❌ |
| TTL 抖动(雪崩) | ✅ 已有 | ⚠️ 需扩展随机 expire | ⚠️ 需扩展 | ❌ | ❌ |
| 写失效 | ✅ 已有(含延迟双删) | ✅ `@CacheInvalidate` | ✅ | ✅ `@CacheEvict` | ⚠️ put/remove |
| 跨实例本地失效 | ❌ 当前靠 TTL | ✅ broadcast(pub/sub) | ✅ 自带 | ❌ | ✅ pub/sub |
| 异步刷新 | 需实现 | ✅ `@CacheRefresh` | ✅ | ⚠️ | ❌ |
| 序列化 | 需选型 | ✅ Java/Kryo/Jackson | ✅ | ✅ | ✅ |
| 监控/统计 | 需自建 | ✅ 内置统计 | ✅ 监控后台 | ⚠️ | ⚠️ |
| 维护活跃度 | — | 高（2.7.7） | 低（停更风险） | 高 | 高 |
| Spring Boot 3 | — | ✅ 2.7.x | ⚠️ 需 fork | ✅ | ✅ |

## 5. 各方案详细评估

### A. 自研多级缓存框架/模板
- **优点**：完全可控、零新依赖、契合现有代码风格；练手价值高（本仓库定位即学习实践）。
- **缺点**：序列化、key 生成、跨实例广播失效、监控统计、并发边界都要自己踩坑；长期维护成本高。
- **结论**：不建"完整框架"，若自研只做**轻量模板**（§6），只抽象读/写模板，不碰序列化/监控等重活。

### B. JetCache（推荐）
- **定位**：阿里开源通用缓存访问框架，与 Spring Cloud Alibaba 同源，[官方介绍](https://github.com/alibaba/jetcache)。
- **版本/兼容**：当前 2.7.7（`com.alicp.jetcache:jetcache-core`），官方 [Compatibility 文档](https://github.com/alibaba/jetcache/blob/master/docs/EN/Compatibility.md) 明确支持 Spring Boot 3；Starter 坐标 `jetcache-starter-redis-lettuce` / `jetcache-starter-redis`。
- **覆盖度**：`cacheType=REMOTE` 即两级、`cacheType=BOTH` 即三级（本地 Caffeine/LinkedHashMap + Redis）；`cacheNullValue` 防穿透、`@CachePenetrationProtect` 防击穿、`@CacheRefresh` 异步刷新、`@CacheInvalidate` 写失效、内置统计。
- **与现有代码的关系**：可替换 `UserPointsService` 里绝大多数手写逻辑；但**延迟双删不属于 JetCache 能力**（它提供即时 `@CacheInvalidate` + 本地缓存 broadcast 失效），是否保留自研延迟双删需在实施时决策（见 §8）。
- **需验证点**：本地缓存跨节点 broadcast 失效的具体配置 API 以 2.7.x 文档为准；TTL 抖动需自行在 `expire` 上做随机化（JetCache 无内置 jitter）。

### C. layering-cache
- **定位**：专门做 Caffeine(L1)+Redis(L2) 两级，[项目地址](https://github.com/xiaolyuh/layering-cache)。
- **优点**：两级开箱即用、有监控后台、空值缓存/击穿单飞都已内置。
- **缺点**：**维护停滞**（主要活跃在 2020–2022，Spring Boot 2.x 时代），Boot 3 需靠第三方 fork（如 zomin/xkernal），长期风险大。
- **结论**：能力契合，但维护活跃度是硬伤，不推荐作为新依赖。

### D. Spring Cache + 组合 CacheManager
- **优点**：标准、生态最广。
- **缺点**：Spring Cache 本身**不做多级 read-through**（`CompositeCacheManager` 只按序查找，不会自动"本地 miss → 远端命中 → 回填本地"）；空值缓存、单飞、TTL 抖动、跨实例失效都要自己实现，等于又回到手写。
- **结论**：仅当团队已有 Spring Cache 基建时值得，否则不如直接上 JetCache。

### E. Redisson RLocalCachedMap
- **定位**：本地缓存 + Redis 的**缓存构件**（带 pub/sub 失效），[Redisson 本地缓存策略](https://deepwiki.com/redisson/redisson/9.2-local-caching-strategies)。
- **优点**：已引入 Redisson，跨实例本地失效自带；能解决前文"多实例 Caffeine 30s 窗口"问题。
- **缺点**：是 Map API 不是注解框架；**无 DB read-through、无空值缓存、无单飞**，这些仍要自己包。
- **结论**：可作为自研方案（A）里"带广播失效的本地缓存"的**底层构件**，而非独立方案。

## 6. 若选 A：自研轻量模板最小设计（供参考）

不建完整框架，只抽一个"读模板 + 写模板"，把 `UserPointsService` 的通用逻辑参数化：

```java
// 读模板：两级或三级，按需装配缓存层
public interface CacheLevel<K, V> {
    V get(K key);                    // 命中返回，未命中返回 null
    void put(K key, V value);        // 回填
    void invalidate(K key);          // 失效
}

public class MultiLevelCache<K, V> {
    private final List<CacheLevel<K, V>> levels;       // 两级=[redis]；三级=[caffeine, redis]
    private final Function<K, V> dbLoader;             // MySQL 回源
    private final Predicate<V> isEmpty;                // 空值标记判定（防穿透）
    private final LockProvider lockProvider;           // 单飞锁（可空）

    V get(K key) {
        for (CacheLevel<K,V> level : levels) {
            V v = level.get(key);
            if (v != null) { backfill(levelsBefore(level), key, v); return v; }
        }
        return lockAndLoad(key);                       // 单飞 + dbLoader + 回填
    }

    void evict(K key) {                                // 立即失效 + 延迟双删（复用 RDelayedQueue）
        levels.forEach(l -> l.invalidate(key));
        delayedEvict.submit(key, delay);
    }
}
```

- 两级场景：`levels = [redisLevel]`；三级场景：`levels = [caffeineLevel, redisLevel]`。
- 保留现有 `PointsCacheEvictQueue`（延迟双删）与 Redisson 单飞锁。
- 业务侧只提供 `dbLoader`（`UserMapper.selectUserById`）和 key 生成，其余复用。
- 代价：仍需自己维护序列化（默认可用 JSON）与统计（可选）。

## 7. 对现有代码的迁移影响

| 方案 | 影响 |
|---|---|
| A 自研 | `UserPointsService` 改为调用 `MultiLevelCache`，业务保留 `dbLoader`/key；`PointsCacheEvictQueue`、`PointsValue` 复用 |
| B JetCache | 引入依赖 + 配置；`UserPointsService` 删除手写三级链路，改为 `@Cached` + `@CacheInvalidate`；`PointsValue` 空值语义由 `cacheNullValue` 接管；延迟双删取舍见 §8 |

## 8. 风险与待确认点

1. **延迟双删 vs JetCache**：JetCache 不提供延迟双删；若选 B，需决定是"接受即时失效 + 本地短 TTL + broadcast"（更简单，最终一致），还是"保留自研延迟双删 + JetCache"（两者叠加，复杂度上升）。**建议选前者**，简化架构。
2. **TTL 抖动**：JetCache 无内置 jitter，需自行在 `expire` 上做随机化（或接受固定 TTL + 分批过期）。
3. **跨实例本地失效**：JetCache broadcast 与 Redisson `RLocalCachedMap` 都依赖 Redis pub/sub，需确认订阅丢失时的兜底（本地 TTL 最终兜底）。
4. **序列化**：JetCache 默认 Java 序列化，生产建议换 Jackson/Kryo；需一次配置。
5. **监控**：现有 `cache=caffeine/redis/db` 日志在迁移后会被框架统计替代，需保留等价可观测性。

## 9. 结论与待决策

- **首选 B（JetCache）**：与 Spring Cloud Alibaba 同源、维护活跃、两级/三级/穿透/击穿/刷新开箱即用，能同时满足"多数两级、少数三级"。
- **次选 A（自研轻量模板）**：仅当想零新依赖或作为练手，按 §6 只抽读/写模板，不碰重活。

待拍板：
1. 选 A 还是 B？
2. 若选 B，延迟双删是否简化为"即时失效 + broadcast + 本地短 TTL"？
3. 是否顺带解决"跨实例 Caffeine 30s 窗口"（JetCache broadcast / RLocalCachedMap）？
