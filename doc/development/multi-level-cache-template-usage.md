# MultiLevelCacheTemplate 使用说明

> 定位：user-service 通用多级缓存访问模板（JetCache + Redisson）
> 关联设计：`cache-template-design.md`
> 适用版本：JetCache 2.7.7 · Spring Boot 3.5

## 1. 这是什么

`MultiLevelCacheTemplate<K, V>` 是一个抽象基类，把「多级缓存读穿透 + 空值缓存（防穿透）+ 分布式锁单飞（防击穿）+ 缓存失效」这套**固定流程**封装好，业务方只需实现**两个方法**：

- `cacheName()` —— 缓存名（全局唯一）
- `loadFromDb(K key)` —— 怎么从数据库加载一条记录（返回 `null` 表示"空值"）

其余流程（读缓存、miss 回源、回填、失效）由模板统一处理，业务代码不再复制粘贴。

## 2. 前置条件

- 依赖已引入：`jetcache-starter-redis-lettuce`、`redisson-spring-boot-starter`（本项目已配置）。
- `application.yml` 已配置 `jetcache.*`（本地 Caffeine + 远程 Redis，`keyConvertor: jackson`、`valueEncoder: java`）。
- 模板用 JetCache **程序化 API**（`CacheManager.getOrCreateCache`），不依赖 `@Cached` 注解，因此无需 `@EnableMethodCache`。

## 3. 快速上手（三步）

```java
@Component
public class CreditsCache extends MultiLevelCacheTemplate<Long, Integer> {

    private final UserMapper userMapper;

    public CreditsCache(CacheManager cacheManager, RedissonClient redissonClient, UserMapper userMapper) {
        super(cacheManager, redissonClient);          // ① 调用父类构造，传入两个基础设施
        this.userMapper = userMapper;
    }

    @Override protected String cacheName() {          // ② 缓存名
        return "user:credits";
    }

    @Override protected Integer loadFromDb(Long userId) {  // ③ 怎么查库
        return userMapper.selectUserById(userId).map(User::getCredits).orElse(null);
    }
}
```

完成。业务 Service 里这样用：

```java
Integer credits = creditsCache.get(1L);          // 或 getWithMutex(1L)
creditsCache.invalidate(1L);                     // 更新 DB 后失效缓存
```

## 4. 三个入口方法

| 方法 | 语义 | 何时用 |
|---|---|---|
| `get(K key)` | 读缓存，miss 直接回源 + 回填，**不走全局锁** | 默认选择；读多写少、可容忍并发回源 |
| `getWithMutex(K key)` | 读缓存，miss 走 **Redisson 分布式锁单飞**（防击穿） | 回源成本高、需要集群内同一 key 只查一次库 |
| `invalidate(K key)` | 删本机本地 + 共享 Redis 缓存 | 业务写 DB 后调用，保持缓存一致 |

三者返回语义一致：命中（含缓存 null）直接返回；`get`/`getWithMutex` 的 miss 都会回填缓存。

## 5. 可选钩子（覆写方法，均有默认值）

| 钩子 | 默认值 | 说明 |
|---|---|---|
| `cacheType()` | `CacheType.BOTH` | `BOTH`=三级（本地+远程）；`REMOTE`=两级（仅远程） |
| `expire()` | 5 分钟 | 远程 Redis TTL |
| `localExpire()` | 30 秒 | 本地 Caffeine TTL（仅 BOTH 生效） |
| `localLimit()` | 10000 | 本地缓存最大条数（仅 BOTH 生效） |
| `cacheNullValue()` | `true` | 是否缓存空值（防穿透） |
| `lockKeyPrefix()` | `cacheName() + ":lock:"` | 分布式锁 key 前缀 |
| `lockWaitSeconds()` | 0 | 拿锁等待秒数（0=不等待，立即返回） |
| `lockLeaseSeconds()` | 10 | 锁租约秒数 |
| `retrySleepMs()` | 50 | 未拿到锁时重读缓存的等待毫秒 |

示例（改成两级缓存）：

```java
@Override protected CacheType cacheType() { return CacheType.REMOTE; }
```

## 6. 三级 vs 两级

- **三级**（默认 `BOTH`）：`Caffeine → Redis → MySQL`，热点数据本地命中最快。
- **两级**（`REMOTE`）：`Redis → MySQL`，无本地缓存，跨实例天然一致，但每次读多一次 Redis 往返。

按数据热度选择：高频小数据用 `BOTH`，普通数据用 `REMOTE`。

## 7. 空值语义（穿透防护与 404）

`loadFromDb` 返回 `null` 表示"记录不存在"。模板在 `cacheNullValue=true`（默认）下会把这个 `null` 也缓存起来，避免恶意/无效 key 反复打库（防穿透）。

**约定**：`get`/`getWithMutex` 返回 `null` 即表示"空值"，业务层据此返回 404：

```java
Integer credits = creditsCache.getWithMutex(userId);
if (credits == null) {
    throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: userId=" + userId);
}
```

## 8. 一致性说明

- **写路径**：业务先更新 DB（原子 SQL），再调 `invalidate(key)`（Cache-Aside）。
- **跨节点本地缓存**：模板内 `syncLocal=false`（固定），不广播失效，其它节点本地缓存靠 `localExpire`（30s）**最终一致**。关闭原因：广播在写频繁时会形成"广播风暴"，压 Redis。
- **单飞语义**：`getWithMutex` 的分布式锁保证集群内同一 key 只有 1 个实例查库；拿不到锁的线程等待后重读缓存，仍 miss 则兜底直查 DB（保证可用）。

## 9. 序列化约束

- `K`：须可被全局 `keyConvertor=jackson` 序列化（`Long`/`String` 等简单类型即可；复杂 key 需自定义 keyConvertor）。
- `V`：须可被 `valueEncoder=java`（JDK 序列化）处理，即实现 `Serializable`（`Integer`、POJO 等）。

## 10. 测试建议

模板已用 `MultiLevelCacheTemplateTest`（假子类 + mock）覆盖固定流程。业务方新增子类时，只需测：

- `cacheName()` 返回值正确；
- `loadFromDb()` 委托 mapper：存在返回数据 / 不存在返回 `null`。

（参考 `CreditsCacheTest`，约 30 行。）

## 11. 常见问题（FAQ）

**Q1：`get` 和 `getWithMutex` 怎么选？**
绝大多数读用 `get`（无锁开销）；只有"缓存 miss 会打库且打库昂贵/并发高"的热点才用 `getWithMutex`。

**Q2：为什么 `loadFromDb` 要返回 null 而不是抛异常？**
`null` 是"空值"信号，会被缓存防穿透，并由业务层映射为 404。真正的数据库异常应让它抛出，由全局异常处理器兜底（不会被缓存）。

**Q3：为什么关闭了跨节点广播（syncLocal）？**
广播在写频繁时会线性放大（每 key × 每节点），且回源回填的 `put` 也广播，易形成广播风暴。本项目用 30s 本地 TTL 做最终一致，够用且更稳。如需近实时一致，可缩短 `localExpire` 或改用 `REMOTE` 两级。

**Q4：能加二级缓存（本地 + 远程 + DB 之外再加一层）吗？**
当前模板固定 `BOTH`（本地+远程）或 `REMOTE`。若需要更复杂层级，需扩展 `cacheType` 钩子或另写专用实现。
