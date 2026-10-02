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
2. **真实模型联调**：未提供百炼凭据；现有模型传输测试使用本地 HTTP 替身。启用真实服务前需验证地区 Base URL、模型权限、额度、限流配置。
3. **浏览器与前端**：后端测试不等于 Cesium/下载页面完成联调。WebP URL 仍需静态资源服务映射；前端重构不在本次后端交付范围。
4. **生产部署**：未部署、未迁移生产用户/数据。首次管理员初始化、HTTPS Secure Cookie、Nginx alias、数据目录权限、备份与回滚需部署阶段核验。
5. **性能指标**：没有代表性历史数据规模下的 EXPLAIN/压测结果，不承诺 QPS。发现服务部分聚合及预览到 NC 候选查询尚有批量优化空间。
6. **多实例**：当前扫描互斥、登录限流辅助锁及缓存失效恢复按单实例实现；Session 共享、分布式扫描锁和可靠事件等不在本版承诺中。

## 验收口径

- 模块测试证明各自覆盖的规则，不能替代最终组合验收。
- `PlatformWorkflowIntegrationTest` 使用真实业务服务、临时数据库和合成文件，通过 HTTP 跑产品发布→扫描→检索→注册→受控下载→管理员统计/禁用。
- 全量 `clean verify` 与 GitHub CI 应基于最终组合提交执行；有跳过/失败时不得写为全部通过。
- 最终报告需明确真实容器验证、业务替身测试、尚未提供凭据的外部服务三者的区别。
