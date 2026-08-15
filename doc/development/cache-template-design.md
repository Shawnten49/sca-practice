# 设计方案：通用多级缓存模板 MultiLevelCacheTemplate

> 状态：已确认（2026-08-15）——类名 `MultiLevelCacheTemplate`；支持 `cacheType()` 钩子（两级 REMOTE）；方法名 `get`/`invalidate`
> 关联：`user-credits-design.md`（CreditsCache 现状）；本次把 CreditsCache 的固定流程抽象为可复用模板

## 1. 背景与目标

`CreditsCache` 目前实现了一套完整的三级缓存访问逻辑：JetCache 读穿透（Caffeine→Redis→MySQL）、空值缓存（防穿透）、Redisson 分布式锁单飞（防击穿）、缓存失效。未来其它业务（如"积分"迁移、其它用户属性）会重复这套代码。

**目标**：把**不变的流程**抽成抽象基类，业务子类**只实现自己关心的方法**（DB 怎么加载、缓存名/TTL），不再复制流程代码。

## 2. 抽取边界（不变 vs 可变）

| 类别 | 内容 | 归属 |
|---|---|---|
| 固定流程 | 读缓存 → miss → 分布式锁单飞（double-check + 兜底）→ DB 回源 → 回填；失效缓存 | **模板** |
| 可变：DB 回源 | 怎么从 MySQL 加载一条记录 | 子类实现 `loadFromDb` |
| 可变：缓存身份 | 缓存名（唯一）、缓存层级（BOTH/REMOTE）、TTL、本地容量 | 子类覆写配置方法 |
| 可变：业务语义 | null→404、参数校验、更新 DB 的业务编排 | 仍留在各 Service（不在本模板范围） |

> 说明：`updateCredits` 的「更新 DB → 失效缓存 → 回读」是**业务编排**，不同业务 SQL 不同，**不抽进模板**；模板只提供 `get` / `invalidate` 两个原语，业务层按需组合。

## 3. 总体设计（Template Method 模式）

抽象类 `MultiLevelCacheTemplate<K, V>`：

- **泛型**：`K` 缓存 key 类型（须可被全局 `keyConvertor: jackson` 序列化，如 Long/String）；`V` 缓存值类型（须可被 `valueEncoder: java` 序列化，如 Integer/Serializable POJO）。
- **固定流程方法**：`get(K key)`（默认读，不走锁）、`getWithMutex(K key)`（分布式锁单飞）、`invalidate(K key)`，流程集中在模板内，子类通常无需覆写（为便于单测 mock，未加 `final`）。
- **抽象方法**：`cacheName()`、`loadFromDb(K key)`，子类必须实现。
- **可覆写钩子**：`cacheType()`、TTL、容量、锁参数，均有默认值。

## 4. 类骨架

```java
public abstract class MultiLevelCacheTemplate<K, V> {

    private final CacheManager cacheManager;
    private final RedissonClient redissonClient;
    private volatile Cache<K, V> cache;                 // 惰性初始化，见 §6

    protected MultiLevelCacheTemplate(CacheManager cacheManager, RedissonClient redissonClient) {
        this.cacheManager = cacheManager;
        this.redissonClient = redissonClient;
    }

    // —— 子类必须实现 ——
    protected abstract String cacheName();              // 缓存名（全局唯一）
    protected abstract V loadFromDb(K key);             // DB 回源；返回 null 表示"空值"

    // —— 可选覆写（默认值） ——
    protected CacheType cacheType()  { return CacheType.BOTH; }     // BOTH=三级；REMOTE=两级
    protected Duration expire()      { return Duration.ofMinutes(5); }
    protected Duration localExpire() { return Duration.ofSeconds(30); }
    protected int localLimit()       { return 10000; }
    protected boolean cacheNullValue() { return true; }
    protected String lockKeyPrefix() { return cacheName() + ":lock:"; }
    protected long lockWaitSeconds() { return 0; }
    protected long lockLeaseSeconds(){ return 10; }
    protected long retrySleepMs()    { return 50; }

    // —— 固定流程（子类通常无需覆写） ——
    public V get(K key) {                                // 默认读：miss 直接回源，不走全局锁
        CacheGetResult<V> r = cache().GET(key);
        if (r.isSuccess()) return r.getValue();          // 命中（含缓存 null）
        return loadAndPut(key);
    }

    public V getWithMutex(K key) {                       // 显式加锁读：miss 走分布式锁单飞（防击穿）
        CacheGetResult<V> r = cache().GET(key);
        if (r.isSuccess()) return r.getValue();
        return loadWithMutex(key);
    }

    public void invalidate(K key) {
        cache().remove(key);
    }

    private V loadWithMutex(K key) {                     // 与 CreditsCache 现有逻辑一致
        RLock lock = redissonClient.getLock(lockKeyPrefix() + key);
        boolean locked = tryLock(lock);
        if (locked) {
            try {
                CacheGetResult<V> r = cache().GET(key);  // double-check
                if (r.isSuccess()) return r.getValue();
                return loadAndPut(key);
            } finally {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            }
        }
        sleepQuietly(retrySleepMs());
        CacheGetResult<V> r = cache().GET(key);          // 重读
        if (r.isSuccess()) return r.getValue();
        return loadAndPut(key);                          // 兜底直查
    }

    private V loadAndPut(K key) {
        V value = loadFromDb(key);
        cache().put(key, value);                         // cacheNullValue=true 时 null 也会被缓存
        return value;
    }
    // ... tryLock / sleepQuietly / cache() 惰性构建 略
}
```

