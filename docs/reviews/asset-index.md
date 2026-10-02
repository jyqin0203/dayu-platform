# AssetIndex 实现与验收说明

本模块接收正式 WebP/NC 文件的路径、大小和最后修改时间，生成数据库索引、文件与产品关系和扫描历史。不读取 NC 数组，也不修改或删除原始文件。

## 实现链路

```text
管理员 → AssetScanTaskService.submitScan(MANUAL, ADMIN)
       → 数据库创建 RUNNING 任务并返回真实 ID
       → 单工作线程扫描
       → LocalFileInventory 流式枚举正式文件元数据
       → AssetFilenameParser 严格解析 UTC 产品与时间
       → Catalog API 解析产品族和产品 ID
       → JdbcAssetIndexStore 事务写入资产及多产品关联
       → 完整且无错误的根才清理 WebP 行/标记 NC MISSING
       → 持久化计数、状态及前 N 条安全错误
       → 发布 AssetIndexChanged 供缓存失效
```

公开查询通过 `IndexedAssetQueries` 调用资产仓储，仅使用 `CatalogQueryService` 获得产品身份，不跨模块读取 `products` 表。

## 公开输入输出

| 能力 | 输入 | 行为和输出 |
| --- | --- | --- |
| `runInitialFullScan` | 无 | 同步执行 STARTUP 扫描；完整扫描复用幂等增量写入，返回旧 `AssetScanResult` |
| `runIncrementalScan` | 触发来源 | 同步扫描并返回变化计数，和异步任务共用同一个防重叠锁 |
| `submitScan` | MANUAL、可信 ADMIN | 先落库再提交后台任务，返回 RUNNING 快照；重叠返回 CONFLICT |
| `findScanRun` | 正整数任务 ID | 返回持久化状态、真实错误总数和有上限的安全明细；未知 ID 返回空 |
| `searchScanRuns` | 可选起止时间/trigger/status、分页 | 开始时间及 ID 倒序；列表不加载错误详情 |
| `getLatestScanResult` | 无 | 兼容旧 API，投影最近完成任务；应优先使用任务视图获取 ID/状态/真实错误数 |
| `searchScanHistory` | 时间/trigger、分页 | 兼容旧历史摘要，使用真实错误总数 |
| `listPreviewAssets` | 产品、模式、有效时间范围、可选起报/时效、limit | SQL 取最新 N 帧，再以有效时间及 ID 正序返回；只含 AVAILABLE WebP |
| `listForecastCycles` | 产品、资产类型、可选起报时间范围 | 以起报时间倒序返回完整批次的有效时间范围和时效集合；资产类型参与筛选 |
| `searchNetcdfAssets` | 产品、模式、有效时间范围、可选起报/时效、分页 | AVAILABLE NC 稳定倒序分页，一个多产品 NC 不产生重复结果 |
| `findNetcdfCandidates` | 产品、模式、有效时间、可选起报/时效 | 精确匹配 NC；实况不会混入预报 |
| `findDownloadableAsset` | assetId | 只返回 NC 受限视图，保留 MISSING 状态让 Download 做最终检查 |
| `findByStoragePath` | netcdf-data、规范化相对路径 | 同时校验存储空间与路径；其他空间不会误命中 |

`IndexedAssetView` 新增可空 `previewRelativePath`，仅 WebP 使用，供 Discovery 构造真实媒体 URL；NC 始终为 null。旧构造器保留以兼容骨架。

内部预览/NC 查询允许独立为空的时间上下界，用于 Discovery 最新数据和健康查询；公开 HTTP 必填规则由 Controller 守护。NC 候选精确匹配则强制 validTime 非空，避免误变成无界搜索。

## 关键规则

