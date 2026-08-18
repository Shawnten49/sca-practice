# user-service 用户画像模块设计方案

> 状态：**已评审通过**（2026-08-19）——已进入编码阶段
>
> 需求文档：`doc/development/user-profile-requirement.md`（评审已通过）
>
> 评审结论（需求）：全量覆盖保存；查询不存在返回 `data=null`；tags ≤ 50 个且每个 ≤ 32 字符、extra 键 ≤ 20 个；保留 createTime/updateTime；MongoDB `mongodb://127.0.0.1:27017/user_profile`

## 1. 技术选型

| 关注点 | 方案 |
|---|---|
| MongoDB 接入 | `spring-boot-starter-data-mongodb`（Spring Data MongoDB） |
| 数据访问 | `MongoTemplate`（upsert 原子更新 + findById 查询，不建 Repository 接口，减少样板） |
| 文档模型 | `@Document("user_profile")`，**userId 直接作为 `_id`**（Long），天然唯一，无需额外索引 |
| 接口风格 | 与项目一致：Controller 薄 + Service 校验 + `Result<T>` 包装 |
| 对象转换 | MapStruct（`UserProfileConverter`），符合命名规范 |

## 2. 数据模型

```java
@Document("user_profile")
public class UserProfile {

    @Id
    private Long userId;            // 业务主键直接作 _id

    private String nickname;

    private List<String> tags;      // 标签数组

    private Map<String, Object> extra;   // 扩展字段，文档模型天然支持增删

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
```

**userId 作 `_id` 的收益**：唯一性由 MongoDB 主键保证，省掉唯一索引；保存/查询都按 `_id` 走，简单高效。

## 3. 接口设计

### 3.1 保存（全量覆盖 upsert）

```
POST /user/profile/save
Content-Type: application/json

{
  "userId": 1,
  "nickname": "demo",
  "tags": ["vip", "new"],
  "extra": { "level": 5, "region": "sh" }
}
```

返回：`Result<UserProfileResponse>`

实现：`MongoTemplate.upsert` 一步完成：

```java
Query query = Query.query(Criteria.where("_id").is(userId));
Update update = new Update()
        .set("nickname", nickname)
        .set("tags", tags)
        .set("extra", extra)
        .set("updateTime", now)
        .setOnInsert("createTime", now);
mongoTemplate.upsert(query, update, UserProfile.class);
```

- 存在 → 全量覆盖 nickname/tags/extra，更新 updateTime；
- 不存在 → 插入，createTime/updateTime 同时落 now；
- 原子、幂等：重复提交最终数据一致。

保存成功后回查一次返回完整画像（与项目 save 后回查风格一致）。

### 3.2 查询

```
GET /user/profile?userId=1
```

返回：`Result<UserProfileResponse>`；不存在时 `data=null`（不抛 404）。

## 4. 模型与转换

| 类 | 包 | 说明 |
|---|---|---|
| `UserProfile` | `com.example.user.entity` | MongoDB 文档实体 |
| `UserProfileSaveRequest` | `com.example.user.dto.request` | record：userId / nickname / tags / extra |
| `UserProfileResponse` | `com.example.user.dto.response` | record：userId / nickname / tags / extra / createTime / updateTime（现阶段仅 user-service 使用，按“重复才提取”规则留在服务内） |
| `UserProfileConverter` | `com.example.user.converter` | MapStruct：`toEntity(SaveRequest)`、`toResponse(UserProfile)` |

## 5. 参数校验（Service 层，项目风格）

| 字段 | 规则 |
|---|---|
| userId | 必填，正整数 |
| nickname | 必填，trim 后非空，≤ 64 |
| tags | 可为 null（规范化为空列表）；≤ 50 个；每个 trim 后非空且 ≤ 32 |
| extra | 可为 null（规范化为空 Map）；键 ≤ 20 个，键 trim 后非空 |

非法参数抛 `IllegalArgumentException`（由全局异常处理器统一包装）。

## 6. 配置

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://127.0.0.1:27017/user_profile
```

pom 新增：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
```

## 7. 测试方案

1. **UserProfileServiceTest**（mock MongoTemplate）：
   - 保存：验证 upsert 的 query（`_id`）、update 字段、`setOnInsert(createTime)`；
   - 保存后回查返回完整画像；
   - 查询命中 / 不存在返回 null；
   - 校验失败：userId 非正、nickname 超长、tags 超 50、extra 键超 20。
2. **UserProfileControllerTest**（MockMvc，mock Service）：
   - 保存返回 `Result.ok` 与画像 JSON；
   - 查询命中 / 不存在 `data=null`；
   - 非法参数由全局异常处理返回错误。

## 8. 涉及文件清单

| 文件 | 说明 |
|---|---|
| `user-service/pom.xml` | + spring-boot-starter-data-mongodb |
| `user-service/src/main/resources/application.yml` | + spring.data.mongodb.uri |
| `entity/UserProfile.java` | MongoDB 文档实体 |
| `dto/request/UserProfileSaveRequest.java` | 保存入参 |
| `dto/response/UserProfileResponse.java` | 出参 |
| `converter/UserProfileConverter.java` | MapStruct 转换 |
| `service/UserProfileService.java` | upsert / 查询 / 校验 |
| `controller/UserProfileController.java` | 两个接口 |
| 测试 | `UserProfileServiceTest`、`UserProfileControllerTest` |

## 9. 风险与说明

1. **MongoDB 未启动**：接口快速失败（连接超时/异常），不静默；
2. **extra 值类型**：任意 JSON 类型（数字/字符串/布尔/嵌套 Map/List）均支持，Mongo 存 BSON，无需预定义；
3. **userId 作 `_id`**：MongoDB 主键不可变，本项目 userId 不变更，无影响；
4. **无强一致需求**：画像独立维护，不依赖 users 主表，保存接口不校验用户是否存在（画像允许先于用户主数据存在）。

> 设计评审通过后进入编码阶段。