**固定流程与原 `CreditsCache` 完全一致**，只是把 key/value 泛化、缓存层级/TTL/锁参数做成钩子、DB 回源交给 `loadFromDb`。

## 5. 使用示例（CreditsCache 改造后）

```java
@Component
public class CreditsCache extends MultiLevelCacheTemplate<Long, Integer> {

    private final UserMapper userMapper;

    public CreditsCache(CacheManager cacheManager, RedissonClient redissonClient, UserMapper userMapper) {
        super(cacheManager, redissonClient);
        this.userMapper = userMapper;
    }

    @Override protected String cacheName() { return "user:credits"; }

    @Override protected Integer loadFromDb(Long userId) {
        return userMapper.selectUserById(userId).map(User::getCredits).orElse(null);
    }
}
```

对比：原来 ~120 行 → 改造后 ~15 行，只剩「缓存名 + 怎么查库」。

两级场景只需覆写 `cacheType()` 返回 `CacheType.REMOTE`：

```java
@Override protected CacheType cacheType() { return CacheType.REMOTE; }
```

`UserCreditsService` 用法仅把 `creditsCache.load(userId)` 改为 `creditsCache.getWithMutex(userId)`（credits 回源成本低但需要防击穿，故用加锁读；普通场景用 `get`）。

## 6. 生命周期与并发

- **惰性初始化缓存**：`Cache<K,V>` 在首次 `get/invalidate` 时用 `QuickConfig` 构建（double-check 保证单例）。原因：`cacheName()` 等抽象方法若在父类构造函数里调用，会踩「子类尚未构造完成」的坑；惰性构建天然规避。
- **固定关闭跨节点广播**：`QuickConfig` 内 `syncLocal(false)`（写死 + 注释），沿用"避免广播风暴"的结论；跨节点本地缓存靠 `localExpire` 短 TTL 最终一致。
- **缓存层级**：`cacheType()` 钩子默认 `BOTH`；返回 `REMOTE` 时本地相关配置（localExpire/localLimit/syncLocal）被 JetCache 忽略，即两级。
- **缓存配置**：`cacheType` 由钩子决定，`cacheNullValue` 按钩子。

## 7. 包位置与依赖

- **包**：`com.example.user.cache`（当前仅 user-service 使用 JetCache/Redisson，先放这里）。
- **依赖**：复用已引入的 `jetcache-starter-redis-lettuce` 与 `redisson-spring-boot-starter`，无新增依赖。
- **待确认**：若后续 order/stock 也要用，可迁到 `sca-common`（届时 sca-common 需引入 JetCache/Redisson 依赖，需单独评审）。

## 8. 改造影响

| 文件 | 改动 |
|---|---|
| 新增 `cache/MultiLevelCacheTemplate.java` | 抽象模板 |
| `CreditsCache` | 改为继承模板，仅保留 `cacheName`/`loadFromDb` |
| `UserCreditsService` | `load` → `getWithMutex`（1 处） |
| 测试 | 新增 `MultiLevelCacheTemplateTest`（用假子类测固定流程）；`CreditsCacheTest` 精简为测 `cacheName`/`loadFromDb` |
| 其它业务 | 不涉及（points 手写缓存不动） |

## 9. 测试方案

| 测试 | 内容 |
|---|---|
| `MultiLevelCacheTemplateTest`（假子类 + mock CacheManager/Redisson/RLock） | 命中返回、缓存 null 返回、miss+拿锁回源回填、miss+未拿锁重读、invalidate、REMOTE 层级构建 |
| `CreditsCacheTest`（精简） | `cacheName()=="user:credits"`；`loadFromDb` 委托 mapper（存在返回 credits / 不存在返回 null） |
| 回归 | `mvn -pl user-service -am test` 全绿 |

## 10. 风险与待确认点

1. **方法名变更**：`CreditsCache.load` → `get`，需同步 `UserCreditsService`（少量改动）。
2. **泛型约束**：`K` 须 Jackson 可序列化、`V` 须 JDK 可序列化（当前 keyConvertor=jackson / valueEncoder=java）；若未来有复杂 key，需自定义 keyConvertor。
3. **REMOTE 层级**：`cacheType()=REMOTE` 时分布式锁单飞仍生效（锁与缓存层级无关）；但 REMOTE 无本地缓存，单飞压力全部由 Redis 承载，需注意。
4. **广播保持关闭**：`syncLocal(false)` 在模板内写死，符合上一轮决策；如需开启，再升级为钩子。
5. **包位置**：先在 `com.example.user.cache`；跨服务复用再迁 `sca-common`（需评审依赖影响）。

## 11. 已拍板决策

1. 类名：`MultiLevelCacheTemplate` ✅
2. 支持 `cacheType()` 钩子（默认 BOTH，可覆写为 REMOTE 两级）✅
3. 方法名 `get`/`invalidate`（替换现有 `load`）✅
