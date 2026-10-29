# 历史 NC 检索：真实数据基线与待确认方案

> 本文保留第一阶段基线和当时待确认方案。用户确认后的实验见 [A/B/A优化验证结果](optimization-results.md)。

日期：2026-10-07。阶段：**只完成盘点、基线及方案；没有实施优化**。没有删除/禁用既有索引、修改业务 SQL、扩大测试数据量或切换生产流量。

本次未发现值得立即上线的严重查询瓶颈。建议至多先做一次隔离联合索引 A/B 实验；收益不显著就保留现有实现。不能把本报告写成“优化提升 X%”。

## 1. 数据口径

元数据一致性快照取自 `dayu_test:13306`，时间、SHA-256 和精确统计见 [inventory.json](results/inventory.json)。只查询新测试库，未连接旧库进行负载测试。

| 数据 | 实际文件数/资产行数 | 文件字节数 |
|---|---:|---:|
| NC 实况 | 123 | 2,538,796,341 |
| NC 预报 | 369 | 7,597,394,353 |
| NC 合计 | **492** | **10,136,190,694（约9.44 GiB）** |
| WebP 实况 | 2,068 | 1,012,367,578 |
| WebP 预报 | 6,710 | 1,626,409,072 |
| 全部 | **9,270** | **12,774,967,344（约11.90 GiB）** |

- `data_assets` 9,270 行，`data_asset_products` 9,762 行；NC 可关联 PLP/PRECIP 两个产品，关联表行数不是物理文件数。
- 按入库路径逐项 stat，9,270 个独立物理文件均存在，大小与索引一致；以设备号/inode去重。未递归扫描新范围、未读取科学文件内容。
- 均来自此前确认的真实 WebP/NC 根目录，本快照合成资产 **0**。独立自动化测试库的数据、测试账号都不纳入文件统计；数据库没有“真实/合成”标志，分类依据是来源目录、扫描记录和文件核对，不能用于任意未知库。
- 这些数值是**已索引文件**的容量，不是整个数据盘容量，也不是 SQL 需要读取的字节量。检索只读元数据。
- NC 有效时间 2026-09-02 06:00 至 2026-09-08 06:00 UTC；最长非空页为实况第7页、预报第19页（pageSize=20），不能称为百万级深分页。

## 2. 原实现链路和 SQL

`DiscoveryController.scientificAssets` → `CachedDiscoveryQueryService`（NC直接绕过Redis）→ `IndexedDiscovery.searchScientificAssets`（产品/模式可用性验证）→ `IndexedAssetQueries.searchNetcdfAssets` → `JdbcAssetIndexStore.query/count`。

正常非空请求有5条 SELECT：指定产品与模式校验、全产品映射、分页资产列表、批量资产产品关联、总数 COUNT。空页省略关联查询。没有逐行 N+1 关联查询；全目录映射确有固定开销，但本次不同时修改它。

列表：`SELECT a.* FROM data_assets a WHERE asset_type='NETCDF' AND data_mode=? AND status='AVAILABLE' AND EXISTS(产品关联条件) AND valid_time>=? AND valid_time<=? [AND cycle_time=?] [AND lead_minutes=?] ORDER BY valid_time DESC,id DESC LIMIT ? OFFSET ?`。
COUNT：相同 WHERE，`SELECT COUNT(*)`，不带排序和分页。时间上下界都包含。

原有索引未改动：

- `idx_assets_timeline(asset_type,data_mode,status,valid_time,id)`。
- `idx_assets_forecast_cycle(asset_type,data_mode,status,cycle_time,valid_time,id)`。
- 关联表主键 `(asset_id,product_id)` 和反向索引 `(product_id,asset_id)`。
- 另有扫描所需索引/路径唯一索引，完整清单见 [schema.tsv](results/schema.tsv)。

最初刚导入时的手写 SQL 计划在 [plans.json](results/plans.json)。负载后发现真实末页查询有排序，因此在测量结束后短暂开启**仅隔离库**general log，捕获HTTP生成的实际SELECT，再分别执行EXPLAIN及3次ANALYZE，保存于 [actual-sql-plans.json](results/actual-sql-plans.json)。随后关闭general log；计时阶段未开启。

实际SQL文字与模板一致，计划随导入后统计状态变化；不能仅凭初始计划断言线上执行形态。后期计划与接口digest统计吻合：

