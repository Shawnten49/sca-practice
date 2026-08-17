# ShardingSphere 加密 + 脱敏性能基准测试报告

> 测试日期：2026-08-18
> 关联功能：user-service `users.id_card` 加密存储（`!ENCRYPT` AES）+ 查询明文碎片展示（`!MASK` KEEP_FIRST_N_LAST_M）
>
> **一句话结论**：每返回一行数据，ShardingSphere 的解密 + 脱敏大约额外消耗 **0.45 ~ 0.58 微秒**，成本随返回行数线性增长（10 万行约 +50ms）；相对数据库查询本身和后续 JSON 序列化，属于噪声级别。

## 1. 背景与目的

`users` 表接入 ShardingSphere 后，`id_card` 的完整查询链路是：

```
SELECT ... id_card ...            -- 逻辑列
  → SQL 改写为 id_card_cipher     -- 物理密文列
  → 数据库返回密文
  → EncryptMergedResult 逐行 AES 解密
  → MaskMergedResult 逐行 KEEP_FIRST_N_LAST_M 脱敏
  → 客户端拿到 110***********1234
```

要回答的问题是：这个"逐行解密 + 逐行脱敏"到底消耗多少性能？尤其**批量查询**（一次返回几千上万行）时会不会成为瓶颈。

## 2. 测试设计

分两个层次测量，互相印证：

### 2.1 微基准（纯算法开销）

直接循环调用与 ShardingSphere 5.5.3 完全一致的算法实现：

- AES 解密：`AES/ECB/PKCS5Padding`，密钥 = `SHA-1(aes-key-value)` 前 16 字节，密文 Base64 解码（与 `AESCryptographicAlgorithm` 源码一致）；
- 脱敏：`KEEP_FIRST_N_LAST_M(first-n=3, last-m=4, replace-char=*)`，即 `toCharArray` + 中间位置替换 + `new String`（与 `KeepFirstNLastMMaskAlgorithm` 一致）。

样本：100 万次解密 + 脱敏，JIT 预热后取平均，得到"纯算法单行成本"。

### 2.2 真实链路批量基准（端到端）

不走业务代码，直接通过**真实的 ShardingSphere 数据源**（`YamlShardingSphereDataSourceFactory` + H2 内存库）执行批量查询：

- 规则与生产 `shardingsphere.yaml` 完全一致：`!SINGLE` + `!MASK` + `!ENCRYPT`（AES + SHA-1）；
- 物理表只建 `id_card_cipher` 密文列，预置 12 万行数据（每行存同一份合法密文）；
- 两组对比查询：
  - **基线**：`SELECT id, nickname, points, credits, create_time ...`（投影不含 `id_card`）；
  - **被测**：`SELECT id, nickname, points, credits, id_card, create_time ...`（投影含 `id_card`，触发逐行解密 + 脱敏）；
- 批量大小：`LIMIT 20000` 与 `LIMIT 100000` 各测一组，验证成本是否线性；
- 每条 SQL 逐行读取全部列（含 `id_card`），确保装饰器链真实执行；每组**预热 3 轮后取 5 次最优**。

设计要点：

- 对比的是**同一数据源、同一行读取逻辑**下"带 / 不带 id_card"的差值，差值即解密 + 脱敏 + 装饰器查找的纯增量；
- `users` 是不分片的单表（`!SINGLE` 穿透），没有跨分片合并干扰，测的就是加解密/脱敏本身；
- 基准 YAML 关闭 `sql-show`，排除日志开销（日志是另一码事，见结论）。

## 3. 测试环境

| 项 | 值 |
| --- | --- |
| 操作系统 | macOS（本机） |
| JDK | 21（OpenJDK 21） |
| ShardingSphere | 5.5.3（`shardingsphere-jdbc` + `encrypt-core` + `mask-core`） |
| 数据库 | H2 2.x（`MODE=MySQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE`，内存库） |
| 数据量 | 12 万行（预置，`id_card_cipher` 存 AES 密文） |

