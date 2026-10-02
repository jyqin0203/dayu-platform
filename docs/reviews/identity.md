# Identity 实现与验收说明

## 本次实现

Identity 已增加 MariaDB 账号存储、密码校验、登录尝试审计、管理员用户管理，以及真实的 Spring Security Session 接入。其依赖仍只有 Shared Kernel；HTTP 接入层通过 Identity API 调用业务服务。

```text
注册请求 + 匿名 CSRF
→ IdentityApplicationService.register
→ 校验并生成带版本标记的密码哈希
→ JdbcUserRepository 在事务内插入 users
→ 数据库提交成功、应用服务返回
→ SessionAuthentication 更新 Session ID、保存认证上下文、旋转 CSRF
→ 返回用户公开资料和新 Token
```

数据库插入失败时不会执行 Session 建立。数据库提交后若 Session 建立异常，清理认证上下文与 Session，并提示用户登录，保留已经成功创建的账号。

## 主要文件与方法

| 文件/方法 | 输入 | 行为 | 输出 |
| --- | --- | --- | --- |
| `IdentityApplicationService.register` | 邮箱、密码、单位 | 邮箱归一化、哈希、事务插入、唯一冲突转换 | `AuthenticatedUser`，不包含密码 |
| `IdentityApplicationService.login` | 邮箱、密码、服务器取到的 IP | 分别检查邮箱/IP 限流，校验哈希和 ACTIVE 状态，持久化登录结果 | 用户或统一认证失败/限流错误 |
| `IdentityApplicationService.getCurrentUser` | SecurityContext 中的可信身份 | 按 ID 重新查询当前账号状态和角色 | ACTIVE 用户或空值 |
| `searchUsers` | 管理员身份、筛选和页码 | 查询参数化 SQL，统计总数、稳定 ID 排序并分页 | `PageResult<UserSummary>` |
| `changeUserStatus/changeUserRole` | 目标 ID、状态/角色、管理员身份 | 锁定有效管理员，检查自身禁用和最后管理员约束，事务更新 | 最新公开用户摘要 |
| `JdbcUserRepository` | 上述业务查询参数 | 访问 users/login_attempts，所有外部值使用 SQL 参数绑定 | 账号、分页、计数等内部数据 |
| `SessionAuthentication` | 已认证用户、当前请求/响应 | 更新 Session ID、显式保存 Spring SecurityContext、旋转 Token；退出使旧 Session 失效并清 Cookie | 与当前 Session 对应的 Token |
| `CurrentActorProvider` | 当前认证上下文 | 只接受服务器建立的 `AuthenticatedUser` principal，再查询当前用户 | `ActorContext` |
| HTTP 安全链 | HTTP 请求 | 管理接口要求 ADMIN、下载要求登录、所有不安全方法校验 CSRF | 业务请求或 JSON 401/403 |
| Legacy 认证适配器 | JSON/表单、旧 csrf_token/action | 复用同一 Identity 与 Session 能力；未知 action 被拒绝 | 旧版 JSON；退出返回新的匿名 Token |

新增 `UserRepository` 出站端口及其 JDBC 适配器，未改变模块之间的依赖矩阵。`SkeletonSecurityConfiguration` 保留历史类名，但现已适用于全部 Profile，不能再理解为 permitAll 配置。

## 安全与生命周期

