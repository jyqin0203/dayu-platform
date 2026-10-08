# 大禹 · DaYu Platform

面向气象科研的可视化与数据服务平台。

大禹将分散存储的气象产品组织为可浏览、可检索、可下载的数据资源，提供地图展示、历史数据检索、受控下载和 AI 辅助查询，帮助用户从产品与时间出发找到所需数据。

## 功能概览

- **气象可视化**：基于 Cesium 展示亮温、云与降水产品，支持区域切换、二维/三维视图、时间轴播放、预报时效和色标展示。
- **科学数据检索**：按产品、实况/预报、有效时间检索，支持起报时间、预报时效及分页筛选；可在 UTC 与北京时间之间切换。
- **受控下载**：下载前验证用户身份并登记用途，支持大文件流式传输与授权记录追溯；登录过程中保留检索条件和选中文件。
- **AI 数据助手**：理解自然语言查询，调用后端数据服务并提供检索、预览和产品说明建议；用户确认后应用查询条件，服务不可用时可继续使用普通检索。
- **产品与数据管理**：管理产品资料、实况/预报模式及发布状态，触发文件扫描，查看任务历史、错误明细和产品更新状态。
- **账号与审计管理**：区分普通用户与管理员，提供账号状态、角色管理和下载授权记录筛选。

## 技术架构

后端采用 **Java 17 + Spring Boot 3 的模块化单体架构**，前端使用原生 JavaScript 与 Cesium。文件存储与业务元数据分离，页面查询通过数据库索引完成，不在每次检索时遍历科学文件。

| 组成 | 技术与用途 |
| --- | --- |
| 后端服务 | Spring Boot、JDBC、MariaDB |
| 数据库迁移 | Flyway |
| 查询缓存 | Redis，用于产品目录、预览列表和预报批次等查询 |
| 地图与页面 | Cesium、HTML、CSS、JavaScript |
| AI 服务 | Qwen，自然语言理解与结构化查询 |
| 文件传输 | 本地流式传输，或由 Nginx 接管受控下载 |
| 测试与交付 | JUnit、ArchUnit、Testcontainers、Node.js Test Runner、GitHub Actions |

### 业务模块

| 模块 | 职责 |
| --- | --- |
| Catalog | 产品目录、模式配置和发布状态 |
| AssetIndex | 文件扫描、元数据解析、资产索引与产品关联 |
| Discovery | 可视化帧、预报批次和历史科学数据查询 |
| Identity | 注册登录、用户身份与角色管理 |
| Download | 下载授权、文件传输与审计 |
| Operations | 管理概览、扫描任务、用户与审计管理 |
| Copilot | 自然语言理解、查询条件校验与建议动作 |

各模块通过公开接口协作，架构测试约束模块之间的依赖方向。

## 数据如何流转

```text
上游卫星处理与科研算法
    ├── 科学数据文件 ── 扫描与元数据索引 ── 条件检索 ── 授权下载
    └── 可视化图片   ── 扫描与元数据索引 ── 时间轴与地图展示
```

- 科学数据文件保存数值结果，可视化图片用于网页展示，两者分别管理。
- 一个科学文件可以关联多个产品；文件是否可下载与是否存在对应图片分别判断。
- 时间以 UTC 处理，预报数据区分起报时间、有效时间和预报时效。
- 文件接入依赖明确的目录与命名规则。新增数据来源需要核对并适配解析规则，不能仅凭文件扩展名自动识别所有产品。
- 平台负责数据接入与服务，不运行上游气象反演或预报算法。

## 快速启动

### 环境要求

- JDK 17
- Node.js 22 或更新版本，推荐 Node.js 24
- Docker 与 Docker Compose
- Git

### 1. 获取代码

```bash
git clone https://github.com/jyqin0203/dayu-platform.git
cd dayu-platform
```

### 2. 启动后端并初始化

以下为 Windows PowerShell 开发流程。运行前启动 Docker，并确认 `JAVA_HOME` 指向 JDK 17。

```powershell
.\scripts\setup-local.ps1
.\scripts\build-local.ps1
.\scripts\start-local.ps1 -DisableCopilot
.\scripts\initialize-local-products.ps1 -BootstrapAdmin
```

`setup-local.ps1` 生成缺失的本地配置和开发凭据；`-BootstrapAdmin` 仅用于空用户库。已有数据库不要重复执行管理员初始化。构建脚本只负责打包，不代替测试。

### 3. 启动前端

在另一个终端中执行：

```powershell
cd frontend
npm.cmd ci --ignore-scripts
npm.cmd run dev
```

默认访问地址：

| 服务 | 地址 |
| --- | --- |
| 观测台 | `http://127.0.0.1:15173/` |
| 管理页面 | `http://127.0.0.1:15173/admin.html` |
| 后端 API | `http://127.0.0.1:18080/api/v1/` |

管理员账号配置见本机 `.env.local-admin`。未配置数据目录和执行扫描时，产品可能没有可预览或可下载的文件。

完整配置及 Linux 部署相关说明请参阅下方文档；上述 PowerShell 脚本不适用于直接在 Linux 上运行。

## 配置说明

- **数据目录**：在 `.env.local` 中设置科学文件、WebP 和色标目录。数据文件不随 Git 分发。
- **模型服务**：根据 `.env.example` 配置 Qwen 密钥、模型名称和启用开关；需要启用 AI 时，启动后端不加 `-DisableCopilot`。
- **缓存与扫描**：开发启动脚本默认关闭 Redis 缓存和定时扫描；可使用管理员页面手动扫描，其他运行配置参阅后端文档。
- **生产部署**：前端构建为静态文件，配合后端 API 和 Nginx 部署。科学文件不得作为无鉴权的静态目录直接开放。

## 测试与构建

前端：

```powershell
cd frontend
npm.cmd run check
npm.cmd test
npm.cmd run build
```

后端（运行集成测试前需启动 Docker）：

```powershell
cd backend
.\mvnw.cmd '-DargLine=-Xmx512m' verify
```

前端静态产物输出到 `frontend/dist/`。GitHub Actions 执行前后端验证；具体测试范围和运行方式见各自的 README。

## 仓库结构

```text
backend/    Spring Boot 后端与数据库迁移
frontend/   观测台、管理页面与前端构建
docs/       架构设计、API 契约与使用文档
infra/      开发基础设施与配置
scripts/    本地配置、启动和初始化脚本
```

## 文档

- [新电脑首次启动指南](docs/新电脑首次启动指南.md)
- [前后端迁移与验收指南](docs/前后端迁移与最终验收指南.md)
- [后端开发说明](backend/README.md)
- [前端开发说明](frontend/README.md)
- [业务模块设计](docs/大禹系统七个业务模块输入输出设计.md)
- [HTTP API 契约](docs/api/openapi-v1.yaml)

请勿将真实科学文件、用户数据、`.env`、模型密钥或生产凭据提交到仓库。