- 正式目录：WebP `realtime/<product>/<file>` 或 `forecast/<cycle>/<product>/<file>`；NC `realtime/<file>` 或 `forecast/<cycle>/<file>`。
- NC 默认只接受已由生产调查和本地文件名确认的 `FY4B_AGRI_REPPIC_PRECIP_...` 命名。BT/CPP 的产品族映射虽已认可，但没有真实 NC 命名样本，本轮不推断启用解析规则。产品关联来自 Catalog 配置，不通过读取数组猜测。
- WebP 兼容云产品的 `_FD_`（旧仓库 `Draw_FD.py`）；RGB/FRGB 文件名没有 `_Dpi500` 后缀，DPI 来自唯一配置根。其他产品不能省略 DPI。已只读核对本地 RGB 样本文件名，无科学文件被复制进仓库。
- 严格日历解析（2 月 30 日无效）；目录起报与文件起报一致；声明的预报小时数必须等于有效时间差。
- 固定一个配置 DPI，忽略未完成后缀、隐藏目录和 tmp/temp；不跟随符号链接；根存在符号链接或不可读时失败。
- 任一文件解析、读取或写库失败，该根不执行缺失清理。根不可读、挂载消失不视为所有 NC 丢失。
- 资产和所有产品关联在一个事务内写入；重复扫描只更新 lastSeenAt。文件大小、mtime 或映射变化才计为 updated。
- 不计算文件内容 SHA-256；字段保持可空，避免扫描重读大型科学数组。
- 部署采用单实例扫描锁；后台最多一个任务，立即拒绝并发扫描。启动将上次进程遗留 RUNNING 标为 FAILED。
- errorCount 保存全部错误总数；数据库错误摘要最多前 `max-errors` 条。页面不能用 errors.size() 替代总数。
- 路径唯一约束目前使用既有数据库排序规则，因此大小写不同但数据库认为相等的路径会报冲突，不能静默覆盖。

## 配置

以下是部署配置示意，不写入仓库 application.yml，不包含实际生产路径：

```yaml
dayu:
  indexing:
    enabled: false
    interval: 15m
    dpi: 500
    max-errors: 100
    roots:
      webp-preview: /configured/webp-dpi500-root
      netcdf-data: /configured/netcdf-root
    expected-leads:
      PRECIP: [60, 120, 180]
```

启用后调度采用 fixed delay。首次扫描由内部 `runInitialFullScan()` 或管理员手动触发；首次部署不自动扫未确认目录。

真实存储空间使用 `netcdf-data`，不将 Mock 的旧 `netcdf-science` 静默重定向；Legacy Controller 需要使用 canonical key。Mock 为旧测试保留两个明确白名单别名。

`expected-leads` 未配置时不会断言预报批次完整（complete=false），但仍返回可用批次与实际时效。

## 测试与限制

已实际执行：

```powershell
$env:MAVEN_OPTS='-Xmx256m'
.\mvnw.cmd '-DargLine=-Xmx384m' '-Dtest=AssetFilenameParserTest,LocalFileInventoryTest,MockAssetTaskTest,AssetIndexIntegrationTest' test
.\mvnw.cmd '-DargLine=-Xmx384m' '-Dtest=AssetIndexIntegrationTest' test
```

第一次命令中 4 个 parser/inventory/mock 测试通过，8 个数据库测试因初始化夹具缺少 PUBLISHED 产品的 published_at 而失败。修正夹具、保持数据库约束不变后，第二次命令的 8 个 MariaDB 测试全部通过。最终本模块 12 个测试均有通过证据，失败 0、错误 0、跳过 0；未声称第一次完整命令成功。

MariaDB 10.6 在临时 tmpfs 数据目录运行，8 个数据库测试耗时 47.55 秒，验证：幂等/修改/缺失/恢复、NC 多产品关联、路径空间筛选、查询所有维度/分页/最新帧、WebP/NC 批次隔离、根异常不误清、错误明细上限、资产与关联事务回滚、任务先提交再异步、防重叠、启动中断恢复，以及 205 条缺失资产跨批清理。

测试只在临时目录生成几个字节的 dummy 文件，未使用生产数据库或实际 NC/WebP 内容。第一次早期 ArchUnit 5 项通过；最终整合后的架构/全量回归由主 Agent 在合并阶段执行。本轮没有做真实生产挂载和 Windows junction 的端到端攻击测试，代码已显式拒绝 symlink/junction 路径。

待主 Agent 集成：Operations 改用新的异步任务 API；Discovery 使用真实 WebP 相对路径；缓存监听 `AssetIndexChanged`；Legacy 使用 netcdf-data。Redis、Nginx、生产挂载和百万文件量 EXPLAIN/压测不属于本模块独立验证。

当前增量扫描仍需枚举配置根，但只更新变化的索引。未来数据量增长后可按分区目录与生产清单缩小枚举范围。

受影响产品保存在本次扫描返回结果和 `AssetIndexChanged` 事件中，现有扫描历史表没有对应列，所以旧 `getLatestScanResult()` 的历史投影不还原 affectedProducts。管理员任务状态、计数和错误通过新任务 API 完整持久化。

BT/CPP NC 需取得正式文件名样本后补充 parser 和相应微型文件测试；不能将“产品族关系可表达”写成“全部 NC 格式已接通”。未确认命名会生成安全错误并阻止该根缺失清理。