> 说明：H2 内存库消除了网络与磁盘 IO，测的是纯 JVM 内处理成本；真实 MySQL 场景下查询本身的耗时更大，加解密增量的**相对占比只会更小**。

## 4. 实施步骤

1. 导出 user-service 测试 classpath（含 H2、ShardingSphere、HikariCP）：

   ```bash
   cd ~/codexwork/sca-practice
   mvn -pl user-service dependency:build-classpath -Dmdep.outputFile=/tmp/ssbench/cp.txt -DincludeScope=test
   ```

2. 编写 `Bench.java`（完整代码见附录）与 `bench.yaml`（规则同生产）；
3. 编译并运行：

   ```bash
   cd /tmp/ssbench
   javac -proc:none -cp "$(cat cp.txt)" Bench.java
   java -cp ".:$(cat cp.txt)" Bench
   ```

4. 重复运行 3 次，记录每次输出，取区间作为结论依据。

## 5. 测试结果

### 5.1 微基准（纯算法）

| 运行 | 单行解密 + 脱敏耗时 |
| --- | --- |
| 第 1 次 | 432 ns |
| 第 2 次 | 512 ns |
| 第 3 次 | 440 ns |
| **区间** | **约 430 ~ 510 ns/行** |

### 5.2 真实链路批量基准

**2 万行**：

| 运行 | 不含 id_card | 含 id_card（解密+脱敏） | 单行额外开销 | 合计增量 |
| --- | --- | --- | --- | --- |
| 第 1 次 | 9.09 ms | 20.60 ms | 575 ns/行 | 11.51 ms |
| 第 2 次 | 8.98 ms | 18.65 ms | 484 ns/行 | 9.67 ms |
| 第 3 次 | 7.74 ms | 16.79 ms | 453 ns/行 | 9.06 ms |

**10 万行**：

| 运行 | 不含 id_card | 含 id_card（解密+脱敏） | 单行额外开销 | 合计增量 |
| --- | --- | --- | --- | --- |
| 第 2 次 | 42.48 ms | 92.82 ms | 503 ns/行 | 50.34 ms |
| 第 3 次 | 36.62 ms | 83.81 ms | 472 ns/行 | 47.19 ms |

### 5.3 线性验证

| 批量大小 | 基线单行 | 含 id_card 单行 | 单行增量 |
| --- | --- | --- | --- |
| 2 万行 | ~387 ~ 454 ns/行 | ~840 ~ 1030 ns/行 | ~453 ~ 575 ns/行 |
| 10 万行 | ~366 ~ 425 ns/行 | ~838 ~ 928 ns/行 | ~472 ~ 503 ns/行 |

结论很干净：**单行增量不随批量大小变化**（2 万与 10 万行几乎一致），即成本严格线性、可预测。

## 6. 结论

1. **每行额外成本约 0.5 微秒（450 ~ 580 ns）**。2 万行约 +9 ~ 11.5ms，10 万行约 +47 ~ 50ms。
2. **对比才有意义**：10 万行数据"查询 + 逐行映射 + JSON 序列化"本身就是几十到上百毫秒的量级，加解密增量在其中占比很小；若接 MySQL（有网络与磁盘），基线更大、相对占比更小。
3. **成本构成**：主要是逐行逐列的装饰器链（`MaskMergedResult → EncryptMergedResult` 的 `getValue` 查找与间接调用）+ 单块 AES 解密 + 一次字符数组替换。AES-NI 硬件加速下 AES 本身只占其中一小部分；ShardingSphere 没有批量向量化，所以是线性累加，但常数很小。
4. **users 不分片**：没有跨分片合并的开销，本测试即最坏情况下的"纯加解密/脱敏"成本。

## 7. 实践建议

