# 设计方案：积分缓存一致性修复（Cache-Aside 竞态 + 延迟双删）

> 状态：已确认（2026-08-15），延迟执行器选定 A（Redisson RDelayedQueue）
> 关联：`user-points-design.md`（三级缓存整体设计）；本次仅改"写路径缓存失效"策略，不涉及 #12（DB schema 约束）

## 1. 背景与问题

当前写路径（`UserPointsService.updatePoints` 与 `PointMqConsumer` → `invalidatePoints`）采用 Cache-Aside "先写库、后删缓存"，存在经典竞态窗口：

```text
T1  读线程 A：Caffeine/Redis miss → 读 MySQL 得到旧值 points=100
T2  写线程 B：UPDATE users SET points = points + 50   （DB 变成 150）
T3  写线程 B：删除 Redis / Caffeine 缓存
T4  读线程 A：把旧值 100 回填进 Redis / Caffeine
结果：缓存驻留脏数据 100，直到 TTL 过期（Caffeine 30s / Redis 最长 5min）
```

根因：读线程"读旧值 → 回填"跨越了写线程的"删缓存"点，最后一次删除发生在回填之前。

## 2. 影响范围

| 路径 | 位置 | 现状 |
|---|---|---|
| HTTP 调整积分 | `UserPointsService.updatePoints` | 写库后立即删缓存 + 回读回填 |
| MQ 加积分 | `PointMqConsumer` → `UserPointsService.invalidatePoints` | 事务提交后删缓存 |

两条路径共用"删缓存"逻辑，需统一升级，否则任一路径都可能读到加/改积分前的旧值。

## 3. 方案对比

| 方案 | 机制 | 能否彻底消除竞态 | 成本 / 复杂度 | 结论 |
|---|---|---|---|---|
| **A. 延迟双删** | 写前删 → 写库 → 延时 Δ 后再删 | 否（缩小到"读延迟"窗口） | 低，需一个延时任务执行器 | ✅ 推荐 |
| B. 版本号 / 时间戳 | 缓存值带 version，回填前比对 | 是 | 中，需 DB 加版本字段（属 #12，已略过） | ❌ |
| C. 读写全程加锁 | 读回填前也持锁 | 是 | 高，热点 key 锁竞争大 | ❌ |
| D. Canal / binlog 订阅 | 订阅 binlog 异步删缓存 | 是 | 高，引入 Canal 基础设施 | ❌ |

**结论**：选 A。理由：
1. 无需 DB schema 变更，与略过 #12 一致；
2. 复用已引入的 Redisson；
3. 积分是"最终一致"场景，剩余短窗口可接受。

> 说明：延迟双删是"缩小竞态窗口"而非数学上彻底消除——若读线程从读旧值到回填耗时 > Δ，仍可能脏。彻底消除需版本号（方案 B），留作后续 #12 一起评估。

## 4. 详细设计

### 4.1 时序（updatePoints）

```text
updatePoints(userId, delta):
  ① 校验 userId / delta（不变）
  ② invalidateNow(userId)                     # 写前删：清空两级缓存（新增）
  ③ updated = userMapper.increasePoints(...)   # DB 原子增量（不变）
     updated == 0 → 404
  ④ invalidatePoints(userId)                   # 写后删：立即删 + 投递延迟删（升级）
  ⑤ return toVo(userId, loadFromDb(...))       # 回读最新值并回填（不变）
```

- ② 写前删：写库期间进来的读请求不再命中旧缓存，改为回源（可能读到旧库值并回填，由 ④ 的延迟删兜底）。
- ④ 写后删：拆成两部分——**立即删**（保证本请求返回后缓存即刻失效）+ **投递延迟删**（Δ 后再次删，兜住 ②~④ 期间读旧值并回填的竞态）。
- ⑤ 回填的新值可能被 ④ 的延迟删再清掉一次，代价是"下次读多一次 DB 回源"，正确性不受影响。

### 4.2 方法契约（UserPointsService）

| 方法 | 可见性 | 语义 |
|---|---|---|
| `invalidateNow(Long userId)` | private | 只删 Redis + Caffeine，不投递延迟任务 |
| `invalidatePoints(Long userId)` | public | **立即删 + 投递延迟删**（对外唯一入口） |
| `submitDelayedInvalidate(Long userId)` | private | 向延迟队列投递"Δ 后删除该 userId 缓存" |

