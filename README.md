# DaYu Platform

大禹辐射—云—降水分析系统的 Java 与 AI 全栈重构仓库。

## 当前阶段

前后端核心功能已接通：观测台、独立科学数据检索、Session账号、用途登记与授权下载、管理员产品/扫描/用户/审计页面，以及可替换Qwen客户端的AI助手。MariaDB、文件索引和受控下载已有真实实现；本机真实亮温、NC下载与一次Qwen查询通过联调。`skeleton` 仅为无数据库演示，生产部署和缺少样本的云/降水WebP展示仍需独立验收。

运行方式见 [backend/README.md](backend/README.md)，逐模块实现与测试证据见 [docs/reviews](docs/reviews)。

新电脑从零配置、构建、启动及初始化见 [新电脑首次启动指南（Windows）](docs/新电脑首次启动指南.md)。真实配置不提交，仓库提供 `.env.example` 等无密钥模板。

完整前后端入口、数据目录和迁移检查见 [迁移与最终验收指南](docs/前后端迁移与最终验收指南.md)；本机分步提交和真实证据见 [最终验收汇总](docs/reviews/frontend-final-local-acceptance.md)。

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

## 后端验证

```powershell
cd backend
.\mvnw.cmd clean verify
```

更多说明见 `backend/README.md`。

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
