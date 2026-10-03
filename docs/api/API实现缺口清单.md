# 大禹系统 API 实现状态与剩余验收

日期：2026-10-02。本文替代仅有 HTTP 空骨架时的缺口清单，不再把已实现的真实服务写为“待实现”。

## 当前实现

`/api/v1` 的 24 条路径、28 个操作，以及 6 个保留的 Legacy 路由和 2 个 410 退役路由，均有协议适配器。**有路由不等于业务已经验证**：对应的真实服务及证据如下。

| 模块 | 真实实现 | 验证入口 |
| --- | --- | --- |
| 公共接入 | 参数、分页、UTC、错误码、traceId、Session/CSRF、ADMIN 授权 | HTTP 契约、安全及 ArchUnit 测试 |
| Catalog | MariaDB Repository、事务审计、模式配置、发布/停用/重新发布、色标边界 | `docs/reviews/catalog.md` |
| AssetIndex | 只读元数据扫描、文件名解析、幂等索引、缺失处理、真实异步任务与错误记录 | `docs/reviews/asset-index.md` |
| Discovery | 近期 WebP、历史 NC 分页、预报批次、预览下载候选、按起报时间判断预报新鲜度 | `IndexedDiscoveryTest`、`PlatformWorkflowIntegrationTest` |
| Identity | MariaDB 用户、密码哈希、每 Session 隔离、注册提交后认证、登录限流、用户状态/角色保护 | `docs/reviews/identity.md` |
| Download | 持久授权/拒绝审计、用户与 TTL 绑定、受控路径、本地流式内容、Nginx internal 转发、聚合统计 | `docs/reviews/download.md` |
| Operations | 公开模块 API 编排、真实扫描 ID/状态/历史、健康与下载统计、用户管理 | `docs/reviews/operations.md` |
| Copilot | 可替换 ChatClient/AiClient、Qwen HTTP、结构化输出校验、只读查询、固定动作、超时/降级 | `docs/reviews/copilot.md` |

Redis 热点元数据缓存、Legacy 最终兼容性及 Copilot 每分钟/在途调用限制已通过收尾 PR 补齐，分别记录在 `reviews/cache.md`、`reviews/legacy.md`、`reviews/copilot-limits.md`。最终组合测试结果见 [整体验收报告](../后端并行开发与整体验收报告.md)。

## 已明确的行为边界

- 发布目录与模式决定公开检索；数据库统一 UTC，显示时区由前端提供/转换。
- WebP 默认检索近期 3 天，可配置；预报有效时间可以位于未来。NC 历史检索不受图片保留窗口限制。
- 一个 NC 可以关联多个产品。RePPIC NC 同时关联 PLP、PRECIP；预览找不到 NC 时正常显示但不伪造下载地址。
- Download 保持既定依赖矩阵：不额外依赖 Catalog。已知旧 assetId 在资产仍可下载时可以申请，不将停用产品等同于撤销文件权限。
- 下载统计指 `AUTHORIZED` 授权申请，不声称客户端完整下载。完整传输统计仍未实现。
- HTTP 扫描提交返回 202 和真实任务 ID；不把后台尚未完成的任务描述成“扫描成功”。
- Copilot 只帮助理解产品和筛选数据，不能替用户下载、越权管理或直接执行模型输出。

## 还不能声称完成的事项

1. **真实数据覆盖**：当前 NC 解析器覆盖已确认的 RePPIC 命名；辐射/云 NC 的具体命名仍需样本，不能仅凭家族映射自动推断。
2. **真实模型联调**：已用实际 Java 客户端成功调用一次 `qwen3.8-flash`，正式启动脚本已支持读取配置；完整 Copilot 页面、查询质量和费用控制仍需联调，不能用一次连通性代替整体验收。
3. **浏览器与前端**：后端测试不等于 Cesium/下载页面完成联调。受限 WebP/色标服务已实现并做小范围 HTTP 验证；2026-10-03 最新生产前端的兼容缺口见下文，尚不能原样复制即用。
4. **生产部署**：未部署、未迁移生产用户/数据。首次管理员初始化、HTTPS Secure Cookie、Nginx alias、数据目录权限、备份与回滚需部署阶段核验。
5. **性能指标**：没有代表性历史数据规模下的 EXPLAIN/压测结果，不承诺 QPS。发现服务部分聚合及预览到 NC 候选查询尚有批量优化空间。
6. **多实例**：当前扫描互斥、登录限流辅助锁及缓存失效恢复按单实例实现；Session 共享、分布式扫描锁和可靠事件等不在本版承诺中。

## 验收口径

