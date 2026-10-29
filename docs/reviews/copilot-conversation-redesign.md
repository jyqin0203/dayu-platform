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
→ Java 生成已校验事实草稿、数量和白名单动作
→ 工具型意图进行第二次模型调用，仅润色最终中文回答
→ 用户点击后进入现有地图、About 或科学数据检索流程
```

开放式气象科学知识问答、知识库、互联网检索、自动下载、管理工具和长期会话均未加入。模块依赖仍为 `Copilot → Catalog API + Discovery API + Shared`。

## 公开输入、行为与输出

| 构件 | 输入 | 行为 | 输出 |
| --- | --- | --- | --- |
| `ConversationMessage` | `USER/ASSISTANT` 与消息正文 | 表达当前标签页短期上下文；日志字符串脱敏 | 不可变角色消息 |
| `CopilotCommand` | 当前问题、默认时区、可选页面上下文、短期消息 | 防御性复制上下文 | 应用服务命令 |
| `CopilotApplicationService.query` | 命令与可选 Actor | Actor 不进入模型；首次模型分类，Java 读取可信事实，第二次模型只润色回答 | `CopilotResponse` 或安全业务异常 |
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

## 工具结果二次润色

2026-10-05 根据人工验收反馈，工具型意图改为“意图识别 → Java 读取可信事实 → 第二次模型润色”。Java 仍独立保留检索条件、数量、建议动作和已脱敏降级文案；第二次模型只返回单字段 `answer`。输出格式、长度、秘密模式或内部元数据检查失败时，降级为 Java 草稿。

本地 Catalog 占位说明在进入第二次模型前已清理，Copilot 前端的产品详情动作也会抑制该占位文案，未修改 Catalog 数据库。

验证证据：

- JDK 17 后端全量 191 项测试通过；
- 前端 26 个浏览器脚本检查、70 项测试和静态构建通过；
- 真实请求“给我介绍一下 BT625”完成两次付费模型调用，HTTP 200，`PRODUCT_HELP`，`degraded=false`，回答未包含本地初始化、文件索引或版本边界文案。

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

## 2026-10-29：Spring AI、多轮状态、工具调用与流式验收版本

本次开发分支为 `feat/copilot-spring-ai`，开发目录 `/home/ubuntu/dayu-platform`。下面记录取代上文旧版的对话存储和调用流程说明；原有非流式接口继续兼容。

### 需求与实现对应

| 功能 | 实现与修改范围 | 正确性边界 |
| --- | --- | --- |
| 模型调用层 | Spring AI 1.1.8，`SpringAiQwenClient` 对接现有 Qwen 兼容端点；`AgentModel` 隔离应用层与 SDK | 不自动重试付费请求；限制模型输出、调用次数、并发和总耗时；日志不记录凭据或提示正文 |
| 多轮对话 | `ConversationCopilotService` 保存已确认查询条件和最近 6 条消息；`ConversationCopilot` 提供创建、清空、查询接口 | 会话绑定服务端 Session；30 分钟过期，最多 1000 个；同会话不可并发；失败不提交本轮工作状态 |
| 工具调用 | 模型原生选择 `LIST_PRODUCTS`、`PRODUCT_HELP`、`FORECAST_CYCLES`、`PREVIEW_SEARCH`、`SCIENTIFIC_SEARCH`、`ABOUT_DAYU`；Java 校验后调用原有 Catalog/Discovery | 无下载、SQL、文件或管理工具；未知工具拒绝；每轮最多 3 次模型调用、5 次工具调用、45 秒；最新科学数据批次使用真实元数据拼接后续查询 |
| 流式展示 | `ConversationController`、`copilot-stream.js`、`copilot.js`、`copilot.css`；`app.js` 发送页面可见时间事件 | 真正消费模型流；先展示可信结果卡片，再展示文本增量；支持停止、清空、断线错误；没有 done 不视为成功；长结果不遮挡输入区 |

新增 HTTP 路径的请求、事件和错误约定见 `docs/api/openapi-v1.yaml`。写请求保留 Session/CSRF。前端仅收到服务端 404（未开启新接口）时回退原有 `/api/v1/copilot/queries`，不会因模型报错自动重发付费调用。

时间、模式的关键约束：未提供时间时不允许模型默认补“最近一天”；单日中文日期由 Java 转换 UTC；省略年份继承已确认查询年份；明确 UTC/北京时间会更新解释时区。“改日期，其他不变”只改变日期，并保留原时段及其他条件。实况会清除预报批次和时效。复杂时间表达仍需模型解释，前端展示已确认条件供用户核对。

### 配置与运行

模型密钥沿用部署环境的 `DASHSCOPE_API_KEY`，不写入仓库。配置示例：

```yaml
dayu:
  copilot:
    enabled: true
    provider: spring-ai-qwen
    conversations:
      enabled: true
    timeout: 15s
    rate-limit:
      requests-per-minute: 30
