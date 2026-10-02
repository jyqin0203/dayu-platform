# Copilot 单实例模型调用保护

本闭环在现有 `ChatClient` 出站端口前增加单实例全局限流，防止启用 Qwen 后匿名公共接口无限触发付费模型调用。没有增加 Redis、Identity 或第三方依赖；Copilot 默认仍为禁用状态。

## 调用链与边界

```text
CopilotApplicationService
→ ChatClient（RateLimitedChatClient，启用时）
  → RoutingChatClient
    → 可替换 AiClient
      → QwenAiClient
```

`RateLimitedChatClient` 是 provider-neutral 装饰器，不知道 Qwen、HTTP、用户或业务查询。`CopilotClientConfiguration` 只在 `dayu.copilot.enabled=true` 时装配该保护器；禁用时保持原有 `AI_UNAVAILABLE` 行为，不产生模型调用。

## 输入、行为和输出

| 能力 | 输入 | 行为 | 输出 / 异常 |
| --- | --- | --- | --- |
| 每分钟请求上限 | 准备发给模型的 `ChatRequest` | 单实例固定 60 秒窗口；只统计取得并发许可后准备调用下游的请求；窗口到期后重置 | 允许时调用原 `ChatClient`；超限时拒绝且不调用模型 |
| 同时在途上限 | 同一实例的并发模型调用 | 使用非阻塞许可，不让 HTTP 工作线程排队；并发拒绝不消耗分钟配额 | 无许可时立即拒绝且不调用模型 |
| 许可释放 | 正常返回、RuntimeException 或 Error | 下游调用位于 `try/finally`，所有退出路径释放并发许可 | 后续请求可继续取得许可 |
| HTTP 限流 | `BusinessException(RATE_LIMITED)` | `CopilotApplicationService` 不再把该异常吞成 AI 降级；统一异常层读取安全 details | HTTP 429、`Retry-After`、`details.retryAfterSeconds` |

分钟额度统计模型调用尝试，因此 provider 调用失败仍占用该分钟的一次额度；但并发许可一定释放。输入校验失败发生在模型调用前，不进入该限制器。

## 配置

配置通过已有 Spring 属性注入，不修改全局 `application.yml`：

| 属性 | 默认值 | 约束与含义 |
| --- | ---: | --- |
| `dayu.copilot.enabled` | `false` | 保持 Qwen 默认禁用 |
| `dayu.copilot.rate-limit.requests-per-minute` | `30` | 每个应用实例全局每分钟最多 30 次模型尝试；允许 1—10000 |
| `dayu.copilot.rate-limit.max-concurrent-calls` | `2` | 每个应用实例最多 2 个同时在途模型调用；允许 1—100 |

一分钟额度拒绝的 `retryAfterSeconds` 是当前固定窗口的剩余秒数（向上取整且至少 1）；并发拒绝返回 1 秒。错误详情不包含 prompt、用户文本、模型响应或密钥。

## 文件变更

- 新增 `copilot.application.RateLimitedChatClient`：单实例固定窗口与并发许可装饰器。
- 修改 `CopilotClientConfiguration`：增加两项可配置限制并在启用时装饰路由客户端。
- 修改 `CopilotApplicationService`：保留 `RATE_LIMITED`，由 HTTP 层输出 429。
- 新增 `RateLimitedChatClientTest`，并扩展应用、配置和 HTTP 契约测试。

没有修改 `pom.xml`、全局 YAML、数据库、Redis、Identity、Legacy、缓存或产品行为；`ChatClient`/`AiClient` 接口和 provider 替换方式保持不变。

## 验证

在 `backend` 目录使用受限堆内存执行：

```powershell
$env:MAVEN_OPTS='-Xmx256m'
.\mvnw.cmd '-DargLine=-Xmx384m' '-Dtest=RateLimitedChatClientTest,ChatClientRoutingTest,CopilotApplicationServiceTest,CopilotControllerContractTest' '-Dlogging.level.org.springframework=INFO' test
.\mvnw.cmd '-DargLine=-Xmx384m' '-Dtest=ArchitectureTest,QwenAiClientTest' '-Dlogging.level.org.springframework=INFO' test
```

结果：第一组 19 项、第二组 11 项，共 30 项，0 失败、0 错误、0 跳过，均 `BUILD SUCCESS`。覆盖确定性时钟窗口和剩余秒数、窗口重置、并发阻塞 fake client、拒绝时零模型调用、模型异常后许可释放、配置属性装配、默认禁用、未知 provider、应用层异常传播、HTTP 429/`Retry-After`、Qwen 超时/响应限制及模块依赖规则。

## 限制

- 这是每个 JVM 实例内的全局保护，不是分布式限流；多实例部署时总额度约为各实例额度之和。
- 它不是用户配额，也不按 IP、Session、账号或机构区分。匿名用户之间共享同一实例额度，不能用于计费、反滥用归因或公平性保证。
- 进程重启会清空当前固定窗口；没有跨实例或跨重启状态。
- 后续若需要分布式用户配额，应单独设计可信身份/IP 边界和 Redis 原子限流，不能把本实现宣传为已具备该能力。