- 模块测试证明各自覆盖的规则，不能替代最终组合验收。
- `PlatformWorkflowIntegrationTest` 使用真实业务服务、临时数据库和合成文件，通过 HTTP 跑产品发布→扫描→检索→注册→受控下载→管理员统计/禁用。
- 全量 `clean verify` 与 GitHub CI 应基于最终组合提交执行；有跳过/失败时不得写为全部通过。
- 最终报告需明确真实容器验证、业务替身测试、尚未提供凭据的外部服务三者的区别。

## 2026-10-03：最新生产前端兼容性核对

### 输入与范围

本次权威前端副本为 `E:\Dayu_frontend_back_end_souce\FDU-CRIAS-source-20261003\FDU-CRIAS`。此前只依据 `DaYu/js/` 经典版的阶段性调查不作为新版完整兼容结论。

- 新版入口 `index.html` 的 `<base href="/flat-20260925/">` 加载 `flat-20260925/app.js`、`product-geometry.js`、`world-map.js`、样式和边界 GeoJSON。
- `classic.html`、`index_en.html` 仍使用 `js/main.js`、`slider.js`、`sidebar.js`、`auth.js`。
- 新版账号区域跳转经典页、管理页、英文页；新版自身没有完整注册/下载流程，不能只复制 `flat-20260925/` 就认为整个网站齐全。
- 对照目标是新 Java Controller、解析器、资源配置及正式 API 契约；本轮只调查和更新本文，不修改任何前端或业务代码。

### 兼容清单

以下表格记录核对时发现的问题；其中降水预报命名、时效别名、完成标记门控已于本轮完成代码与 45 项定向验收，详见 [降水兼容闭环](../reviews/reppic-compatibility.md)。不等于已经部署或完成前端真实数据联调。

| 功能 / 输入 | 新后端输出 / 当前状态 | 处理位置 |
| --- | --- | --- |
| 产品清单：`GET /api/products.php` | `{ok,products}`、`product_id/path_id/category/name_*/status` 等基本字段兼容；新系统按产品+模式展开，仅公开已发布产品 | 基础调用可保留；不要重新公开草稿来迎合旧行为 |
| BT、云、RGB 的 WebP 列表：`files.php?path=...&number=200` | `{files:[相对路径]}`，已实现单 DPI 500 别名；预报最新批次 `{latest:12位UTC}` | 基础契约可复用，实际数据仍需索引；不能把空数组验收当成图像联调成功 |
| **降水预报**：新版请求 `PRECIP_1H/2H/3H`，完整预报并行合并三次 files 查询 | **已补**：Legacy 层映射为 `PRECIP + leadMinutes=60/120/180`，保留真实文件路径 | 后端完成定向验收；不新建三个业务产品，待真实前端联调 |
| **降水 WebP 文件名**：`FY4B_AGRI_REPPIC_PRECIP_1H_<cycle>_<valid>_palettev2_Dpi500.webp` | **已补**：精确识别生产命名、目录与时间关系，并要求发布检查 | AssetIndex 完成；不将命名规则宽松外推到未确认的其他文件 |
| **降水完整批次门控** | **已补**：有效标记 + 三个时效齐全后整批事务入库；无日期特例，通用旧 PRECIP 预报名也不能绕过门控 | 目录读取故障保留旧索引并报错；可用最新批次不会被未完成的新批次抢占 |
| 新版时间轴 | 明确在前端截取最近 24 小时、整 30 分钟、最多 48 帧；后端近期窗口是 3 天 | 这是前端展示选择，不是后端只保存一天；需要日期筛选时改前端交互 |
| 云预报单位与图像几何 | `FCST_CTH/CER/COT` 不在新版本地 defaults 表；单位可能为空；`geometryFor(product.id)` 只识别实况云 ID，预报落入未验证默认范围 | 前端适配产品元数据与 canonical code/pathId，并用真实预报样本验证尺寸/裁边，不能盲目沿用 BT 默认定位 |
| 经典页历史 NC 检索 | 三个平行数组兼容，但旧 JS 把查询开始时间拼成起报目录、强制预报范围≤3小时，且只给降水传 product 参数 | 改前端查询构造；科学数据检索优先接 `/api/v1/scientific-assets`，区分有效时间与起报时间、使用分页 |
| 注册/登录/退出 | 经典页 JSON + `X-CSRF-Token`，响应 `authenticated/user/csrf_token` 与新 Legacy 一致；密码/机构长度规则一致 | 可复用经典页认证逻辑；保持同源与 Cookie，不手工假造 Session |
| NC 下载 | 经典页向 `download.php` POST `csrf_token/file_path/purpose`，新后端保留此方式，返回真实文件流 | 可复用基础表单；超时会话、下载失败反馈和预览到 NC 按钮仍要做界面联调 |
| 图片/色标静态地址 | 已支持 `/WebP/WebP_V2_Dpi500_4KM/**`、`/media/webp/**`、`/CPP_Colorbar/**` 和 `/colorbars/**` | 迁移时保持静态资源和 API 同源；NC 不作为静态文件开放 |
| 管理页面 | 旧 `admin.php/admin_products.php` 均为 410；旧 UI 的 completed_downloads 不等于新 AUTHORIZED 统计 | 必须改管理页对接 `/api/v1/admin/*`，不能恢复旧管理接口或假称完整下载 |
| AI Copilot | 新版生产前端没有对应完整 UI；后端 `/api/v1/copilot/queries` 已有输入/输出契约 | 补前端入口、时区/pageContext、筛选跳转和降级/429 提示；不新增模型自动下载权限 |