- 每个浏览器 Session 保存独立认证上下文，HTTP 不再读取全局 Mock 当前用户。
- 注册只创建 USER，客户端提交 role/userId 不会提升权限。
- 登录、注册后更新 Session ID 并生成新 CSRF Token；原 Token 不能继续写操作。
- 获取 Session、登录、注册、退出及安全错误均使用 `Cache-Control: no-store`。
- 登出失效当前 Session 和原 Token，过期 `DAYUSESSID` Cookie；Legacy 登出额外建立新的匿名 Session。
- 账号禁用与角色变更在已有 Session 的下一个请求生效。身份存储不可用时拒绝继续认证请求。
- 原始密码不会写入数据库、公开响应或命令/HTTP DTO 的 `toString()`。
- 新密码使用 Spring Security 内置 `Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()`，通过 `DelegatingPasswordEncoder` 保存算法版本标识；支持接口约定的长 Unicode 密码。保留已有 BCrypt 哈希校验能力，但未承诺全部旧 PHP 密码算法的迁移兼容。
- 限流按邮箱和服务器 `getRemoteAddr()` 的 IP 分别计数，不信任客户端伪造的转发头。使用固定数量锁防止本实例并发绕过检查；多实例部署前应升级分布式限流。
- `DATETIME` 参数由 `Instant` 显式转换为 UTC `LocalDateTime` 后绑定，不依赖 Windows/JVM 默认时区；用户创建更新时间使用数据库 `UTC_TIMESTAMP(6)`。
- `MockIdentity` 只在 skeleton Profile 保存示例账号，非 HTTP 用户故事可用线程局部身份。该模式的账号存储不是生产持久化。

## 配置

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `dayu.identity.login-window` | `15m` | 失败统计时间窗口 |
| `dayu.identity.email-failure-limit` | `5` | 单邮箱失败上限 |
| `dayu.identity.ip-failure-limit` | `30` | 单 IP 失败上限 |
| `dayu.identity.attempt-retention` | `30d` | 登录审计保留期，部署时可调整 |
| `dayu.identity.attempt-cleanup-delay` | `PT1H` | 定期清理间隔，一批最多删除一万行 |

生产 HTTPS/Cookie Secure 与反向代理信任设置仍应由统一部署配置核验。没有访问生产数据库，也没有迁移真实用户。

## 验证进度

新增 `IdentitySessionIntegrationTest`，使用 Testcontainers 临时 MariaDB 10.6 和真实 Spring Security 过滤链，覆盖独立 Session、登录 CSRF 旋转、旧 Token 拒绝、退出 Cookie、USER 管理接口拒绝、伪造角色无效、数据库触发器回滚不登录、密码哈希、限流持久化、现有 Session 撤权、Legacy 兼容、分页及并发最后管理员保护。

首次完整相关验证执行：

```powershell
$env:MAVEN_OPTS='-Xmx256m'
.\mvnw.cmd clean '-DargLine=-Xmx512m -Duser.timezone=Asia/Shanghai' '-Dtest=IdentitySessionIntegrationTest,RemainingHttpContractTest,UserJourneyTest,AdminJourneyTest' test
```

结果：14 个测试，0 失败、0 错误、0 跳过，BUILD SUCCESS。其中真实 MariaDB+HTTP 10 项、skeleton HTTP 2 项、原有直接调用用户故事 2 项。UTC 登录审计还在 `Asia/Shanghai` JVM 下通过 SQL 与 `UTC_TIMESTAMP()` 的差值断言。

之后根据 DEBUG 日志证据补充 Legacy 请求和 Session 响应 `toString()` 脱敏，复验命令：

```powershell
.\mvnw.cmd '-DargLine=-Xmx512m -Duser.timezone=Asia/Shanghai' '-Dtest=IdentitySessionIntegrationTest,RemainingHttpContractTest' '-Ddebug=false' '-Dlogging.level.root=INFO' test
```

最终复验：12 个测试，0 失败、0 错误、0 跳过，BUILD SUCCESS。数据库触发器测试刻意产生一次受控 SQL 失败，HTTP 返回脱敏 500，用户行回滚且 Session 未认证；此错误日志是预期的测试证据。两条未受脱敏修改影响的旧用户故事已在前一轮通过。

未在本分支重复运行全部模块测试；ArchUnit 和合并后的全量验证由主 Agent 统一执行。`git diff --check` 通过，敏感模式扫描未发现密钥；无新第三方依赖、DDL 变化、真实用户或科学数据文件。

第一次并行测试因本机内存不足导致 Java/PowerShell 明显阻塞，主 Agent 协调停止了本 worktree 的 Maven 和测试 JVM。仅停止经父子 PID 和命令行确认属于 Identity 的进程，未操作用户原有容器；该轮不计为通过。
