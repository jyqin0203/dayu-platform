# Operations 实现与验收说明

本分支将 Operations 从 skeleton 内联结果改为真实的跨模块编排，并补齐管理员 HTTP 契约。Operations 只依赖 Catalog、AssetIndex、Discovery、Identity、Download 的公开 API；未访问其他模块的 application、domain、infrastructure 或数据库表。

## 调用关系

```text
管理员 Session + Spring Security ADMIN
→ OperationsController 再次取得可信 ActorContext 并校验 ADMIN
→ OperationsApplicationService
   ├─ CatalogQueryService：已发布产品、模式策略与展示用 staleAfter
   ├─ DiscoveryQueryService：当前预览/下载可用性、最新时间与健康结论
   ├─ AssetScanTaskService：真实异步任务 ID、状态、分页和详情
   ├─ DownloadAuditQueryService：审计与 9 字段统计
   └─ UserAdminService：用户分页、状态和角色变更
```

`MockOperations` 只在 `skeleton` Profile 复用相同编排规则；其依赖仍是各模块的 skeleton Mock，因此不能作为生产数据库或文件系统已集成的证据。

## 方法输入、行为和输出

| 方法 | 输入 | 主要行为 | 输出 / 副作用 |
| --- | --- | --- | --- |
| `getDashboard` | 可选 UTC `from/to`、产品、数据模式、可信管理员 | 缺省最近 30 天，拒绝反向及超过 365 天范围；产品筛选影响汇总范围，`dataMode` 只过滤当前可用性/健康；采用 Discovery 的模式感知健康结论；下载统计按 `from/to/productCode` 查询 | 五类产品计数、真实最近扫描任务或 null、OpenAPI 下载统计；只读 |
| `getProductHealth` | 可选产品集合和数据模式、可信管理员 | 批量读取管理产品详情；发布/模式开关决定 DISABLED，其余健康结论来自 Discovery，Catalog `staleAfter` 只提供展示阈值 | 稳定按产品编码和模式排序；`latestValidTime` 无数据时为 null；未聚合的 WebP/NC 数量保持 null |
| `triggerIncrementalScan` | 可信管理员 | 保留旧内部同步 Java 契约；HTTP 不调用它 | `AssetScanResult`；同步副作用仅供旧内部调用 |
| `searchDownloadAudits` | 时间、产品、用户、单位、状态和分页 | 校验时间顺序并原样委托 Download | 审计分页；`DENIED` 的 `authorizedAt` 保持 null |
| `getDownloadStatistics` | 时间和产品 | 校验时间顺序，原样委托 Download | HTTP 投影为 `authorizedRequests/uniqueUsers/uniqueOrganizations/uniqueAssets` |
| `changeUserStatus` | 目标用户、状态、可信管理员 | 只做管理员与必填校验，账号状态机由 Identity 执行 | 最新用户摘要；Identity 负责冲突和持久化 |
| `changeUserRole` | 目标用户、角色、可信管理员 | 只做管理员与必填校验，角色规则由 Identity 执行 | 最新用户摘要；Identity 负责冲突和持久化 |
| `POST /admin/index-scans` | ADMIN Session + CSRF | 直接调用公开 `AssetScanTaskService.submitScan(MANUAL, actor)`，不等待扫描完成 | HTTP 202，返回数据库任务 ID 和初始 `RUNNING` 状态；重叠任务为 409 |
| `GET /admin/index-scans` | 时间、trigger、status、分页 | 将全部筛选传给扫描任务 API | 真实任务分页，列表不返回错误明细 |
| `GET /admin/index-scans/{id}` | 正整数任务 ID | 按真实 ID 查询，不以“最近一次”代替 | 任务详情和有界安全错误摘要；未知 ID 为 404 |
| 用户管理 HTTP | 邮箱、单位、角色、状态、分页或写命令 | 参数校验、可信管理员转换、调用 Identity API | 分页或最新公开用户摘要，不返回密码字段 |

所有管理路由同时由 Spring Security 过滤链和可信 actor 校验保护；写操作还要求 CSRF。Controller 不引用任何其他模块的基础设施类。

## HTTP 响应语义

