# 缓存键与调用链审计

`GEN` 是随机正整数版本号，不是递增序列；空查询字段以 `NONE` 表示。

产品目录：

```text
dayu:catalog:generation                                        无 TTL
dayu:catalog:v{GEN}:published                                   300 秒
```

产品目录缓存保存 `ProductDetail` 列表，`listPublishedProducts()` 从缓存详情投影；`findProduct()`、管理员列表和族映射绕过目录缓存。源码：`catalog/infrastructure/RedisCatalogCache.java`、`CachedCatalogQueryService.java`。

Discovery：

```text
dayu:discovery:{CODE}:generation                                无 TTL

dayu:discovery:{CODE}:v{GEN}:timeline:assetType=WEBP:mode={MODE}:from={FROM}:to={TO}:cycle={CYCLE}:lead={LEAD}:limit={LIMIT}
                                                                120 秒

dayu:discovery:{CODE}:v{GEN}:latest-cycles:assetType=WEBP:mode=FORECAST:from={FROM}:to={TO}:cycle=ALL:lead=ALL:limit=ALL
                                                                120 秒
```

源码：`discovery/infrastructure/RedisDiscoveryCache.java`、`CachedDiscoveryQueryService.java`。命中读取版本键和业务键；冷请求还要查数据库、再检查版本并写 JSON。这里只缓存 WebP 批次，NETCDF 批次主动绕过。热点帧列表在 V1 控制器中 limit 固定 200，在旧接口中由 number（1～200）指定。

调用关系：

```text
frontend/public/flat-20260925/timeline-data.js: loadTimeline
→ /api/fcst_latest.php                  获取最新批次
→ /api/files.php?...forecast/{cycle}/{product}&number=200
→ LegacyDataController.files
  → 产品/模式检查                      一次数据库查询，未缓存
  → CachedDiscovery.listForecastCycles 固定 cycle 起止条件
  → CachedDiscovery.listPreviewFrames  固定批次有效时间范围
```

最新批次路径在 `LegacyDataController.latest` 中使用 `Instant.EPOCH` 到 `clock.instant()`；后者参与缓存键，因此重复相同 HTTP URL 也没有复用同一业务键。本次 600 次开启缓存的最新批次请求，全部未命中；返回内容与关闭缓存一致。实况 files 分支也用 `now`/`now-window`，是同类键变化，未在本轮额外做性能结论。V1 的固定起止参数可以复用键，但不能把 V1 固定参数命中率套到旧接口动态调用上。

失效链路：

```text
PersistentAssetScanner
→ 文件变更逐条提交 / 批次原子提交
→ 完成扫描记录
→ AssetIndexChanged(affectedProducts)
→ CachedDiscoveryQueryService.assetIndexChanged
→ 更换该产品 generation

CatalogService（事务代理）
→ 产品命令、审计、CatalogChanged
→ 提交成功后 AFTER_COMMIT
→ 更换 catalog generation + 相应产品 discovery generation
```

没有文件变化时不发布变更事件，因此无变化扫描不清热点数据。失败批次/不完整根的缺失清理规则仍由扫描器管理，本轮未改动。

旧代数据仍在 Redis，直到自身 TTL 过期；随机代号避免版本键被淘汰后又恢复旧命名空间。回填检查发生在写入前：若失效恰在检查与 SET 之间，最多写入一个不可达的旧代键。已在执行中的读取仍可能跨过失效时刻，不声称线性一致。

读取/JSON 解析失败、写入失败均不替代业务结果；读取失败回源，写入失败返回已取得的数据库结果。失效失败记录 pending 并重试；此标记只存在当前适配器 JVM。直接 SQL 修改不触发业务事件，不满足“立即失效”的正常路径。

本轮使用原部署 jar，不使用前一轮尚未部署的批量扫描候选。性能探针仅增加 JDBC/cache 返回值计数，不更换缓存、SQL、控制器或业务编排。验证控制端口只存在于仓库外实验 JVM，未加入应用源码或部署产物。
