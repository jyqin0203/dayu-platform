# Backend

Java 17 + Spring Boot 3.5.16 模块化单体空骨架。

当前只固定七个业务模块的公开接口、核心 DTO、依赖方向和 Mock 主流程，不连接 MySQL、Redis、真实文件系统、Nginx 或 AI 服务。`skeleton` 是默认 Profile，Mock 数据仅用于验证模块串联，不代表业务已经实现。

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

## 结构

七个业务模块均采用 `api/application/domain/infrastructure` 分层。跨模块调用只能引用目标模块的 `api` 包；`interfaces/rest/v1` 与 `interfaces/rest/legacy` 只作为协议适配层。依赖边界由 `ArchitectureTest` 验证。

