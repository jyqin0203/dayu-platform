# 大禹系统 API 实现缺口清单

> 状态：由已确认的 /api/v1 与 Legacy 契约倒推
> 日期：2026-10-02
> 用途：说明当前空骨架距离 HTTP 契约还缺什么，不表示这些能力已经完成。

## 1. 当前已有

- 七个业务模块的公开 API、DTO、Mock 主流程和 ArchUnit 约束；
- MariaDB/Flyway 首版结构；
- Session、CSRF、产品、资产、下载和扫描的领域方向；
- interfaces/rest/v1 与 interfaces/rest/legacy 空接入层位置。

当前 24 条 /api/v1 路径（28 个操作）和 8 个 Legacy 路由均已有 Controller 映射；
Repository、真实 Spring Security 登录、文件扫描、Nginx 下载和 AI Provider 仍未实现。

## 2. 公共接入能力

| 状态 | 能力 | 后续实现或结果 | 最低验证 |
| --- | --- | --- | --- |
| 已完成 | 统一错误响应 | BusinessException、400/422、未知异常和 traceId | 7 个 MVC 契约测试 |
| 部分完成 | 参数与 UTC 校验 | Bean Validation 已接入；时间组合规则随各 Controller 实现 | 各接口 400/422 测试 |
| 已完成 | 分页上限 | page 默认 1，pageSize 默认 20、最大 100 | MVC 契约测试 |
| 待实现 | Session/CSRF | 匿名 Session Token、认证 Session | 安全集成测试 |
| 待实现 | 管理员授权 | URL 与方法权限 | USER/ADMIN 测试 |
| 待实现 | 接入层 DTO | 模块 DTO 到 JSON 的显式映射 | JSON 契约测试 |

公共 HTTP 基础当前实现于 interfaces/rest/v1，包括 ApiErrorResponse、V1ExceptionHandler、TraceIdFilter 和 PageParameters。Session Cookie 名称已配置为 DAYUSESSID；这不代表真实认证已经完成。

## 3. Catalog

- [已完成] 增加创建或更新 ProductModePolicy 的公开管理能力和 Mock 规则验证；
- [已完成] 实现公开产品目录和单产品详情 Controller；
- [已完成] 增加批量公开详情查询，避免 HTTP 列表逐产品查询；
- 实现 MariaDB Repository 和事务；
- 发布/重新发布时验证启用模式、色标并写管理审计；
- [已完成] 实现管理员产品 Controller 适配层；
- 保持 DRAFT → PUBLISHED ↔ DISABLED，重新发布不覆盖首次 publishedAt。

最低验证：Catalog 单元测试、MariaDB 集成测试、公开/管理员 HTTP 契约测试。

## 4. AssetIndex 与 Discovery

- 实现文件名和目录解析器；
- 实现初次全量及可配置增量扫描；
- 将手动扫描改为异步任务提交，并支持按 ID 查询；
- 持久化扫描批次和安全错误摘要；
- [已完成] 实现 WebP 时间轴、预报批次和 NC 分页 HTTP 适配层；
- 用代表性数据执行 EXPLAIN；
- 为 Legacy 增加受限的 storageKey + relativePath → assetId 内部查询端口。

最低验证：临时目录、重复扫描、缺失 WebP/NC、非法文件名、并发扫描冲突和 MariaDB 查询测试。

## 5. Identity

- 实现用户 Repository、密码哈希和登录失败限流；
- 注册事务提交后再建立认证 Session；
- 实现 Session Fixation 防护和 CSRF Token 生命周期；
- 增加管理员用户分页查询；
- 禁止管理员禁用自己或移除最后一个有效管理员；
- [已完成] 实现 Session、注册、登录、退出和管理员用户 Controller 适配层；

最低验证：事务失败不建立 Session、Token 与 Session 一致、禁用用户不能登录、限流、CSRF 和权限测试。

## 6. Download

- 实现 assetId + purpose 授权事务和审计快照；
- [已完成] 在 Mock 范围生成短期 downloadEventId 内容地址并校验申请人；
- 本地使用 Java 分块适配器，生产使用 Nginx X-Accel-Redirect；
- 实现授权过期、资产消失和路径逃逸检查；
- 保持当前决定：Download 不额外依赖 Catalog，已知旧 assetId 在资产仍可用时可以申请。

最低验证：未登录、用途长度、非 NC、MISSING、过期授权、非申请人、路径逃逸和 Nginx 隔离环境测试。

## 7. Operations

- 将同步扫描接口调整为异步提交结果；
- 增加扫描历史分页和详情；
- 增加用户分页编排；
- [已完成] 实现仪表盘、产品健康、授权下载统计和审计 HTTP 适配层；
- 统计名称不得把 AUTHORIZED 写成客户端“下载完成”。

最低验证：管理员权限、运行中扫描冲突、统计口径、敏感字段不出现在响应中。

## 8. Copilot

- 接入 AI Provider，并设置超时、限流和降级；
- 将模型输出解析为受控结构，再由 Java 校验；
- 只调用 Catalog 和 Discovery；
- Suggested Action 使用固定白名单；
- 第一版不持久化完整对话、不流式响应、不执行下载或管理操作。
- [已完成] 实现 Copilot HTTP 适配层和动作响应映射；

最低验证：非法模型输出、未知产品、时区转换、提示注入、Provider 超时、降级与动作白名单。

## 9. Legacy Adapter

已实现六个兼容 Controller 路由和两个 410 Gone 端点：

~~~text
products.php      → Catalog
files.php         → Discovery
fcst_latest.php   → Discovery
search.php        → Discovery
auth.php          → Identity
download.php      → AssetIndex + Download
admin_products.php/admin.php → 410
~~~

必须通过 ArchUnit 保证 Legacy 不直接访问 Repository、数据库或文件系统。

当前 Legacy 行为仍基于 skeleton Mock；真实文件大小、目录解析覆盖面和表单兼容性需要在文件索引实现后补充完整契约测试。

## 10. 推荐实现顺序

~~~text
1. 公共错误、分页和安全接入骨架
2. Catalog MariaDB + /api/v1 Catalog
3. AssetIndex/Discovery + 查询 API
4. Identity + Session/CSRF
5. Download + Nginx/本地适配
6. Operations 异步扫描和管理接口
7. Copilot
8. Legacy Adapter
9. 全链路契约与浏览器测试
~~~

每一项仍按“一个可审查、可验证闭环”实施，不一次生成全部 Controller。