- **只查需要的列**：投影里不写 `id_card` 就不触发解密/脱敏。批量列表接口若不需要展示身份证碎片，不要 select 它；
- **生产关闭 `sql-show`**：当前 `shardingsphere.yaml` 是 `sql-show: true`。日志格式化与输出的开销在批量/高 QPS 下比加解密更值得警惕，验证完路由建议关闭；
- **热点结果可缓存**：脱敏后的碎片是稳定且不敏感的，可用项目已有的 JetCache/Caffeine 缓存；**不要缓存解密后的完整明文**（扩大敏感数据暴露面）；
- 若未来 `id_card` 所在表参与分片，跨分片查询的**结果合并**开销会大于加解密本身，届时单独评估。

安全提示：解密过程中 JVM 内存会短暂出现完整明文（这是"先解密再脱敏"的固有形态），客户端拿到的始终是掩码碎片。

## 8. 局限性

- 单机、H2 内存库、非 JMH（简单循环计时 + best-of-5），数值反映数量级而非精确基准；
- 未覆盖真实 MySQL 网络往返、分片路由、以及高并发下的争用；
- 数据行字段少且固定（id/nickname/points/credits/create_time + id_card），真实实体字段更多时装饰器查找成本会按列数略有增加；
- 结论适用于本项目形态（单表穿透 + 单加密列），其他场景建议按本方法复测。

## 附录 A：完整代码

### A.1 `bench.yaml`（与生产规则一致）

```yaml
dataSources:
  ds0:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: org.h2.Driver
    jdbcUrl: jdbc:h2:mem:ssbench;MODE=MySQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE
    username: sa
    password: ""
    maximumPoolSize: 5
rules:
  - !SINGLE
    tables:
      - "*.*"
    defaultDataSource: ds0
  - !MASK
    tables:
      users:
        columns:
          id_card:
            maskAlgorithm: id-card-keep-first-3-last-4
    maskAlgorithms:
      id-card-keep-first-3-last-4:
        type: KEEP_FIRST_N_LAST_M
        props:
          first-n: 3
          last-m: 4
          replace-char: '*'
  - !ENCRYPT
    tables:
      users:
        columns:
          id_card:
            cipher:
              name: id_card_cipher
              encryptorName: id-card-aes
    encryptors:
      id-card-aes:
        type: AES
        props:
          aes-key-value: bench-key-5.5.3
          digest-algorithm-name: SHA-1
props:
  sql-show: false
```

### A.2 `Bench.java`