- `updatePoints`：② 用 `invalidateNow`，④ 用 `invalidatePoints`。
- `PointMqConsumer`：事务提交后调用 `invalidatePoints`（对外契约不变，内部自动带延迟删）。

### 4.3 延迟执行器（待确认点）

| 方案 | 机制 | 优点 | 缺点 |
|---|---|---|---|
| **A. Redisson RDelayedQueue（推荐）** | 延迟任务持久化到 Redis，常驻线程 `queue.take()` 消费 | 跨实例可靠、JVM 重启不丢、复用已引入的 Redisson | 需一个常驻监听线程 + 生命周期管理 |
| B. ScheduledExecutorService / DelayQueue | 内存延时任务 | 实现最简 | 任务不持久，JVM 重启即丢（脏数据靠 TTL 兜底） |

**推荐 A**，与现有 RLock 同源、语义统一。实现要点：

```java
RQueue<Long> queue = redissonClient.getQueue("user:points:evict:queue");
RDelayedQueue<Long> delayedQueue = redissonClient.getDelayedQueue(queue);

// 投递：Δ 后从延迟队列转入 queue
delayedQueue.offer(userId, delay.toMillis(), TimeUnit.MILLISECONDS);

// 消费：后台线程阻塞取，取到即执行 invalidateNow(userId)
Long userId = queue.take();
```

监听器生命周期：新建 `PointsCacheEvictListener implements SmartLifecycle`，`start()` 启动 daemon 线程循环消费，`stop()` 中断退出。

### 4.4 延迟时长 Δ

- Δ 需 ≥ "读线程从读旧库值到回填缓存"的最大耗时 ≈ DB 读延迟 + 回填耗时（本机 DB 读 < 50ms）。
- 取安全裕量：**默认 500ms，可配置** `app.points-cache.double-delete-delay`。
- Δ 越大兜底越强，但会拉长"回填新值被延迟删掉 → 下次多一次回源"的窗口；500ms~1s 为合理区间。

### 4.5 幂等与失败处理

- 同一 userId 多次更新会投递多个延迟删任务；删除不存在的 key 无害，天然幂等。
- 延迟删执行失败（Redis 抖动）：catch + `log.warn`，不抛，靠 TTL 最终兜底。
- 延迟任务丢失（Redis 重启 / 进程强杀）：靠 TTL 兜底，最终一致。

## 5. 配置项新增

```yaml
app:
  points-cache:
    # ...现有 redis-ttl / caffeine-ttl 等不变
    double-delete-delay: 500ms   # 延迟双删的延时（新增）
```

`PointsCacheProperties` 增加 `Duration doubleDeleteDelay = Duration.ofMillis(500)`。

## 6. 文件清单

- `user-service/.../service/UserPointsService.java`：拆分 `invalidateNow` / `invalidatePoints`，接入延迟删
- `user-service/.../config/PointsCacheProperties.java`：新增 `double-delete-delay`
- `user-service/.../config/PointsCacheEvictListener.java`（新增）：延迟队列监听 + 生命周期（若选方案 A）
- `application.yml`：新增 `double-delete-delay`
- 测试：`UserPointsServiceTest` 增加"写前删 + 投递延迟删"验证；新增延迟队列监听/执行单测（mock 队列）

## 7. 测试方案

| 测试 | 内容 |
|---|---|
| `UserPointsServiceTest` | 白盒验证：updatePoints 先 `invalidateNow`、后 `invalidatePoints`（verify 投递延迟任务）；MQ 路径 `invalidatePoints` 触发延迟删 |
| `PointsCacheEvictListenerTest`（若选 A） | mock RQueue/RDelayedQueue：投递 → 到期 take → 执行删除；stop 中断退出 |
| 竞态本身 | 单测难以精确复现，用"投递被调用 + 到期执行删除"白盒验证，附注释说明窗口边界 |
| 回归 | `mvn -pl user-service -am test` 全绿 |

## 8. 风险与边界

1. 延迟双删仍是"缩小窗口"，非彻底消除；彻底方案（版本号）需 DB schema 变更，留待 #12 一起评估。
2. 方案 A 引入常驻线程 + 对 Redis 的延迟队列依赖；Redis 不可用时延迟删丢失，靠 TTL 兜底。
3. 延迟删会删掉写后回填的新值，多一次回源（可接受）。
4. 本设计不改变对外接口签名与响应结构，现有测试语义保持一致。
