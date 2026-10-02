# Backend

Java 17 + Spring Boot 3.5.16 模块化单体后端。

当前已固定七个业务模块的公开接口、核心 DTO、依赖方向和 Mock 主流程，并提供 MariaDB 10.6 的首版 Flyway 数据库结构。真实 Repository、Redis、文件索引、Nginx 下载和 AI 服务尚未实现。`skeleton` Profile 不连接数据库，Mock 数据仅用于验证模块串联，不代表业务已经实现。

## 构建与测试

Windows PowerShell：

```powershell
cd backend
.\mvnw.cmd clean verify
```

Linux/macOS：

```bash
cd backend
./mvnw clean verify
```

启动空骨架：

```powershell
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=skeleton
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

该测试要求 Docker daemon 正在运行；Docker 不可用时测试会明确标记为跳过，不代表数据库已经验证。

## 结构

七个业务模块均采用 `api/application/domain/infrastructure` 分层。跨模块调用只能引用目标模块的 `api` 包；`interfaces/rest/v1` 与 `interfaces/rest/legacy` 只作为协议适配层。依赖边界由 `ArchitectureTest` 验证。