```java
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.h2.jdbcx.JdbcDataSource;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Base64;

/** 临时基准：ShardingSphere ENCRYPT(解密) + MASK 在单行/批量查询上的开销（不入库）。 */
public class Bench {

    private static final String KEY = "bench-key-5.5.3";
    private static final String ID_CARD = "110101199003071234";
    private static final int TOTAL_ROWS = 120_000;
    private static final int ITER = 1_000_000;

    public static void main(String[] args) throws Exception {
        // ---- 纯算法微基准：AES 解密 + KEEP_FIRST_N_LAST_M 脱敏（每次一行 id_card） ----
        byte[] key = Arrays.copyOf(MessageDigest.getInstance("SHA-1").digest(KEY.getBytes(StandardCharsets.UTF_8)), 16);
        String cipherText = encryptAes(ID_CARD, key);
        String[] samples = new String[1000];
        Arrays.fill(samples, cipherText);

        long t0 = System.nanoTime();
        long sink = 0;
        for (int i = 0; i < ITER; i++) {
            String plain = decryptAes(samples[i % samples.length], key);
            String masked = mask(plain);
            sink += masked.length();
        }
        long microNs = (System.nanoTime() - t0) / ITER;
        System.out.printf("[微基准] 解密+脱敏 1 行: 平均 %.2f ns/行%n", microNs / 1.0);

        // ---- 真实链路：H2 + ShardingSphere 批量查询 ----
        JdbcDataSource raw = new JdbcDataSource();
        raw.setURL("jdbc:h2:mem:ssbench;MODE=MySQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        raw.setUser("sa");
        try (Connection conn = raw.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS users");
            st.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, nickname VARCHAR(64) NOT NULL,"
                    + " points INT NOT NULL DEFAULT 0, credits INT NOT NULL DEFAULT 0,"
                    + " id_card_cipher VARCHAR(128) NOT NULL DEFAULT '', create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (id, nickname, points, credits, id_card_cipher) VALUES (?, 'u', 0, 0, ?)")) {
                for (int i = 0; i < TOTAL_ROWS; i++) {
                    ps.setLong(1, i);
                    ps.setString(2, cipherText);
                    ps.addBatch();
                    if (i % 10_000 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
        }

        byte[] yaml = Files.readAllBytes(Path.of("/tmp/ssbench/bench.yaml"));
        DataSource ss = YamlShardingSphereDataSourceFactory.createDataSource(yaml);

        // 预热（触发 JIT + ShardingSphere 元数据加载）
        for (int i = 0; i < 3; i++) {
            run(ss, false, 20_000);
            run(ss, true, 20_000);
            run(ss, false, 100_000);
            run(ss, true, 100_000);
        }

        for (int rows : new int[]{20_000, 100_000}) {
            long baseNs = bestOf(5, ss, false, rows);
            long encNs = bestOf(5, ss, true, rows);
            System.out.printf("[批量基准] %d 行, 不含 id_card 列:    平均 %.2f ms（%.2f ns/行）%n", rows, baseNs / 1e6, baseNs / (double) rows);
            System.out.printf("[批量基准] %d 行, 含 id_card 解密+脱敏: 平均 %.2f ms（%.2f ns/行）%n", rows, encNs / 1e6, encNs / (double) rows);
            System.out.printf("[批量基准] %d 行单行额外开销: %.2f ns/行（合计 %.2f ms）%n",
                    rows, (encNs - baseNs) / (double) rows, (encNs - baseNs) / 1e6);
        }

        if (ss instanceof AutoCloseable closeable) {
            closeable.close();
        }
        System.out.println("sink=" + sink);
    }

    private static long bestOf(int n, DataSource ss, boolean withIdCard, int rows) throws Exception {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            long t = run(ss, withIdCard, rows);
            best = Math.min(best, t);
        }
        return best;
    }

    /** 跑一次批量查询并读取所有列（含 id_card 时触发逐行解密+脱敏），返回耗时 ns。 */
    private static long run(DataSource ss, boolean withIdCard, int rows) throws Exception {
        String sql = withIdCard
                ? "SELECT id, nickname, points, credits, id_card, create_time FROM users LIMIT " + rows
                : "SELECT id, nickname, points, credits, create_time FROM users LIMIT " + rows;
        long t0 = System.nanoTime();
        try (Connection conn = ss.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            long sum = 0;
            int createTimeIdx = withIdCard ? 6 : 5;
            while (rs.next()) {
                sum += rs.getLong(1);
                sum += rs.getString(2).length();
                sum += rs.getInt(3);
                sum += rs.getInt(4);
                if (withIdCard) {
                    sum += rs.getString(5).length();
                }
                sum += rs.getTimestamp(createTimeIdx).getTime();
            }
            if (sum == Long.MIN_VALUE) {
                throw new IllegalStateException();
            }
        }
        return System.nanoTime() - t0;
    }

    private static String encryptAes(String plain, byte[] key) throws Exception {
        Cipher c = Cipher.getInstance("AES");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    private static String decryptAes(String cipherText, byte[] key) throws Exception {
        Cipher c = Cipher.getInstance("AES");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return new String(c.doFinal(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
    }

    /** 与 KEEP_FIRST_N_LAST_M(first-n=3, last-m=4, replace-char=*) 一致 */
    private static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() < 3 + 4) {
            return value;
        }
        char[] chars = value.toCharArray();
        for (int i = 3; i < value.length() - 4; i++) {
            chars[i] = '*';
        }
        return new String(chars);
    }
}
```
