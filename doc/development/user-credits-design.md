# 设计方案：用户信用点（credits）查询/修改 + JetCache 三级缓存

> 状态：已确认（2026-08-15）——进入编码
> 对应需求：`user-credits-requirement.md`（已确认：增量 delta 语义、扣减不足 409、复用 max-delta）
> 范围：本次引入 JetCache 依赖/配置/启用，仅 credits 消费；points 手写缓存暂不迁移

## 1. 总体结构

沿用 JetCache 迁移后的"缓存访问器独立 Bean + 业务编排"分层：

```
UserCreditsController（接口层，沿用项目简单风格）
   └─ UserCreditsService（业务：参数校验、404/409 语义、DB 增量 + 失效）
        ├─ CreditsCache（JetCache 缓存访问器：@Cached / @CacheInvalidate）
        │     └─ UserMapper（selectUserById / increaseCredits）
        └─ CreditsProperties（app.credits.max-delta）
```

- `CreditsCache` 独立 Bean，避免 self-invocation 使注解失效。
- `UserCreditsService` 不持有 Redis/Caffeine/Redisson 句柄。

## 2. 数据字段与迁移

### 2.1 字段

| 项 | 值 |
|---|---|
| 表 | `users` |
| 字段 | `credits INT NOT NULL DEFAULT 0` |
| 约束 | `CHECK (credits >= 0)`（MySQL 8.0.16+ 强制执行，本项目 8.4） |

### 2.2 Flyway 迁移 `V2__add_credits.sql`

```sql
ALTER TABLE users
    ADD COLUMN credits INT NOT NULL DEFAULT 0 CHECK (credits >= 0);
```

> 说明：V1 已有数据/种子行自动补 0；`CHECK` 在 MySQL 8.4 生效，作为应用层之外的兜底。

## 3. 接口定义

**查询信用点**

```
GET /user/credits?userId=1
```

- 校验：userId 正整数；非法 → 400（ProblemDetail）
- 成功：`HTTP 200` + `Result`：`{ "code": 0, "message": "success", "data": { "userId": 1, "credits": 0 } }`
- 用户不存在：404（`ErrorCode.NOT_FOUND`）

**修改信用点（增量）**

```
POST /user/credits/update?userId=1&delta=100     // delta 可为负（扣减）
```

- 校验：userId 正整数；delta 非 0、`|delta| <= max-delta`（默认 100000）
- 扣减后为负 → 409（`ErrorCode.INSUFFICIENT_CREDITS`）
- 成功返回最新值：`data: { "userId": 1, "credits": 80 }`

## 4. JetCache 三级缓存设计

### 4.1 配置（application.yml）

```yaml
jetcache:
  statIntervalMinutes: 15
  areaInCacheName: false
  local:
    default:
      type: caffeine
      keyConvertor: jackson        # 方法缓存必须指定 keyConvertor（不能为 none）
      limit: 10000
      expireAfterWriteInMillis: 30000
  remote:
    default:
      type: redis.lettuce
      keyConvertor: jackson
      broadcastChannel: default    # 跨节点本地缓存失效广播（remote 级配置，2.7+）
      valueEncoder: java
      valueDecoder: java
      uri: redis://127.0.0.1:6379/
      expireAfterWriteInMillis: 300000
```

> 依据 JetCache 官方 Config：`keyConvertor` 对方法缓存必须是 `fastjson2/jackson` 等（`none` 仅限 `@CreateCache` LOCAL）；`broadcastChannel` 位于 **remote** 配置、用于 `cacheType=BOTH` 时跨 JVM 失效本地缓存。

### 4.2 缓存访问器 `CreditsCache`

```java
@Component
public class CreditsCache {
    // 程序化创建 Cache（JetCache 三级：Caffeine → Redis → MySQL）
    private final Cache<Long, Integer> cache = cacheManager.getOrCreateCache(
            QuickConfig.newBuilder("user:credits")
                    .cacheType(CacheType.BOTH)
                    .expire(Duration.ofSeconds(300))
                    .localExpire(Duration.ofSeconds(30))
                    .localLimit(10000)
                    .cacheNullValue(true)
                    .build());

    public Integer load(Long userId) {                       // 三级读 + 空值缓存 + 分布式锁单飞
        CacheGetResult<Integer> r = cache.GET(userId);
        if (r.isSuccess()) return r.getValue();              // 命中（含缓存 null）
        return loadWithMutex(userId);                        // miss → Redisson 锁单飞回源
    }

    public void invalidate(Long userId) { cache.remove(userId); }
}
```

对照关系：

| 关注点 | 实现 |
|---|---|
| Caffeine 本地一级 | `cacheType=BOTH` + `localExpire=30` |
| Redis 二级 | `expire=300` |
| MySQL 三级回源 | `loadWithMutex` 内 `selectUserById` |
| 空值缓存（穿透） | `cacheNullValue=true` |
| 热点单飞（击穿） | **Redisson 分布式锁**（跨实例单飞，见 §4.3） |
| 写失效 | `cache.remove`（即时失效 + remote broadcast 跨节点） |

### 4.3 分布式锁单飞（升级点）

`@CachePenetrationProtect` 只是 JVM 内单飞，集群下每个实例仍各有一个线程查库。本设计升级为 **Redisson 分布式锁单飞**（与 points 的 `loadWithMutex` 一致）：

