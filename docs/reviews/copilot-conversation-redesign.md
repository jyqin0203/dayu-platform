# 大禹 Copilot 对话助手第一版实现与验收说明

日期：2026-10-05。状态：代码与自动验证已完成，等待用户人工验收页面和真实模型对话。

## 交付范围

本轮只调整 Copilot API、应用服务、HTTP 适配、前端 Copilot 组件及其测试，没有修改 Catalog、Discovery、Identity、Download、Operations、AssetIndex、数据库迁移或总体模块依赖。

第一版实现：

- 将右上角 `AI` 按钮移动并改造为“气象图层”面板正下方的“和大禹 AI 聊一聊”文字入口；入口随面板高度和窗口尺寸重新定位；
- 使用不带遮罩的非模态抽屉，地图在对话时仍可操作；
- 提供欢迎消息、消息气泡、收起、清空、Enter 发送和普通检索入口；
- 移除 Copilot 内的 UTC/北京时间选择器，未明确时区的自然语言默认按 `Asia/Shanghai` 解释；
- 支持平台能力、About、公开产品列表、单产品说明、WebP 预览查询、NetCDF 科学数据查询、预报批次和范围外问题；
- 使用带 `USER` / `ASSISTANT` 角色的当前标签页短期上下文，最多 12 条消息、总用户文本最多 8000 字符，不写数据库；
- 对话结果采用文字回复、紧凑条件摘要和白名单动作，不在抽屉内重复展示完整 WebP 图片或 NC 文件表；
- 用户点击后才打开 About、产品目录、切换地图产品或进入原有科学数据检索；Copilot 不直接下载或执行管理动作。

## 可信调用链

```text
用户消息 + 最多 12 条短期角色消息
→ 模型只识别受限意图和参数
→ Java 严格校验字段、产品、模式、UTC 时间和解释时区
→ Copilot 固定 About 摘要 / Catalog 公开产品 / Discovery 真实查询
→ Java 生成回答、数量和白名单动作
→ 用户点击后进入现有地图、About 或科学数据检索流程
```

开放式气象科学知识问答、知识库、互联网检索、自动下载、管理工具和长期会话均未加入。模块依赖仍为 `Copilot → Catalog API + Discovery API + Shared`。

## 公开输入、行为与输出

| 构件 | 输入 | 行为 | 输出 |
| --- | --- | --- | --- |
| `ConversationMessage` | `USER/ASSISTANT` 与消息正文 | 表达当前标签页短期上下文；日志字符串脱敏 | 不可变角色消息 |
| `CopilotCommand` | 当前问题、默认时区、可选页面上下文、短期消息 | 防御性复制上下文 | 应用服务命令 |
| `CopilotApplicationService.query` | 命令与可选 Actor | Actor 不进入模型；模型只分类，Java 从可信来源生成答案 | `CopilotResponse` 或安全业务异常 |
| `CopilotController.query` | HTTP JSON | 校验 IANA 时区、角色、长度和嵌套 DTO | 条件、解释时区、回答和建议动作 |
| `queryCopilot` | 问题和短期角色消息 | 使用 Session/CSRF，同源 POST，默认北京时间，不自动重试 | 经基本格式校验的响应 |
| `safeAction` | 后端建议动作 | 仅接受五种固定动作并重建参数，丢弃 URL 等额外字段 | 可由浏览器执行的本地动作 |

白名单动作只有：`OPEN_ABOUT`、`OPEN_PRODUCT_CATALOG`、`OPEN_PRODUCT_DETAILS`、`APPLY_PREVIEW_FILTER` 和 `APPLY_SCIENTIFIC_SEARCH`。

## 文件变化

### 后端主代码

- `copilot/api/ConversationMessage.java`：新增角色化短期消息 DTO，并对日志字符串脱敏。
- `copilot/api/CopilotCommand.java`：上下文从无角色字符串调整为角色消息。
- `copilot/api/InterpretedCriteria.java`：增加 `interpretedZone`，让前端无需解析回答文字即可知道时间解释。
- `copilot/application/CopilotApplicationService.java`：扩展八类受控意图；加入稳定 About 摘要、Catalog 产品回答、Discovery 查询、预报批次、针对性追问、北京时间解释和新 Prompt；所有结果数量与动作继续由 Java 生成。
- `copilot/application/MockCopilot.java`：同步新查询类型、解释时区和动作参数，继续只代表 skeleton 用户故事，不代表真实模型能力。
- `copilot/infrastructure/CopilotClientConfiguration.java`：记录不含密钥值的配置可用性，便于区分开关、密钥和模型配置问题。
- `copilot/infrastructure/QwenAiClient.java`：增加安全失败分类日志，只记录配置、超时、HTTP 状态或异常类型，不记录密钥、Prompt 和响应正文。
- `interfaces/rest/v1/copilot/CopilotController.java`：接收嵌套角色消息并输出解释时区，保留 HTTP 校验和 DTO 转换职责。

