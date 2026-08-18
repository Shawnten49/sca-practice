# user-service 用户画像模块需求文档

> 状态：**已评审通过**（2026-08-19，评审结论见第 7 节）——已进入设计阶段
>
> 相关环境：MongoDB Community 8.0.28 已运行于 `127.0.0.1:27017`（brew mongodb-community@8.0，脚本 `scripts/start-mongodb.sh`）

## 1. 背景与目标

用户画像数据（昵称、标签、扩展信息）需要**灵活存储**：

- `tags` 是字符串标签数组，业务上会随时增减；
- `extra` 是扩展字段（`Map<String, Object>`），不同场景可能塞入不同字段，**不能因为加字段就改表结构**。

关系型表的“加列/加表”成本高，无法满足这种自由度；MongoDB 的**文档模型**天然适合：一个用户一个文档，字段随意增删。因此在 user-service 中新增**用户画像**模块：

- 数据存入 MongoDB（本地 127.0.0.1:27017）；
- 提供**保存**与**查询**两个接口；
- 保存按 `user_id` 幂等覆盖（upsert），查询按 `user_id` 获取完整画像。

## 2. 范围

**本期包含：**

- user-service 引入 `spring-boot-starter-data-mongodb`，接入本地 MongoDB；
- 用户画像文档模型（`user_profile` collection）；
- 保存接口（按 userId upsert）；
- 查询接口（按 userId 查询）。

**本期不包含：**

- 画像删除接口（如需可后续补充）；
- 标签推荐 / 画像分析等业务能力；
- MongoDB 集群、多环境部署、备份恢复；
- 画像数据与 users 主表的强一致性同步（画像独立维护，不依赖 users 表）。

## 3. 功能需求

### 3.1 保存接口

- `POST /user/profile/save`
- 请求体（`UserProfileSaveRequest`）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| userId | Long | 是 | 用户ID，正整数 |
| nickname | String | 是 | 昵称，≤ 64 字符 |
| tags | List\<String\> | 否 | 标签数组，可空；上限待评审（默认 50 个，每个 ≤ 32 字符） |
| extra | Map\<String, Object\> | 否 | 扩展字段，键值自由；上限待评审 |

- 行为：按 `userId` **upsert**——不存在则插入，存在则**全量覆盖** nickname / tags / extra；
- 幂等：同一份请求重复提交，最终数据一致（覆盖写）。

### 3.2 查询接口

- `GET /user/profile?userId=1`
- 返回 `Result<UserProfileResponse>`，字段同文档模型（userId / nickname / tags / extra / 时间戳）；
- 画像不存在时的返回（待评审，见第 7 节）。

## 4. 数据模型

MongoDB collection：`user_profile`

| 字段 | 类型 | 说明 |
|---|---|---|
| userId | Long | 业务主键，**唯一索引** |
| nickname | String | 昵称 |
| tags | List\<String\> | 标签数组 |
| extra | Map\<String, Object\> | 扩展字段（文档模型，天然支持增删字段） |
| createTime | LocalDateTime | 首次创建时间 |
| updateTime | LocalDateTime | 最近更新时间 |

## 5. 非功能需求

1. **参数校验**：userId 必填正整数；nickname 必填且 ≤ 64；tags / extra 按评审确定的限制校验；
2. **幂等/覆盖语义**：重复保存结果一致；
3. **快速失败**：MongoDB 不可用或超时时接口快速报错，不静默吞异常；
4. **可观测**：保存/查询记录日志（userId、耗时）。

## 6. 验收标准

1. 调用保存接口后，查询接口能返回刚保存的画像；
2. 修改 tags（增减标签）或 extra（增删字段）后再次保存，查询返回最新值——**验证无需改表结构**；
3. `mongosh` 中可见 `user_profile` 集合及对应文档；
4. 参数非法（userId 非正整数等）返回明确错误。

## 7. 评审结论

| # | 问题 | 结论 |
|---|---|---|
| 1 | 保存语义：全量覆盖 vs 字段合并 | **全量覆盖（upsert）** |
| 2 | 查询不存在：返回 404 vs `data=null` | **`data=null`** |
| 3 | tags 数量上限 / 单个标签长度 / extra 键值大小限制 | **tags ≤ 50、单个 ≤ 32 字符；extra 键 ≤ 20** |
| 4 | 是否需要 createTime / updateTime | **保留** |
| 5 | MongoDB 库名（默认 `user_profile`？）与连接串 | **`mongodb://127.0.0.1:27017/user_profile`** |

---

> 需求评审通过后，进入设计文档（MongoDB 接入、文档模型与索引、接口实现、测试方案）。
