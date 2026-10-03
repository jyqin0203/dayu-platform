# Backend

Java 17 + Spring Boot 3.5.16 模块化单体后端。

首次 clone 后请先按 [新电脑首次启动指南](../docs/新电脑首次启动指南.md) 完成配置、构建、启动和数据库初始化；不要依赖旧电脑的 `target/` 或密码文件。

使用本地脚本时，NC/WebP/色标路径填 `.env.local`，Qwen 配置填 `.env`；图片服务和安全边界见 [后端配置与图片资源接入](../docs/后端配置与图片资源接入.md)。不需要临时模型探测程序来为正式后端注入配置。

七个模块通过公开 API 连接；非 `skeleton` 环境使用真实 MariaDB Repository、文件元数据扫描、Session 登录、检索及下载服务。默认不扫描任何目录、不调用付费模型。`skeleton` Profile 保留 Mock 演示，不能用其测试结果证明数据库、文件或模型集成成功。

## 构建与测试

Windows PowerShell：

```powershell
cd backend
$env:MAVEN_OPTS='-Xmx256m'
.\mvnw.cmd '-DargLine=-Xmx512m' clean verify
```

Linux/macOS：

```bash
cd backend
MAVEN_OPTS=-Xmx256m bash mvnw -DargLine=-Xmx512m clean verify
```

启动空骨架：

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=skeleton'
```

## 数据库结构

数据库结构由 `src/main/resources/db/migration` 中的 Flyway 脚本管理。真实运行时通过环境变量提供连接信息：

```text
DAYU_DB_URL=jdbc:mariadb://localhost:3306/dayu
DAYU_DB_USER=dayu
DAYU_DB_PASSWORD=<本地或部署环境提供>
```

数据库密码不得写入仓库。应用在非 `skeleton` Profile 启动时由 Flyway 校验并执行尚未应用的迁移。

数据库集成测试使用 Testcontainers 创建临时 MariaDB：

```powershell
.\mvnw.cmd -Dtest=DatabaseMigrationIntegrationTest test
```

全量验证要求 Docker daemon 正在运行，并能取得测试使用的 MariaDB、Redis、Nginx 镜像。不得把未执行或跳过的容器测试当成成功；GitHub CI 会拒绝跳过集成测试的结果。测试使用临时容器和合成文件，不连接生产数据库或扫描生产目录。

## 启动真实后端

先创建独立开发数据库并设置上面的连接环境变量；不要使用 `skeleton`。以下 YAML 放在**仓库外**的本地配置文件（路径仅示例，需替换为实际样本目录）：

```yaml
dayu:
  indexing:
    enabled: false                 # 先手动验证；启用后按 interval 扫描
    interval: 15m                 # 可配置，不是固定的数据生产频率
    dpi: 500
    roots:
      netcdf-data: E:/Dayu-dev-data/netcdf
      webp-preview: E:/Dayu-dev-data/webp
  preview:
    retention: 3d
    public-prefix: /media/webp/
  download:
    transfer-mode: LOCAL          # 本地流式传输；部署时才考虑 NGINX
    grant-ttl: 5m
  cache:
    enabled: false                # 开启前配置 spring.data.redis.*
    catalog-ttl: 5m
    preview-ttl: 2m
    forecast-cycle-ttl: 2m
  copilot:
    enabled: false
    provider: qwen
    qwen:
      model: qwen-plus
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    rate-limit:
      requests-per-minute: 30
      max-concurrent-calls: 2
```

```powershell
# 通过外部配置定位样本；不要把本地数据/凭据文件加入 Git。
$env:SPRING_CONFIG_ADDITIONAL_LOCATION='file:E:/dayu-local/application.yml'
.\mvnw.cmd spring-boot:run
```

注意：

- 注册只创建普通用户。首次管理员需由开发/部署负责人在独立数据库中核验账号后初始化；应用不附带通用管理员密码，也不开放匿名提升权限接口。
- 先配置并发布产品及其模式，再通过管理员扫描接口建立索引；扫描只读文件元数据，不解析 NC 数组、不重新生产图片。
- 当前已确认的 NC 文件名解析范围是 RePPIC 降水（同时关联 PLP、PRECIP）。辐射/云 NC 的具体文件名规则仍需真实样本确认；不会凭推断自动建错索引。
- WebP 查询返回资源 URL；本地脚本会开启受限图片服务，需配置 WebP 根目录才能读取图片。生产也可交给 Nginx。仅启动服务、不配置目录，不代表已有可视化数据。
- `LOCAL` 下载真实流式输出文件；`NGINX` 需要独立部署 [internal location](../infra/nginx-download.conf)，不能把 NC 目录直接公开为静态资源。
- Redis 仅缓存公开产品目录、WebP 时间轴和 WebP 预报批次；故障回源，不缓存历史 NC 搜索、密码或下载用途。连接/命令超时默认 250ms。启用时显式提供 `spring.data.redis.host/port`，需要认证时凭据只放外部配置；不开启时不访问 Redis。
- 启用 Qwen 时，在进程环境安全提供 `DASHSCOPE_API_KEY`，并显式开启 `dayu.copilot.enabled`。当前客户端测试使用本地假 HTTP 服务，**不代表真实百炼账号/额度已经验证**。增加提供商时实现 `AiClient`，由 `ChatClient` 路由，不改业务查询代码。
- HTTPS 部署需开启 `server.servlet.session.cookie.secure=true`；反向代理、会话多实例共享、备份及生产容量验证另行部署验收。

## 结构

七个业务模块均采用 `api/application/domain/infrastructure` 分层。跨模块调用只能引用目标模块的 `api` 包；`interfaces/rest/v1` 与 `interfaces/rest/legacy` 只作为协议适配层。依赖边界由 `ArchitectureTest` 验证。