| 场景 | 列表访问及开销 | COUNT访问及开销 |
|---|---|---|
| 短实况 | 时间索引；7个资产+7次关联检查 | 时间覆盖索引；7+7 |
| 长预报首页 | 时间索引；20+20 | 预报索引前缀覆盖；369+369 |
| 长预报末页 | 预报索引前缀读369资产，排序369行，再查关联 | 369+369 |
| 指定预报批次 | 批次索引；3+3 | 批次覆盖索引；3+3 |
| 指定60分钟时效 | 时间索引访问61个资产，过滤得20个，查20次关联 | 从产品关联索引读492行，再按主键查492次，过滤得123条 |

本次实际接口的单并发 SQL 平均耗时（三轮范围）：

- 长预报首页：列表0.155～0.166ms，COUNT 0.387～0.392ms。
- 长预报末页：列表0.609～0.617ms，COUNT 0.363～0.404ms。
- 时效筛选：列表0.194～0.200ms，COUNT 0.369～0.399ms。

以上是数据库执行计数的平均时间，不是HTTP P95。逐轮拆分见 [list-count-costs.json](results/list-count-costs.json)。ANALYZE的 `r_rows × r_loops` 是各执行节点的行访问，不与整个语句的 `SUM_ROWS_EXAMINED` 混为同一指标；排序也可能计入后者。