### 前端主代码

- `frontend/public/flat-20260925/copilot-client.js`：固定默认北京时间、发送最多 12 条角色消息，并扩展本地动作白名单。
- `frontend/public/flat-20260925/copilot.js`：把原按钮移动到气象图层面板下方并随面板调整位置；实现非模态对话抽屉、短期上下文、紧凑摘要和用户确认动作；移除时区下拉框和抽屉内完整预览图片。
- `frontend/public/flat-20260925/copilot.css`：实现桌面右下抽屉、手机底部抽屉、浮动入口、消息气泡、摘要卡片和操作按钮样式。
- `scripts/start-local.ps1`：使用 `Start-Process -Environment` 将数据库密码、Spring JSON 和 Qwen 密钥显式传给 Java 子进程；密钥仍不进入命令行、JSON和日志。

### 测试与文档

- `CopilotApplicationServiceTest.java`：覆盖 About、公开产品、角色上下文、真实查询计数、空结果、注入防护、追问、时区和降级。
- `CopilotControllerContractTest.java`：覆盖嵌套角色消息、非法角色、默认北京时间及原有限流契约。
- `frontend/copilot-client.test.mjs`：覆盖新动作白名单、默认北京时间、12 条角色上下文和 503 不重试。
- `docs/大禹Copilot对话助手设计方案.md`：将方案状态更新为已实现、待人工验收。
- 本报告：记录需求到代码映射、验证证据和剩余人工验收项。

## 自动验证证据

- `npm run check`：26 个浏览器脚本通过语法检查。
- `npm test`：69 项通过，0 失败、0 跳过。
- `npm run build`：静态站点构建成功。
- 使用本机 JDK 17 执行 Copilot、HTTP、配置绑定、Qwen 回环适配、限流、用户故事和架构定向测试：35 项通过。
- 后端全量 `verify` 执行了 188 项测试，0 失败、0 错误、0 跳过；随后因当时运行中的后端占用目标 JAR，生命周期在重命名 JAR 时失败。精确停止该进程后执行 `mvnw.cmd -DskipTests package`，重打包 `BUILD SUCCESS`，没有通过跳过测试掩盖失败，测试与打包证据分别保留。
- 新后端使用 JDK 17 启动；`18080/api/v1/products`、`15173/` 和前端同源产品代理均返回 HTTP 200。
- 已确认 15173 实际提供的新 Copilot 脚本含右下角文字入口、无时区选择器、使用角色上下文。

## 真实模型修复与验证

首次通过完整应用调用“大禹是什么？”在约 53 毫秒内返回 `503 AI_UNAVAILABLE`。同一密钥和模型直接调用百炼兼容端点约 611 毫秒成功，返回 `qwen3.8-flash`、`finish_reason=stop` 和合法 JSON，因此账号、额度、模型权限和外网正常。

增加脱敏配置诊断后，启动日志明确显示 `Copilot enabled=true` 但 Qwen Bean 为 `keyPresent=false`。根因是当前 PowerShell 环境中，通过 `System.Environment` 修改父进程环境没有可靠进入 `Start-Process` 创建的 Java 子进程。启动脚本改用显式 `-Environment` 后，日志显示 `keyPresent=true`。

修复后再次通过 Session、CSRF、Copilot Prompt、Qwen、JSON 意图解析和 Java 回答的完整 HTTP 链路调用“大禹是什么？”：

- HTTP 200，约 1.85 秒；
- 意图为 `ABOUT_DAYU`，`degraded=false`；
- 回答来自 Copilot 固定可信 About 摘要；
- 建议动作仅为 `OPEN_ABOUT`。

共发生两次成功的真实付费厂商调用：一次最小直连诊断、一次修复后的端到端验收。首次应用内 503 在网络调用前失败，没有自动重试。所有输出均未包含密钥。

## 人工验收

请用户在 `http://127.0.0.1:15173/` 验收：

- 右上导航不再显示独立 AI 项，“气象图层”面板正下方出现“和大禹 AI 聊一聊”；
- 打开抽屉后仍能拖动和缩放地图，收起不清空，点击清空才恢复欢迎消息；
- 桌面和手机布局、遮挡关系、文字密度与按钮位置符合预期；
- 询问“大禹是什么”“有哪些产品”“PRECIP 是什么”时内容和动作合理；
- 先问“查 PRECIP 预报科学数据”，再补充时间，可以延续上一轮条件；
- 未说明时区时按北京时间解释，明确说 UTC 时按 UTC 解释，并在结果中复述；
- 空结果明确显示 0，不伪造文件；预览、科学检索和 About 只有点击按钮后才打开；
- 普通地图、产品目录和科学检索在 AI 不可用时仍可使用。

## 未自动声称完成的事项

- 真实调用只证明已选场景和完整调用链正常，不代表所有自然语言、产品别名和时间表达都已验收。
- 没有使用浏览器自动化代替人工视觉验收，也没有部署或修改生产服务器。
