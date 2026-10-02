# Redis 热点元数据缓存评审

## 输入

- 已发布产品目录查询。
- 近期 WebP 时间轴查询：产品、模式、UTC 起止、起报时间、时效和 limit。
- WebP 预报批次查询：产品、UTC 起止和 `assetType=WEBP`。
- `CatalogChanged` 与 `AssetIndexChanged` 进程内事件。
- `dayu.cache.*` 开关及 TTL 配置；默认关闭。

## 行为

- Catalog 与 Discovery 通过 `@Primary` 装饰器执行 Cache Aside，真实委托分别仍为 `CatalogService` 和 `IndexedDiscovery`。
- 缓存值使用 Jackson JSON，覆盖 `Instant`、`Duration`、URI、ID record 和集合，不使用 Java 本地序列化。
- Discovery key 覆盖产品、mode、from、to、cycle、lead、limit 和 assetType，避免不同查询相撞。
- 每个目录或产品使用 Redis generation key。失效只切换 generation，不执行 `SCAN`；旧 payload 由 TTL 回收。generation 缺失时使用随机正数种子，避免 generation key 被淘汰后重新引用仍存活的旧 payload。
- 回填前再次检查 generation；并发失效后，旧查询最多写入不可达的旧代际 key。
- Catalog 写操作在事务内发布 `CatalogChanged`，缓存监听只在 `AFTER_COMMIT` 执行；回滚不失效。
- Catalog 变更同时失效公开目录和受影响产品的 WebP 查询；`AssetIndexChanged` 失效受影响产品的时间轴和 WebP 预报批次。
- Redis 读取、解析或写入失败均回源业务委托。缓存损坏时删除损坏项并回源。
- Redis 离线期间发生写入时，本进程记录待失效产品；Redis 恢复后的首次读取先切换 generation，再允许读缓存，避免恢复后直接返回旧值。

## 输出

- Catalog：已发布 `ProductDetail`/`ProductSummary`，不缓存管理员目录。
- Discovery：`PreviewFrame` 与仅 WEBP 的 `ForecastCycleSummary`。
- 不缓存历史 NC search、下载候选、产品健康聚合、密码、用途、审计、管理员统计、文件内容、绝对文件路径或 NC 相对路径。

## 测试证据

执行命令（Maven 进程 `-Xmx256m`，测试 fork 使用 `-DargLine=-Xmx384m`）：

```powershell
.\mvnw.cmd '-Dtest=CatalogChangedPublicationTest,CatalogCacheTransactionEventTest,CacheAsideRedisIntegrationTest,ArchitectureTest' '-DargLine=-Xmx384m' test
.\mvnw.cmd '-Dtest=CacheAsideRedisIntegrationTest' '-DargLine=-Xmx384m' test
```

已通过的行为证据：

- Testcontainers `redis:7.4-alpine`（128 MiB）真实 Redis 的 miss/hit、JSON 往返、查询维度隔离、Catalog/AssetIndex 失效、空结果和损坏值回源。
- Catalog 写成功发布事件、写失败不发布；事务提交后失效、回滚不失效。
- generation key 被单独淘汰时不会重新命中旧 payload。
- Redis 读取失败和回填写入失败不改变业务结果；缓存关闭时 Redis client 零交互。
- 失效发生在 delegate 查询与缓存回填之间时，旧 generation 回填被拒绝；失效失败后，恢复读取会先切换 generation，不能命中旧 payload。
- WEBP 批次被缓存，NETCDF 批次与历史 NC search 始终绕过缓存。
- ArchUnit 5 项与既有 IndexedDiscovery 测试通过。

变基后组合执行 16 项全部通过；补强并发/恢复边界后，Redis 集成测试单独执行 10 项全部通过。所有执行均为 0 失败、0 错误、0 跳过，未使用 Docker 跳过开关。

## 限制

- Redis 不是事实来源；启用缓存只降低热点元数据查询压力。
- 离线失效的本地待处理标记适用于当前单实例部署。未来多实例部署需要可靠的跨实例失效通道；本实现不宣称跨实例强一致。
- TTL 是最后的旧 key 回收和多实例异常兜底，不替代事务后失效。
- 本变更不修改 Controller、安全配置、数据库迁移、SQL 或缓存范围外的业务服务。