MariaDB 10.6的原始JSON计划存在重复 `table` 键；解析脚本保留原文并将重复键加后缀，避免默认JSON解析悄悄丢掉前一个表节点。语义依据：[MariaDB ANALYZE FORMAT=JSON](https://mariadb.com/docs/server/reference/sql-statements/administrative-sql-statements/analyze-and-explain-statements/analyze-format-json)。

## 3. 固定环境与正式三轮

- 独立路径 `/home/ubuntu/dayu-test/performance/nc-search-20261007/`，独立DB `dayu_perf:13307`、后端 `127.0.0.1:18081`，没有复制账号、密码或科学文件；只复制目录/资产元数据和数据库结构。
- 原运行JAR直接复制，SHA、源码摘要、服务器CPU信息见 [environment.json](results/environment.json)。未修改实现来制造慢基线。
- MariaDB 10.6.23，buffer pool 128MiB，Java Xmx512MiB，Hikari最大5，Tomcat最大24。
- DB/后端各限制CPU 100%、分别内存512/768MiB、同绑CPU 2/3、Nice10；不修改线上或日常测试服务的限制。
- 无定时扫描、AI、Redis；NC在原系统也不走Redis。Performance Schema启用且整个前后对照保持一致。
- 9种查询固定在 [queries.json](queries.json)：6小时/全数据时间范围、实况/预报、指定批次、指定时效、首页/末页、空结果。另保留时间范围和产品、分页大小精确参数。
- 并发1和4，各组每轮1000次，重复3轮，共 **54,000次计时请求**。固定随机种子交错场景顺序；预热1800次、每组另预热20次，不计入结果。
- 本机HTTP keep-alive闭环负载。响应耗时从发请求到读完响应体；吞吐量包含客户端校验和工作线程启动。不是公网、浏览器或生产Nginx指标，不是最大承载能力。
- 每个响应与原始快照独立生成的预期值逐字段比较，包括顺序、页号、页大小、total、文件摘要；不是只检查HTTP200。**54,000次均一致，错误率0%。**
- P95按nearest-rank逐轮计算，不挑最好一轮，不平均分位数作为总体P95。[完整三轮表](baseline-table.md)、[CSV](results/baseline-summary.csv)。

代表性基线（所有场景见完整表）：

| 场景 | 并发 | P95三轮ms | 吞吐三轮请求/s |
|---|---:|---|---|
| 6小时实况 | 1 | 3.36 / 2.11 / 1.37 | 758 / 840 / 941 |
| 长预报首页 | 1 | 3.40 / 1.61 / 2.50 | 618 / 701 / 649 |
| 长预报末页 | 1 | 3.11 / 2.40 / 2.43 | 513 / 524 / 539 |
| 指定批次 | 1 | 1.16 / 1.72 / 1.16 | 986 / 890 / 974 |
| 指定60分钟时效 | 4 | 5.65 / 5.95 / 5.42 | 1005 / 1017 / 999 |
| 长预报末页 | 4 | 22.60 / 8.35 / 28.43 | 688 / 617 / 688 |

重要限制：4并发部分组出现cgroup CPU节流（逐轮cpuDelta已保存）；尤其预报末页的P95大幅波动，不能据此归因于单条SQL或宣称可稳定消除。单并发正式各组未观察到CPU节流。小规模数据/亚毫秒SQL的收益很容易被调度噪声覆盖。

数据库扫描行数按隔离库SELECT digest前后增量计算，包含产品查询、映射、列表、关联和COUNT；详见原始digest。个别组统计边界有轻微波动（短预报4并发第一轮199.896行/请求，后两轮200），原样保留，不人为四舍五入成完全相同。

初次试跑为每组200次、预热180次，共10,800次；首轮JIT波动明显。`pilot-*`原始结果单独保留，不和正式轮次混算，也不把二者差异当优化收益。

## 4. 唯一候选方案：待用户确认，不执行

仅在隔离库尝试新增一个覆盖时效筛选的联合索引：

```sql
CREATE INDEX idx_assets_type_mode_status_lead_time
ON data_assets(asset_type, data_mode, status, lead_minutes, valid_time, id);
```

理由：原时间索引没有lead_minutes；时效筛选COUNT目前从492条产品关联出发查492次资产，性能计数共984行检查/次，列表81行检查/次。新索引可能把扫描限定到匹配的时效并使COUNT覆盖，仍保留原索引供不带lead或指定cycle的查询选择。**没有提前执行DDL或测试优化版；行数下降和耗时收益均待实测。**

不承诺解决末页排序，不改返回字段/total/排序，不加NC Redis，不改分页协议，不省略COUNT，不用FORCE INDEX制造对照。

代价：新增索引会覆盖整个data_assets表，包括WebP；增加存储、插入/删除和状态更新维护成本。因此当前492个NC不足以证明值得上线。建议只有当隔离复测收益稳定且入库无明显回归时才保留，否则撤回索引并如实结束实验。

确认后的流程：固定同一快照/JAR/资源限额/查询集/预热，先重新采同协议基线，再仅加该索引复测3轮；必要时在隔离库移除候选索引再做A/B/A验证。比较每轮而不是最好值。对所有响应继续逐字段校验，另比对SQL计划、列表/COUNT行数、时间和新索引占用。

入库影响：在另一个可恢复的隔离副本中用同一批元数据、相同事务/关联写入顺序比较新增、重复扫描更新、失效索引删除的耗时；保留输入清单和每轮原始值。若用合成文件驱动扫描，明确标成合成扫描测试，不能冒称真实科学文件吞吐。不得为了跑写入基线修改真实源目录或清空现有测试库。此阶段尚未执行。

## 5. 当前能写的简历内容与STAR

当前可信描述（性能优化完成后再更新，不提前编造提升）：

> 为气象平台历史NC检索建立可复现性能基线，盘点9,270个真实文件及492个NC资产，隔离MariaDB与Java实例，覆盖9类查询、1/4并发共54,000次请求；结合执行计划和SQL扫描行数定位时效筛选的索引改进候选，并逐请求验证分页、排序与总数一致性。

STAR：

- S：平台接入真实WebP/NC，文件总容量不能代表检索规模，需要证明瓶颈和收益而非凭感觉加索引。
- T：保护旧线上服务，建立可重复基线，分开列表/COUNT成本，确认后才优化。
- A：复制元数据到资源受限隔离实例；固定查询集、预热、三轮计时；逐请求对照快照；核对真实SQL和数据库计数，发现初始EXPLAIN与负载后计划不同并补充验证。
- R：54,000次响应无错误且内容一致；确认当前只有492个NC，现有索引总体有效，发现lead筛选COUNT的984行检查候选，但没有夸大为已实现提速；后续按A/B与入库代价决定是否采用。

还不能写“P95下降X%”“支持百万级NC”“生产千QPS”或“检索了9GB文件内容”。

## 6. 复现与交付位置

详见 [复现命令](reproduce.md)。统计/匿名原始结果在本目录，元数据快照只保存在上述仓库外0700私有目录，未提交真实数据或.env。私有快照不含用户表内容和生产凭据，仍不应当作公共测试数据上传。

本阶段没有修改业务源代码、原库索引或旧系统；临时性能服务在完成后停止，数据和配置保留供确认后的复测。用户此前已有源码改动保持原样，未commit/push。