```

新会话路径固定最多 2 轮并行，限额按实际模型调用计数；旧同步接口继续使用原有限流器，两条路径的计数独立。关闭 `conversations.enabled` 可恢复旧前端调用路径。此版本会话存储为单进程内存，不支持重启恢复或多实例共享。

验收环境独立运行：

- 前端及同源 API 代理：`http://127.0.0.1:15174/`。
- 后端：`127.0.0.1:18082`；独立 MariaDB：`127.0.0.1:13310`。
- 运行目录：`/home/ubuntu/dayu-copilot-acceptance`；后端启动器 `start.py` 从已有配置安全读取模型环境变量。
- 公开产品和资产元数据使用验收快照，未复制生产用户或下载记录；科学文件通过已有只读查询/媒体路径访问，自动扫描关闭。
- 流式验证经过前端 Node 同源代理；没有将本版本替换到生产 Nginx 或生产服务。

远程浏览器通过 SSH 转发访问：

```bash
ssh -L 15174:127.0.0.1:15174 ubuntu@服务器地址
```

随后打开 `http://127.0.0.1:15174/`，点击“和大禹 AI 聊一聊”。

### 验证方法与人工验收

后端执行非容器测试及打包：

```bash
mvn -f backend/pom.xml '-Dtest=*Test,!*IntegrationTest' verify
```

当前主机未安装 Docker，因此没有声称 Testcontainers 集成测试通过；真实 HTTP 验证使用独立 MariaDB 和现有 Qwen 配置。

前端执行 `npm run check`、`npm test`、`npm run build`。新增测试覆盖 UTF-8/SSE 任意字节拆分、CRLF、多行事件、截断、错误与非法完成事件。后端新增测试覆盖模型适配、不自动重试、真实 SSE 解析、未知工具/工具预算、会话隔离、清空、并发、凭据拒绝、日期切换、状态回滚和 HTTP 流式契约。

真实验收脚本（会产生模型调用）：

```bash
python3 scripts/copilot-acceptance-eval.py \
  --base-url http://127.0.0.1:15174 \
  --output /tmp/copilot-evaluation.json
```

脚本将 20 个场景执行 3 轮，共 60 项检查，其中 48 项为真实模型对话、12 项为会话/CSRF/非法输入检查。不是 60 个不同问题。对所有成功科学/预览查询，依据返回条件再次请求 Discovery API，比较真实文件数/帧数。覆盖平台介绍、目录、产品说明、实况、预报、预览、UTC、空结果、缺少条件、最新批次以及四轮日期追问；不把这些场景的通过率当成所有自然语言的准确率。

人工验收建议按顺序发送：

1. “查 PRECIP 科学数据” → 应追问模式。
2. “预报” → 应追问时间，不自选最近一天。
3. “北京时间 2026 年 9 月 2 日全天” → 条件为 PRECIP/预报/科学数据，当前快照为 24 个文件，文字逐段显示。
4. “改成 9 月 3 日，其他不变” → 产品和模式保留，日期更新，当前快照为 63 个文件。
5. 清空后问“查 PRECIP 最新预报批次的科学数据” → 先查询批次再检索文件，不额外强求用户给出时间。
6. 测试停止、清空、收起、普通检索与结果动作；结果动作仅由点击触发。会话停止或断线后应明确显示未完成。

### 本次实际结果

- 后端非容器测试 159 项通过，0 失败，构建打包成功。
- 前端 27 个脚本检查通过，78 项测试通过，静态构建成功。
- 最终端到端检查 60/60 通过（20 个场景各执行 3 轮）；其中真实模型对话为 48 轮。结果保存于验收目录 `evaluation-final.json`。首次回归暴露的默认时间、最新批次缺少范围和日期追问参数漂移已修复并纳入回归。
- Chromium 浏览器真实查询无页面 JavaScript 异常；桌面和 390px 手机宽度的发送区可见，停止及清空验证通过。
- 已验证前端和同源产品接口 HTTP 200。验收期间临时提高模型限额以运行批量用例，完成后恢复每分钟 30 次模型调用。
- 这些结果验证当前场景及快照，不代表任意自然语言输入都正确；请人工核对查询条件卡片和交互体验。
