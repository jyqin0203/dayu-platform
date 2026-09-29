# AGENTS.md

本文件适用于整个 `dayu-platform` 仓库。

## 项目背景

大禹系统是面向辐射、云和降水气象产品的可视化与科学数据分发平台。

数据链路：

FY-4B/AGRI 原始 HDF
→ 上游处理和课题组算法
→ NetCDF 科学数值产品 + WebP 可视化产品
→ 大禹平台索引、展示、检索和受控下载

大禹系统负责：

- 近期 WebP 气象产品可视化；
- 历史 NetCDF 科学数据检索；
- 登录、用途登记和受控下载；
- 产品、文件索引和运行状态管理；
- AI Copilot 自然语言辅助检索。

## 必读文档

进行架构或代码修改前，完整阅读：

1. `README.md`
2. `docs/大禹系统MS1架构决策草案.md`
3. `docs/大禹系统七个业务模块输入输出设计.md`

文档中已确认的业务边界和架构决策优先于临时推测。

## 核心架构

后端采用 Java 17、Spring Boot 模块化单体，第一阶段不拆微服务。

七个业务模块：

- Catalog：产品定义
- AssetIndex：文件索引
- Discovery：WebP/NC 查询
- Identity：用户身份与登录
- Download：下载授权与审计
- Operations：管理员运维与监控
- Copilot：AI 自然语言查询

接入与基础设施：

- LegacyAdapter：兼容旧 PHP API
- Infrastructure：MySQL、Redis、文件系统、Nginx 和 AI 模型适配

核心约束：

- Controller 不承载业务逻辑；
- 业务模块依赖接口，不直接依赖具体基础设施；
- Discovery 不扫描硬盘；
- Download 不搜索文件；
- Copilot 不直接访问数据库、Redis 或文件系统；
- Operations 只做管理编排，不复制其他模块规则；
- 禁止模块循环依赖。

## 数据原则

- `products` 保存产品定义；
- 文件系统决定物理文件是否存在；
- `data_assets` 是可重建的文件元数据索引；
- `data_asset_products` 表达文件与产品的多对多关系；
- Redis 是临时缓存，不是事实来源。

WebP 与 NC 不强制一一对应。

时间规则：

- 后端、数据库和 API 统一使用 UTC；
- API 使用 ISO-8601；
- 前端支持不同 IANA 显示时区；
- `validTime = cycleTime + leadMinutes`。

## 认证与下载

- 使用 Spring Security、Session、HttpOnly Cookie 和 CSRF；
- 不使用 JWT；
- 前端通过 `assetId` 请求下载，不提交服务器路径；
- Java负责鉴权、用途、路径安全和审计；
- 生产环境由 Nginx `X-Accel-Redirect` 传输 NC；
- Java 不整体加载大型 NC；
- 授权成功不等于客户端已经完整保存文件。

## AI 边界

Copilot 只能：

自然语言
→ 结构化查询条件
→ Java 校验
→ 调用 Catalog/Discovery
→ 基于真实结果回答

Copilot 不得：

- 执行 SQL 或扫描文件；
- 执行下载；
- 代替用户填写用途；
- 绕过认证；
- 调用管理员能力；
- 修改产品或索引；
- 获取密码、Session 或其他用户敏感信息。

AI不可用时，普通浏览和检索必须正常工作。

## 数据与安全

禁止提交：

- HDF、NetCDF、WebP 等真实科学数据；
- `.env`、密钥和生产凭据；
- 生产数据库和日志；
- 用户资料、下载用途和 IP；
- 未经授权的课题组算法代码；
- 未脱敏的服务器调查、内部 IP 和生产配置。

真实样本必须放在仓库外，通过配置引用。

不得在生产服务器上进行日常开发、迁移、安装依赖或压测。

## 工程规范

- 修改前检查 `git status`；
- 保留用户已有修改；
- 每次只完成一个清晰、可验证的任务；
- 使用小而聚焦的 Git 提交；
- 使用 Maven Wrapper，不依赖全局 Maven；
- 配置通过环境变量或配置文件注入；
- 架构或接口变化必须同步更新文档；
- 不通过删除或弱化测试让构建通过；
- 不虚构性能数据；
- 性能结论必须有可复现的环境、命令和指标。

## 协作方式

- 每次优先讨论一个核心问题；
- 先用自然语言解释，再提供类名、接口名和代码；
- 不一次性输出未经确认的大型方案；
- 不把推测写成事实；
- 如果任务仍处于讨论或评审阶段，不擅自开始实现；
- AI可以辅助编码，但架构边界和重要决策必须由用户理解并确认。