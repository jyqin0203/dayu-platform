# Copilot 实现与验收说明

## 目标与调用链

本轮实现可替换模型客户端的自然语言检索助手，首个真实厂商适配器为阿里云百炼 Qwen。默认关闭模型调用，未配置密钥时仍能启动；没有读取本机实际密钥、连接付费模型或修改生产环境。

```text
HTTP问题 + IANA时区 + 少量页面/对话上下文
→ CopilotApplicationService 校验输入、构建公开产品上下文
→ ChatClient 端口
→ RoutingChatClient 按配置选择 AiClient
→ QwenAiClient 调用配置的百炼端点
→ 严格解析结构化检索条件
→ Catalog 校验产品已发布、模式启用
→ Discovery 查询真实文件索引
→ Java根据真实数量生成回答和白名单筛选动作
```

用户点击建议动作后进入普通检索页面；登录、填写下载用途和真正下载仍走原有流程。Copilot 没有下载、扫描、用户管理、SQL 或文件读取工具。

## 输入、行为与输出

| 构件/方法 | 输入 | 行为 | 输出 |
| --- | --- | --- | --- |
| `ChatClient.complete` | 脱敏系统提示和公开用户上下文 | 定义与厂商无关的模型调用端口 | 模型结构化文本 |
| `AiClient.provider` | 无 | 标识具体厂商实现 | 如 `qwen` |
| `RoutingChatClient.complete` | 同上 | 根据 `provider` 选择已注册适配器；关闭/未知厂商时安全失败 | 模型文本或脱敏不可用异常 |
| `QwenAiClient.complete` | 同上 | 使用 Java HttpClient，Bearer 认证，JSON Object、非流式输出；限制总超时和响应体积 | `choices[0].message.content` |
| `CopilotApplicationService.query` | 问题、显示时区、页面上下文、最近消息；可选 Actor | 验证模型条件，调用 Catalog/Discovery，只根据业务结果生成答案 | 条件、答案和建议动作；或澄清/降级 |
| `CopilotController.query` | HTTP JSON | IANA 时区和字段校验，转换成业务 DTO | 类型化 HTTP DTO，正确保留可空字段 |

实现依赖仍为 `Copilot → Catalog API + Discovery API + Shared`。模型适配器位于 Copilot 自己的 infrastructure；应用层只依赖 ChatClient 出站端口。没有新增第三方依赖。

`InterpretedCriteria` 增加可空 `cycleTime`、`leadMinutes`，保留原五参数构造器。两项条件会同时传给 Discovery 和前端筛选建议，避免“指定6点起报、+2小时”在中途被丢弃。

## 已实现规则

- 查询类型固定为 `PREVIEW / SCIENTIFIC_ASSET / PRODUCT_HELP`。
- 建议动作固定为 `APPLY_PREVIEW_FILTER / APPLY_SCIENTIFIC_SEARCH / OPEN_PRODUCT_DETAILS`，由 Java 构造；不接收模型的动作、URL、SQL、文件名或结果数量。
- 严格拒绝重复 JSON 键、尾随 JSON、未知字段、无效模式、非法时间、非整数/负时效；实况不能携带起报批次和时效。
- 所有模型时间必须是以 `Z` 结尾的 UTC 时间，要求 `from <= to`。
- 未知产品不会被替换为默认产品；缺少产品、模式或时间时返回澄清问题。
- 页面时区省略时继承请求显示时区；两处显式时区不一致返回 422。仅接受 IANA 标识，固定偏移 `+08:00` 不作为 IANA 时区接受。
- 单条消息最多 2000 字符、历史最多 6 条、全部用户文本最多 8000 字符；模型上下文最多 40000 字符；厂商请求编码后最多 128 KiB。
- 提示只包含问题、短期上下文、当前时区/时间和最多 64 个已发布产品的名称/简述/模式。不包含 Actor 的用户 ID、角色、单位、Session、历史下载用途或服务器路径；明显的密码、Cookie、API Key 文本被入口拒绝。
- 模型失败、超时、异常输出时，有合法当前产品上下文则返回产品说明并标记 `degraded=true`；否则返回 `503 AI_UNAVAILABLE`。
- Qwen 响应不允许 tool calls 或截断完成状态；默认最多 64 KiB，超限立即取消读取。总超时覆盖连接、响应头和响应体，默认不重试，也不跟随重定向。
- 无模型密钥、关闭开关或未知 provider 不发起网络调用。正常产品查询仍由独立 Catalog/Discovery 提供。
- skeleton Mock 的动作名称同步为正式白名单；Mock 不代表真实模型理解能力。

## 配置与厂商替换

| 配置 | 默认值 |
| --- | --- |
| `dayu.copilot.enabled` | `false` |
| `dayu.copilot.provider` | `qwen` |
| `dayu.copilot.qwen.model` | `qwen-plus` |
| `dayu.copilot.qwen.base-url` | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| `dayu.copilot.qwen.api-key` | 空；可由 `DASHSCOPE_API_KEY` 环境变量提供 |
| `dayu.copilot.timeout` | `10s` |
| `dayu.copilot.max-response-bytes` | `65536` |

应用会在 Base URL 后追加 `/chat/completions`，可以配置不同地域或业务空间域名。地域与密钥需匹配，依据[百炼 Base URL 官方说明](https://help.aliyun.com/zh/model-studio/base-url)；认证和消息协议参考[子业务空间调用说明](https://www.alibabacloud.com/help/zh/model-studio/model-calling-in-sub-workspace)。结构化输出使用 `response_format=json_object`，提示词明确包含 JSON，参考[结构化输出说明](https://help.aliyun.com/zh/model-studio/qwen-structured-output)。

新增厂商时，实现 `AiClient` 并注册为 Bean，再修改 `dayu.copilot.provider` 即可，业务服务与 Controller 无需修改。测试已经用 `alpha`、`beta` 两种独立假适配器证明配置切换。

## 测试证据

测试使用 Mockito 和本地回环 HTTP 假服务，不需要 MariaDB、真实 API Key 或付费调用。

```powershell
$env:MAVEN_OPTS='-Xmx256m'
.\mvnw.cmd '-DargLine=-Xmx256m' '-Dtest=CopilotApplicationServiceTest,QwenAiClientTest,ChatClientRoutingTest,CopilotControllerContractTest' '-Ddebug=false' test
```

首轮18项全部通过。补充预览分支与“无密钥请求次数为零”明确断言后，最终19项全部通过，0失败、0错误、0跳过，BUILD SUCCESS：业务服务9项、Qwen回环HTTP6项、厂商配置切换2项、HTTP契约2项。

测试覆盖：真实查询结果计数来源、空结果、未知产品和缺失时间澄清、模型注入/重复键/额外字段拒绝、模式时间校验、提示中无 Actor 信息、上下文安全降级、配置切换两种客户端、Qwen请求格式、HTTP错误、分块超大响应、慢响应体超时、无密钥不联网、可空字段序列化和时区冲突。

## 限制

没有执行真实百炼模型调用，不能声称自然语言理解准确率已验证。产品查询在这些单元测试中使用受控桩，最终由主 Agent 合并真实 Catalog/Discovery 后联调。未增加长期聊天存储、科学数组分析、自动下载或管理员工具；多用户额度与生产网关限流仍需部署阶段统一配置。

本轮使用 agent-reach 核对官方文档，协议按官方兼容端点实现；不依赖 OpenAI 服务或 OpenAI API Key。