### 三个容易误判的地方

1. **样例数值查询不是当前主页面必需接口。** `app.js:15–17` 曾添加 SAMPLE/GLOBAL 条目，紧接着又从产品数组删掉；正式产品分支显示“精确数值暂不可用”。`/sample/point`、`/global/point` 和样例清单代码仍残留，但不应据此擅自扩展为 NC 数值解析项目。迁移时保留禁用/明确不可用状态。
2. **最新版已有 PRECIP 色标候选。** 新副本的 `CPP_Colorbar/horizontal/PRECIP_Colorbar_rainsnow_v2.webp` 正是新版页面引用的名称，另有普通 PRECIP 色标；没有找到对应独立 PLP 色标。本轮未复制这些文件、未修改产品 `colorbarPath`、未发布草稿。可以在后续配置正确根目录后单独核验 PRECIP 发布。
3. **不要破坏新前端的换帧逻辑。** 当前 `showFrame` 使用异步 fetch、`image.decode()`、请求代次检查和延迟释放旧图层；这是应保留的行为，不等同于已实现整段影像预加载。不要换回经典版同步 Ajax 实现。

### 降水协议的具体证据与下一步边界

- `flat-20260925/app.js:66–73`：完整降水预报先按 `PRECIP_1H` 找批次，再请求三种时效目录，并校验返回路径前缀。
- `api/reppic_cycle_gate.php:5`：每个时效目录必须恰好有一张匹配 `_palettev2_Dpi500.webp` 的非空文件，valid 必须等于 cycle 加该时效。
- `api/reppic_cycle_gate.php:48`：通常要求 `.reppic-complete.json` 中 schema/complete/cycle12/archive_sha256 格式满足条件；旧代码另有固定历史批次豁免。这是生产发布协议证据，**不自动代表新系统应该继续保留该历史硬编码豁免**。
- 核对时 Java 尚缺新命名与时效别名；这些缺口现已补齐。Catalog 仍只有一个 PRECIP，转换只在索引解析/Legacy 适配发生。

上述后端闭环已完成：**降水生产 WebP 命名解析 + 旧时效别名适配 + 完整批次可见性检查**，用合成文件和临时 MariaDB 验证。用户已确认取消历史日期硬编码豁免。核心模型保持一个 `PRECIP` 产品，时效仍是资产属性；没有修改数据库表结构。

然后再复制生产前端，保留页面、样式和地球渲染，做必要的接口、时区、元数据与管理/Copilot 补充。保留 `/flat-20260925/` 的 base 路径，或统一改写资源引用；开发代理需覆盖 API、WebP、色标等路径，不能仅代理 `/api` 而让图片落到另一个端口。

### 本轮实际执行与未执行

- 静态核对最新版页面入口、请求参数、响应读取、认证/下载表单、色标路径、降水 PHP 门控及 Java 对应实现。
- 对本地 `18080` 做了 7 个只读 GET 检查：产品清单 25 个模式展开项且字段齐全；files/latest/search 在未扫描状态下返回对应空结构；匿名 status 返回 CSRF（未输出 Token）；两个旧管理员接口返回 410。请求未访问生产服务器。
- 用 Java 已编译解析器直接检查两条合成路径：BT 预报文件被接受；上述 RePPIC palette-v2 预报路径被拒绝。没有创建科学文件或执行扫描。
- 本轮没有重新运行注册、登录、下载等写流程，没有浏览器/Cesium 真数据联调，没有调用模型、修改数据库、复制前端、提交或推送。
- 输出是本节兼容清单与下一步建议；不是“新版全部兼容”的验收结论。
