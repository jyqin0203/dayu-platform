# Legacy API 兼容层实现评审

> 实现日期：2026-10-02
>
> 范围：`/api/*.php` 兼容接入层，不包含旧管理员写模型
>
> 基线：`integration/backend-modules`（已包含 Catalog、Identity、AssetIndex、Discovery、Download）

## 1. 模块边界

Legacy 只转换旧参数、路径别名和响应字段。它调用以下公开 API：

- `CatalogQueryService`：已发布且启用模式的产品；
- `DiscoveryQueryService`：近期 WebP、预报批次和分页 NetCDF 查询；
- `DownloadAssetLookup`：由 `assetId` 重取真实 `netcdf-data` 相对路径，及旧表单路径到资产的内部查找；
- `DownloadAuthorizationService`：登录用户、用途、资产和审计授权；
- `DownloadContentService`：按下载事件和当前用户重新验证并准备 local 流或 Nginx 内部转发。

兼容层不访问数据库、磁盘、业务模块内部实现，也不根据文件名猜 NetCDF 路径。

## 2. 路径与时间安全

`LegacyPathParser` 只接受配置的 WebP 别名根和 NetCDF 别名根，并只解析以下目录形状：

```text
WebP/<configured>/realtime/<product>
WebP/<configured>/forecast/<yyyyMMddHHmm>/<product>
netcdf/realtime
netcdf/forecast[/<yyyyMMddHHmm>]
```

绝对路径、盘符、`.`、`..`、空段、控制字符、查询串、片段、反斜杠逃逸以及仍含 `%` 的二次编码输入在调用模块前拒绝。12 位时间使用严格 UTC 公历解析，闰日、月份、日期、小时和分钟均真实校验。

## 3. 查询兼容行为

- `files.php`：实况查询 retention 窗口内最新 `number` 帧；预报先读取真实批次的首末有效时间，再在 retention 范围内查询，因此保留起报后的未来有效帧；`number` 为 1～200，结果保持有效时间正序并返回旧 WebP 别名；
- `fcst_latest.php`：仅接受精确预报根；`product` 可省略，省略时遍历已发布且启用 FORECAST 的产品，从真实 AVAILABLE WebP 批次选择全局最新值；
- `search.php`：保留 `files/sizes/times` 三个平行数组，统一按有效时间倒序，大小按 1024 进位输出两位小数；
- WebP 搜索按 `dayu.preview.retention` 裁掉过旧起点但不裁掉用户的未来结束时间；调用 Discovery 前先把长窗口分段，满 200 帧时继续递归拆分并按 `assetId` 去重；一分钟区间仍满载时明确返回 422，不静默截断；
- NetCDF 搜索逐页读取到 `total`，跨 PLP/PRECIP 等共享产品按 `assetId` 去重；输出路径通过 `findDownloadableAsset(assetId).relativePath` 组成，不使用摘要文件名推测；
- `dayu.legacy.max-search-results`（默认 2000）限制兼容搜索结果，发现第 N+1 个唯一资产即返回旧式 422。

非法路径、非法时间和无数据保留各端点规定的旧空响应。模块运行异常不再被吞成空成功，而是记录并返回旧形状 `{ "ok": false, "message": ... }` 的真实 4xx/5xx。

## 4. 旧直接下载

`POST /api/download.php` 的调用链为：

```text
严格解析 netcdf 别名相对路径
→ DownloadAssetLookup.findByStoragePath("netcdf-data", relativePath)
→ DownloadAuthorizationService.authorizeDownload(...)
→ DownloadContentService.prepareContent(eventId, actor)
→ DownloadHttpResponse.from(content)
```

因此旧表单与 v1 使用相同的用途校验、审计、事件归属/期限/文件重验、local 流和 Nginx 安全响应。Session 登录与 CSRF 仍由同一 Spring Security 链先行校验。

## 5. 验证证据

无数据库、无真实科学文件环境中实际执行：

```text
.\mvnw.cmd -DskipTests compile
BUILD SUCCESS；180 个主源码文件编译通过。

.\mvnw.cmd "-Dtest=LegacyPathParserTest,LegacyDataControllerContractTest,LegacyDownloadSecurityTest" test
12 tests；0 failures；0 errors；0 skipped。

.\mvnw.cmd "-Dtest=ArchitectureTest,HttpRouteCoverageTest,RemainingHttpContractTest,LegacyPathParserTest,LegacyDataControllerContractTest,LegacyDownloadSecurityTest" test
22 tests；0 failures；0 errors；0 skipped。

$env:MAVEN_OPTS='-Xmx256m'
.\mvnw.cmd "-Dtest=LegacyDataControllerContractTest" "-DargLine=-Xmx384m" test
审查修复后 10 tests；0 failures；0 errors；0 skipped。
```

验证覆盖：坏路径不调用模块、严格 UTC 日历、旧空响应、最近 N 帧正序、预报未来有效帧、可选产品最新批次、可配置 retention 分段、WebP 真实大小、NC 多页与共享资产去重、真实索引相对路径、配置上限 422、系统异常 500、POST local 流，以及下载登录/CSRF 安全链。架构规则 5 项全部通过。

## 6. 未覆盖范围

- 本轮未连接 MariaDB、真实数据目录或 Nginx；这些能力由各业务模块的独立集成测试负责；
- 未执行生产部署或真实大文件传输；
- 旧管理员接口继续固定返回 410，新管理员页面不属于 Legacy 适配器实现范围。