```text
loadWithMutex(userId):
  lock = redisson.getLock("user:credits:lock:" + userId)
  if lock.tryLock(0, 10s):                      // 拿到锁：负责回源
      double-check cache.GET(userId) → 命中则返回
      loadFromDb(userId)                        // 查库 + cache.put 回填
  else:                                         // 没拿到锁
      sleep 50ms → 重读 cache.GET(userId) → 命中则返回
      loadFromDb(userId)                        // 兜底直查（保证可用性）
```

- 拿锁线程负责回源并回填；等待线程 50ms 后重读缓存，多数情况命中回填后的 Redis，避免打库。
- 极端下（回源超过等待窗口）等待线程兜底直查 DB，保证可用性（与 points 语义一致）。

## 5. 业务层设计

### 5.1 `UserCreditsService`

```java
public CreditsVO getCredits(Long userId) {
    validateUserId(userId);
    Integer credits = creditsCache.load(userId);
    if (credits == null) throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: userId=" + userId);
    return new CreditsVO(userId, credits);
}

public CreditsVO updateCredits(Long userId, Integer delta) {
    validateUserId(userId);
    if (delta == null || delta == 0) throw new IllegalArgumentException("delta 不能为 0");
    if (Math.abs((long) delta) > props.getMaxDelta()) throw new IllegalArgumentException("delta 超出上限");

    int updated = userMapper.increaseCredits(userId, delta);
    if (updated == 0) {
        // 区分「用户不存在(404)」与「扣减后为负(409)」
        boolean exists = userMapper.selectUserById(userId).isPresent();
        if (!exists) throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: userId=" + userId);
        throw new BusinessException(ErrorCode.INSUFFICIENT_CREDITS, "信用点不足: userId=" + userId);
    }

    creditsCache.invalidate(userId);
    return getCredits(userId);               // 回读最新值（走缓存回填）
}
```

### 5.2 Mapper：非负守卫 SQL

`UserMapper` 新增 `increaseCredits`，XML：

```xml
<update id="increaseCredits">
    UPDATE users SET credits = credits + #{delta}
    WHERE id = #{userId}
      AND credits + #{delta} &gt;= 0
</update>
```

- 返回 0 = 用户不存在 **或** 扣减后为负，二者由业务层 `selectUserById` 二次判定（见 §5.1）。
- 原子：DB 层面保证并发下不扣成负、不丢更新。

## 6. 错误码与配置

### 6.1 ErrorCode 新增

```java
INSUFFICIENT_CREDITS(HttpStatus.CONFLICT, "信用点不足")
```

`GlobalExceptionHandler` 已统一把 `BusinessException` 转 ProblemDetail，无需改动。

### 6.2 CreditsProperties

```java
@ConfigurationProperties(prefix = "app.credits")
public class CreditsProperties { private int maxDelta = 100000; }
```

```yaml
app:
  credits:
    max-delta: 100000
```

## 7. DTO / 实体改动

- 新增 `CreditsVO(Long userId, Integer credits)`（record）。
- `User` 实体新增 `credits` 字段（`Integer credits`，映射 `credits`）。

## 8. 文件清单

新增：
- `user-service/src/main/resources/db/migration/V2__add_credits.sql`
- `user-service/.../dto/CreditsVO.java`
- `user-service/.../service/CreditsCache.java`
- `user-service/.../service/UserCreditsService.java`
- `user-service/.../controller/UserCreditsController.java`
- `user-service/.../config/CreditsProperties.java`

修改：
- `User.java`（+credits）、`UserMapper.java`/`UserMapper.xml`（+increaseCredits）
- `sca-common/.../ErrorCode.java`（+INSUFFICIENT_CREDITS）
- `application.yml`（+jetcache 段、+app.credits）
- `pom.xml`（+jetcache-starter-redis-lettuce，若 JetCache 前置未落地）

## 9. 测试方案

| 测试 | 内容 |
|---|---|
| `UserCreditsServiceTest` | 查询命中/404、更新成功/404/409、delta 非法、失效后回读 |
| `UserCreditsControllerTest`（MockMvc） | 参数校验 400、成功返回 Result JSON |
| `UserMapperXmlTest` 扩展 | users 表加 credits 列；`increaseCredits` 累加、扣成负返回 0、CHECK 拒绝负值 |
| `CreditsCacheTest`（可选） | `@Cached` 命中不查库、`cacheNullValue` 缓存 NULL |
| 回归 | `mvn -pl user-service -am test` 全绿 |

## 10. 风险与边界

1. **JetCache 前置依赖**：若 JetCache 尚未编码落地，本需求需先完成 `jetcache-migration-design.md` 的实施（依赖/配置/`@EnableMethodCache`），或将两者合并实施。
2. **`broadcastChannel` 键名**：以 2.7.7 为准；未启用时回退到"本地 30s TTL 最终一致"（功能仍正确）。
3. **TTL 抖动**：JetCache 无内置 jitter，本需求接受固定 TTL（30s/5min），雪崩风险低。
4. **404 vs 409 判定**：`increaseCredits` 返回 0 后二次 `selectUserById` 判定；极端并发下用户被删会误判，演示场景可接受。
5. **CHECK 兼容**：H2 `MODE=MySQL` 下 CHECK 行为需实测；若 H2 不强制执行，非负仍由 SQL 守卫 + 应用层保证。
6. **序列化**：`valueEncoder: java` 默认；若需 Redis 值可读，改 `jackson`（独立决策，不影响本需求功能）。
