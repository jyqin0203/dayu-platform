# 并行开发与主 Agent 验收

用户已授权分支开发、提交、推送、PR 和主 Agent 验收后合并。本文件记录本批次执行边界；不替代架构文档。

## 合并路径

子分支 → 模块 PR → integration/backend-modules → 总 PR → main。

82c4a0f 是 HTTP 工作快照，不代表生产可用版本。此前的 33 个测试仅证明已覆盖路径，不能证明会话隔离、完整权限或全部响应符合 OpenAPI。

## 第一批任务

| 分支 / worktree | 所有权 | 验收重点 |
| --- | --- | --- |
| agent/catalog / E:/dayu-worktrees/catalog | Catalog 与产品 HTTP | 真实 MariaDB、事务审计、模式与生命周期、并发修改、扁平响应 |
| agent/identity / E:/dayu-worktrees/identity | Identity、认证安全、Legacy auth | 注册提交后认证、独立 Session、CSRF、角色与禁用、登录限流、无密码日志 |
| agent/asset-index / E:/dayu-worktrees/asset-index | AssetIndex 与扫描查询端口 | 真实文件及 MariaDB、幂等、缺失、失败扫描不误清、分页、真实异步任务 |
| integration/backend-modules / E:/dayu-platform | 主 Agent 公共基础与集成 | 复查公共错误处理、契约、构建与跨模块衔接 |

后续任务按依赖分配 Discovery、Download、Operations、Legacy、Copilot；公共接口变更由主 Agent 协调，公共迁移脚本不可由多个 Agent 同时修改。

## 每个 PR 的交付要求

1. 业务目标和输入、行为、输出对应到具体代码。
2. 只修改拥有的模块及对应测试；不得减弱既有断言、修改生产服务器或提交真实科学数据/凭据。
3. 子 Agent 实际执行相关测试，提供数量、失败/跳过、覆盖范围；数据库测试用临时 MariaDB。
4. 主 Agent 阅读 diff，独立运行针对性检查，检查失败和权限路径；发现问题返回修订。
5. 模块 PR 只有主 Agent 可以合并。仅有路由覆盖或 Mock 成功不满足真实模块验收。
6. 集成分支在全量测试、HTTP 联调和文档说明通过之前保持总 PR 为 draft。

## 已知基线缺口

- Identity 使用进程内全局 currentUser；必须实现每 Session 隔离。
- Skeleton permitAll 不能作为管理员 HTTP 授权；部分控制器甚至没有管理员检查。
- 扫描端点同步执行后返回 202，且忽略 scanRunId；必须补真实任务端口。
- 部分响应仍存在硬编码、空日期和内部 DTO，分页和参数未完整校验。
- Legacy auth 尚缺表单兼容、action 校验、退出 Token 更新和旧式错误响应。
- 产品模式、注册、文件/时间筛选等失败边界需要真实测试，不能仅依据 happy path 验收。

以上缺口属于本批次修复内容。此文件的存在不能作为未修复代码可上线的依据。
