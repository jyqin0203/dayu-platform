# 复现与实验边界

工作区只增加本评估目录；应用业务代码未修改。运行物、随机凭据、真实元数据快照及合成文件位于 `/tmp/dayu-redis-eval-20261008`（0700）。不要提交该目录。

## 初次准备

依赖使用服务器已有 JDK、MariaDB、Redis 和部署 jar，不安装软件。先确认 13309、16389、18580～18583 空闲。当前保留的实验已经完成验证、库内已切为合成数据；**不可直接在其上重跑性能脚本**。完整复现应使用新的隔离目录，或经用户确认清理后重新准备。脚本固定了实验根目录，迁移环境需一致调整。

```bash
cd /home/ubuntu/dayu-platform
bash docs/performance/redis-eval-20261008/scripts/compile.sh
```

该脚本保留原 jar 并校验完整 SHA256，提取原类/依赖，编译计数启动器，保留 20261007 真实元数据快照。原 jar SHA256 以 `12a7faae` 开头；实际值、快照哈希见 `results/environment.json`。测量时使用的是同一个 jar 的既有提取物，后续在本目录另外保留相同产物以独立复现。

两个独立终端前台启动：

```bash
bash docs/performance/redis-eval-20261008/scripts/start-db.sh
bash docs/performance/redis-eval-20261008/scripts/start-redis.sh
```

准备只在新空库上执行；遇到既有 schema/凭据就拒绝覆盖：

```bash
python3 docs/performance/redis-eval-20261008/scripts/prepare.py
```

生成独立 `dayu_cache_eval` 库和随机 DB/Redis 密码（只保存到 0600 私有文件）。导入产品、模式、真实资产元数据和关联，不导入生产用户或审计；另建 1 个不可登录的合成管理员供应用层测试。DDL 为原部署 V1～V5。HTTP 启动时关闭自动扫描、AI、媒体传输和自动迁移，配置根指向私有合成目录。

## 开关两组后端

另两个终端启动，两个 JVM 均用原部署类，只有 `dayu.cache.enabled` 和监听端口不同：

```bash
bash docs/performance/redis-eval-20261008/scripts/start-app.sh off
bash docs/performance/redis-eval-20261008/scripts/start-app.sh on
```

业务端口 18580/18581，测试控制端口 18582/18583；全部仅 127.0.0.1。控制端口只在实验启动器中存在，提供 JDBC/业务缓存计数、计数清零以及隔离 fixture 操作，**没有加入正式应用源码**。写入前校验数据库目录和 schema。

## 三轮性能测试

```bash
nice -n 15 taskset -c 4 python3 docs/performance/redis-eval-20261008/scripts/benchmark.py
```

- 80 个固定“产品＋预报批次”查询，产品为 BT855/BT108/BT120/BT133，各取真实快照中最新 20 个起报批次；HTTP 参数与前端相同 `number=200`，结果各 12 帧。
- 通过原 Controller → Discovery → AssetIndex/Catalog → JDBC/Redis 调用链；不使用 Mock 数据库或缓存。两 JVM 均先用相同查询预热三遍。
- 客户端 4 个线程，每线程持久 HTTP 连接。使用 `http.client` 直连回环，避免服务器代理环境影响实验；最初 urllib 可达性探测被代理返回 502，未计入任何结果。
- 每个条件每轮 10 个区块，每区块 80 请求，共 800。off 不读写缓存；cold 每区块前仅 FLUSHDB **独立 16389 Redis**；warm 在计时前填充 80 个查询，再重复访问。
- 区块重置、计数读取、预热和响应校验不计入单请求延迟；吞吐量计入请求批执行、响应解析校验等客户端开销，但不含区块之间的 Redis 重置/控制调用。
- 第一/三轮顺序 off→cold→warm，第二轮 cold→warm→off。源码、数据、查询、并发、连接池、资源参数不变。
- Latest 补测每轮每条件 200 请求：4 个产品 × 50 次固定 URL，先未计时访问一次，三轮比较开/关缓存。
- 每次响应保留 SHA256，核对条数/HTTP 状态，跨 84 个查询全部摘要一致；仓库不保存实际文件路径响应列表。

业务命中率来自 `DiscoveryCachePort.Read.value` 是否存在。主接口每请求读取两类缓存，因此每 800 次请求有 1,600 次业务缓存读取。关闭时为不适用。不能用 Redis `keyspace_hits` 判断该命中率，因为 generation 的 GET 即使 payload 未命中也可能命中。

JDBC 在 DataSource/Statement 外侧只计数实际 execute/SELECT 调用，不改 SQL 或业务返回值；不计驱动 Ping、事务协议。两组同样启用计数包装。正式 Redis 从未被 FLUSHDB/KEYS/暂停等操作访问。

## 失效和降级验证（性能测试之后）

```bash
nice -n 15 taskset -c 4 python3 docs/performance/redis-eval-20261008/scripts/validate.py
```

该脚本会将**独立实验库**的资产切换为微型合成 fixture，因此必须在性能实验之后运行。扫描原应用配置的私有目录，调用真实扫描器与事务型 CatalogService，而非直接伪造失效结果。

仅向确认命令行包含 `redis-server`、端口 16389 的私有 PID 发 SIGSTOP，保持旧键不丢失；`finally` 必定 SIGCONT。验证无响应时仍返回新入库数据、恢复后 pending 失效重试、目录/预览产品变更失效、坏 JSON 修复。不是停止线上 Redis，也不更改任何服务管理配置。

故障功能测量使用 3 个合成文件、12 请求、并发 4；与真实资产性能表不是同一数据量，不能直接比较加速倍数。

## 文件和状态

- `benchmark.json`、`latest-benchmark.json`：逐轮 P95、吞吐、SQL、命中、错误率。
- `*-samples.json`：逐请求延迟、状态、正确性和摘要。
- `dataset.json`、`environment.json`、`queries.json`：数据规模、版本、资源和固定查询集。
- `redis-footprint.json`：键数量、TTL 和内存。
- `correctness.json`：扫描/产品变更/坏 JSON/故障恢复的实测断言。
- `summary.json`、`ROUNDS.md`：汇总，不以汇总替代原始样本。

实验结束停止这两个测试 JVM、独立 Redis 与 MariaDB；数据库和私有快照保留。删除数据库或整个实验目录须先经用户确认。此前扫描优化的三个测试库未动。