- 扫描响应使用 `ScanRunView.scanRunId/status/errorCount`，不从时间戳伪造 ID，也不把 202 表述成同步成功。
- `latestScan` 为真实任务视图；没有任务时为 JSON null。
- 健康响应无数据时输出 JSON null 的 `latestValidTime`，不使用空字符串；不公开无法证明的资产数量。
- 下载审计允许 `DENIED` 的 `authorizedAt` 为 JSON null，并且不输出 IP、User-Agent、存储键或相对路径。
- 下载统计严格输出 OpenAPI 的 4 字段：`authorizedRequests`、`uniqueUsers`、`uniqueOrganizations`、`uniqueAssets`；Download 内部更细的申请、拒绝和分组聚合不在本端点悄然扩展。
- 用户、审计和扫描列表均使用统一的一基页码及最大 100 条分页校验。

## 文件变更

- 新增 `OperationsApplicationService`：非 skeleton 的管理员编排实现及健康/时间规则。
- 新增 `OperationsResponses`：显式、可空安全的 HTTP 响应类型和跨模块 DTO 转换。
- 修改 `OperationsController`：补全筛选、分页、管理员/CSRF 边界、真实异步扫描及安全响应。
- 修改 `DashboardSummary`：最近扫描改用包含真实 ID/状态的 `ScanRunView`。
- 修改 `ProductHealth`：无法证明的数量使用可空 `Long`，避免把布尔可用性伪装成数量。
- 修改 `MockOperations`：skeleton Profile 复用同一 Operations 编排。
- 新增应用服务单元测试和带真实安全过滤链的 Controller 契约测试。

没有修改依赖矩阵、第三方依赖、DDL、`pom.xml` 或部署配置。

## 验证

使用受限堆内存，在 `backend` 目录执行：

```powershell
$env:MAVEN_OPTS='-Xmx256m'
.\mvnw.cmd '-DargLine=-Xmx384m' '-Dtest=OperationsApplicationServiceTest,OperationsControllerContractTest' test
.\mvnw.cmd '-DargLine=-Xmx384m' '-Dtest=ArchitectureTest,AdminJourneyTest,RemainingHttpContractTest,HttpRouteCoverageTest' '-Dlogging.level.org.springframework=INFO' test
```

最终结果：第一组 13 项、第二组 10 项，共 23 项，0 失败、0 错误、0 跳过，均 `BUILD SUCCESS`。覆盖：

- 默认 30 天/最大 365 天、反向时间、产品和模式实参；
- Discovery 模式感知的健康状态、Catalog 展示阈值、未来有效时间仍可为 STALE、停用模式、null 时间及不编造数量；
- 真实扫描 ID、RUNNING/PARTIAL、错误总数、详情、分页筛选、404 和并发 409；
- 全部管理路由的未认证/非管理员拒绝以及写操作 CSRF；
- `DENIED authorizedAt=null`、审计全部筛选与安全字段排除；
- 下载统计 OpenAPI 4 字段和 `from/to/productCode` 转发；
- 用户分页、命令必填、路径 ID 和枚举错误；
- Operations 依赖矩阵、路由完整性及 skeleton 管理故事。

中间复验发现新增测试时钟构造器未标记 Spring 注入构造器导致 HTTP 测试上下文失败，随后明确注入生产构造器。主 Agent 审查还发现用 validTime 重算健康会误判预报数据，已改为保留 Discovery 的健康结论、Catalog 阈值仅展示，并新增未来 validTime 仍为 STALE 的回归；完整两组测试重新执行并通过，没有删除测试或降低断言。

## 限制

- Operations 自身不连接数据库；扫描持久化、下载聚合、用户持久化和产品查询由已注入模块负责。本分支测试使用这些公开 API 的可控替身，不冒充跨模块数据库集成。
- 旧 `OperationsService.triggerIncrementalScan` 仍为同步 Java 兼容契约；管理员 HTTP 已只使用新的真实异步任务 API。后续公共 API 清理需由主 Agent 统一协调。
- 本分支没有运行全仓数据库/容器测试，也没有连接生产文件、数据库、Redis 或 Nginx；合并后的全量验证由主 Agent 执行。
- 当前 OpenAPI 文件由集成分支统一维护，本分支没有重复修改公共 YAML；HTTP 统计保持其 4 字段契约，Download 内部新增聚合字段不会自动泄露。
