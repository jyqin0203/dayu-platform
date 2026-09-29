# DaYu Platform

大禹辐射—云—降水分析系统的 Java 与 AI 全栈重构仓库。

## 当前阶段

项目处于 MS1 架构阶段。当前目标是先稳定系统边界、模块接口、关键数据模型和主流程，再进入具体业务实现。

## 系统边界

```text
FY-4B/AGRI 原始 HDF
→ 上游算法与课题组模型
→ NetCDF 科学数值产品 + WebP 可视化产品
→ 大禹平台索引、展示、检索和受控下载
```

本仓库不保存生产 HDF、NetCDF、WebP、用户数据库、凭据或服务器配置。

## 目录

```text
backend/   Spring Boot 后端
frontend/  Web/Cesium 前端
docs/      架构与工程文档
infra/     本地开发和部署基础设施
scripts/   非生产数据的开发辅助脚本
```

## 核心业务模块

```text
Catalog
AssetIndex
Discovery
Identity
Download
Operations
Copilot
```

详细决策见 `docs/`。

## 数据目录

真实开发样本必须放在仓库外，并通过环境变量或本地配置引用。例如：

```text
E:\Dayu-dev-data
```

## 安全要求

- 不提交 `.env`、密钥和生产配置；
- 不提交 NC、HDF、WebP 等真实数据；
- 不提交用户信息、下载用途和访问日志；
- 不直接在生产服务器上开发或压测；
- 所有生产部署必须基于已测试的版本化构建产物。

