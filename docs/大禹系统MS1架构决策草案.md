# 大禹系统 MS1 架构决策草案

> 状态：讨论中，尚未进入开发定稿  
> 首次整理：2026-09-29  
> 目标：记录 Java 重构前已经确认的系统边界、核心概念和架构决策，作为后续模块输入输出设计、接口契约和空骨架设计的依据。  
> 说明：本文只记录当前已经达成一致的内容。未确认事项明确留空，不以推测冒充生产契约。

---

## 1. 当前所处阶段

本项目参考《架构设计规范》的流程推进：

```text
已确定的产品目标
→ 风险分析
→ 模块拆分
→ 模块输入输出和接口契约
→ 重点模块详细设计
→ 可编译空骨架
→ 开发实现
```

产品目标已经明确，本轮不重新进行功能发散。当前已基本完成核心风险分析，下一步进入模块拆分和输入输出设计。

---

## 2. 系统目标与边界

大禹系统定位为：

> 接收上游已经生产完成的 WebP 和 NetCDF 文件，建立可查询的文件元数据索引，为用户提供近期气象产品可视化、历史科学数据检索、受控下载、产品管理、运行监控和 AI 辅助检索。

已确认的数据层级为：

```text
FY-4B/AGRI 原始观测数据（HDF）
→ 上游校准、通道处理和课题组算法
→ 课题组 NetCDF 科学数值产品
→ WebP 可视化产品
```

上游也可能从同一批内存数组并行生成 NC 和 WebP。其内部究竟是“先写 NC 再渲染”还是“从数组分别输出”，不属于大禹系统责任边界，也不阻塞本系统设计。

### 2.1 上游数据生产系统负责

- 运行卫星数据处理、反演算法和 RePPIC-Net；
- 读取和处理 FY-4B/AGRI 原始 HDF 观测数据；
- 生产 NetCDF 科学数据；
- 根据处理结果生产 WebP 展示图片；
- 保证科学变量、数值、单位和数据内容正确；
- 按约定目录和文件名发布完整文件。

### 2.2 大禹系统负责

- 识别和索引已经存在的 WebP/NC 文件；
- 组织产品、实况/预报、起报时间、有效时间和预报时效；
- 提供最近 WebP 时间序列浏览；
- 提供历史 NC 检索；
- 实施登录、用途登记、权限校验和下载审计；
- 提供管理员产品管理、数据状态和索引触发能力；
- 为 AI Copilot 提供受控的业务查询工具。

### 2.3 第一版明确不负责

- 在线运行气象算法；
- 修改或重新计算 NC 科学内容；
- 解析 NC 内部变量并向用户提供变量级查询；
- 通过网页上传大型科学文件；
- 从 WebP 反演物理数值；
- 保存、索引或分发 FY-4B/AGRI 原始 HDF；
- 使用 AI 直接访问数据库、磁盘或绕过下载权限。

原始 HDF 由风云卫星官方数据服务负责分发。大禹系统只提供数据来源说明和官方平台跳转，不代理官方登录或重复保存 HDF。

---

## 3. 事实来源划分

已确认以下职责：

| 信息 | 事实来源 |
| --- | --- |
| 系统定义了哪些气象产品 | `products` 表 |
| 物理文件是否真实存在 | 服务器硬盘/挂载文件系统 |
| 当前可查询的文件元数据 | `data_assets` 文件索引表 |
| 文件包含或关联哪些产品 | `data_asset_products` 关联表 |
| 热点查询结果 | Redis 临时缓存 |

架构原则：

> 硬盘是文件是否真实存在的最终依据；数据库文件索引是为了快速查询而建立的可重建索引；Redis 不是事实来源。

`products` 中存在产品，不代表硬盘上一定存在对应 WebP 或 NC。空目录也不能生成文件资产记录。

---

## 4. 核心领域概念

### 4.1 Product

Product 表示用户能够认识和选择的一种气象变量或图层类别，不表示具体文件。

示例：

```text
BT855   8.55μm 亮温
CTH     云顶高度
COT     云光学厚度
PRECIP  降水强度
PLP     雨雪相态
RGB     真彩色
FRGB    假彩色
```

以下内容不是 Product：

```text
realtime / forecast  数据模式
+1h / +2h / +3h     预报时效
202609020600.nc      物理文件
2026-09-02 06:00    数据时刻
```

因此旧系统中的 `FCST_BT855` 在新领域模型中应理解为：

```text
Product：BT855
数据模式：FORECAST
```

Product 还应保存简要来源信息，用于数据说明和溯源，但第一版不建立复杂的数据血缘子系统：

```text
producer            产品生产者，如课题组
algorithmName       如自研云产品算法、RePPIC-Net
sourceDescription   如“基于 FY-4B/AGRI 观测数据生成”
officialSourceUrl   FY-4B 官方原始数据入口
```

需要明确区分：

```text
FY-4B/AGRI：原始观测来源
课题组算法：云和降水科学产品生产者
大禹系统：产品展示和分发平台
```

### 4.2 DataAsset

DataAsset 表示硬盘上的一个真实物理文件。

主要属性方向已经确定：

```text
id / assetId
assetType          WEBP / NETCDF
dataMode           REALTIME / FORECAST
cycleTime          起报时间，实况为空
validTime          有效时间
leadMinutes        预报时效，实况为空
relativePath       相对于配置根目录的路径
fileSize
checksum           可选或按阶段计算
dpi                WebP 可用，NC 为空
fileModifiedAt
indexedAt
```

第一版采用的合理假设：

> 一个物理文件对应一个有效时间；一个物理文件可以关联一个或多个 Product。

### 4.3 DataAssetProduct

文件和产品为多对多关系：

```text
products
data_assets
data_asset_products
```

典型示例：

```text
一张 BT855 WebP → BT855

一个 BT.nc
├─ BT625
├─ BT695
├─ BT742
├─ BT855
├─ BT108
├─ BT120
└─ BT133
```

WebP 与 NC 之间不直接建立强制的一对一外键。

### 4.4 User

User 表示注册用户或管理员，核心信息包括：

```text
邮箱
密码哈希
工作单位
角色
账号状态
```

### 4.5 DownloadAudit

DownloadAudit 表示用户的下载申请、授权和传输状态记录。审计重点是行为，而不是未经证明地声称客户端已经保存成功。

---

## 5. 时间模型与时区

### 5.1 实况数据

```text
validTime    数据实际对应时间
cycleTime    null
leadMinutes  null
```

### 5.2 预报数据

```text
cycleTime    起报时间
validTime    预报对应时间
leadMinutes  预报时效（分钟）
```

必须满足：

```text
validTime = cycleTime + leadMinutes
```

### 5.3 时区决策

- 后端和数据库统一使用 UTC；
- API 使用 ISO-8601 UTC，例如 `2026-09-02T06:00:00Z`；
- UTC 是系统唯一时间基准；
- 前端显示时区属于用户偏好；
- 前端支持 UTC 和任意 IANA 时区，如 `Asia/Shanghai`、`Europe/London`；
- 用户输入按照当前选择的显示时区解释，再转换为 UTC 提交；
- 所有时间输入、结果列表和时间轴必须明确显示当前时区，禁止出现无时区说明的时间。

---

## 6. 文件索引策略

### 6.1 扫描策略

第一版采用简单、可配置的方案：

```text
首次建立全量索引
运行时按配置间隔增量扫描
管理员可手动触发一次立即增量扫描
```

不在第一版引入：

- MQ 发布事件；
- 上游主动回调 Java；
- 分布式扫描任务；
- 复杂文件状态机。

### 6.2 幂等性

- `relativePath` 在同一存储空间中唯一；
- 重复扫描不得产生重复资产记录；
- 已存在文件可更新文件大小、修改时间和索引时间；
- Java 只索引正式发布目录中的正式文件，不处理上游临时目录和临时后缀。

### 6.3 扫描配置

扫描是否启用和扫描间隔均不能写死：

```yaml
dayu:
  indexing:
    enabled: true
    interval: 15m
```

实际间隔在确认上游生产频率后通过部署配置调整。调度采用 fixed delay，避免上一次扫描未结束又启动下一次。

手动“立即扫描”和定时扫描调用同一增量扫描能力。全量重建可保留为内部维护能力，第一版不急于暴露在管理页面。

---

## 7. WebP 与 NC 的生命周期

### 7.1 WebP

- 用于近期可视化；
- 当前业务确认只支持查看最近约三天；
- WebP 文件被上游清理后，对应 `data_assets` 索引应随之移除；
- 历史 WebP 不作为第一版产品能力；
- 具体保留时间仍应通过配置表达，避免硬编码。

### 7.2 NetCDF

- 用于历史科学数据检索和下载；
- 不受 WebP 三天预览窗口限制；
- NC 文件索引长期保留；
- 第一版不解析 NC 内部科学内容。

### 7.3 预览与下载的关系

- WebP 预览和 NC 检索是两个独立后端能力；
- NC 历史检索是下载系统的核心；
- 近期 WebP 页面可以提供“下载对应 NetCDF 科学数据”作为附加便利；
- 该功能根据产品和时间动态搜索 NC，不依赖 WebP→NC 强制外键；
- 没有匹配 NC 时明确显示“暂无对应 NetCDF 科学数据”。

---

## 8. 已确认的数据产品映射

### 8.1 云产品

这些云产品不是 FY-4B 官方 L2 产品，而是课题组基于 FY-4B/AGRI 观测数据，通过自研算法生成的科学产品。依据 [`Draw_FD.py`](../Draw_FD.py)：

```text
Per_CPP[0] → CLP → CLP WebP
Per_CPP[1] → CTH → CTH WebP
Per_CPP[2] → CER → CER WebP
Per_CPP[3] → COT → COT WebP

CER + COT + CLP → CWP → CWP WebP
CTH + CLP + CWP → CBH → CBH WebP
```

因此一个云产品/CPP 数据资产可关联：

```text
CLP、CTH、CER、COT、CWP、CBH
```

注意：CWP 和 CBH 是在 WebP 生成阶段计算得到的，未确认它们是否作为独立变量直接存储在 NC 内部；这不影响其关联到同一个 CPP NC 资产。

### 8.2 RePPIC 降水产品

本地 NC 查看工具已经确认 RePPIC NC 包含：

```text
latitude
longitude
precipitation_phase_class
precipitation_phase_probability
precipitation_rate
```

因此一个 RePPIC PRECIP NC 同时关联：

```text
PLP     雨雪相态
PRECIP  降水强度
```

推定的 WebP 生成用途：

```text
precipitation_phase_class
+ precipitation_phase_probability（可能作为辅助）
→ PLP WebP

precipitation_rate
→ PRECIP WebP
```

降水 WebP 生成程序当前尚未接入生产或尚未取得，不影响 NC 与产品的关联建模。

RePPIC-Net 属于课题组产品生产算法。其输入包含 FY-4B/AGRI 观测信息，并融合其他上游数据；大禹系统不负责运行或解释其模型内部过程。

### 8.3 RGB 与 FRGB

`Draw_FD.py` 已确认：

```text
RGB 输入：0.6μm、0.8μm、0.4μm 通道
FRGB 输入：bt 数组的部分红外通道
```

这些数组来源于 FY-4B/AGRI 上游处理结果。`Draw_FD.py` 自身不读取 HDF 或 NC，而是接收已经加载到内存的数组。当前尚未取得它的调用方，因此数组从 HDF 读取、校准并同时输出 NC/WebP 的具体过程，以及 FRGB 的准确波段数组顺序仍未确认。该内部过程不属于大禹系统范围。

### 8.4 单波段亮温

旧系统命名和产品设计支持以下映射：

```text
BT.nc
→ BT625、BT695、BT742、BT855、BT108、BT120、BT133
```

但当前 `Draw_FD.py` 不负责生成七种单波段 BT WebP，相关生成程序尚未取得。该映射目前作为认可的产品族配置保留，后续用生产程序补充证据，不阻塞表结构设计。

七个亮温波段与 FY-4B/AGRI 的 6.25、6.95、7.42、8.55、10.8、12.0、13.3μm 红外通道一致。大禹只管理上游交付的 BT 科学产品和 WebP，不处理原始 HDF 通道校准。

---

## 9. DPI 与前端预加载

### 9.1 DPI

第一版决定：

- 固定使用一个可配置的 WebP DPI；
- 默认使用 Dpi500；
- 只扫描和索引配置的 WebP 根目录；
- 前端暂不提供多 DPI 自动选择或手动切换；
- 其他 DPI 作为未来扩展，不进入第一版核心范围。

示例：

```yaml
dayu:
  preview:
    webp-root: /data/WebP/WebP_V2_Dpi500_4KM
    dpi: 500
```

### 9.2 前端预加载

- 预加载属于前端详细设计，不属于后端文件索引职责；
- 后端负责返回按时间排序的 WebP 帧列表和 URL；
- 前端负责提前加载相邻帧、取消过时请求和释放旧图层；
- 保留并进一步核对线上新版前端已经实现的预加载机制；
- 不建议一次性长期保留全部高分辨率帧在浏览器内存。

---

## 10. 查询与下载接口边界

### 10.1 WebP 预览查询

只查询近期 WebP，主要输入方向：

```text
productCode
dataMode
时间范围
cycleTime（可选）
leadMinutes（可选）
```

主要输出方向：

```text
WebP URL
validTime
cycleTime
leadMinutes
```

### 10.2 NC 科学数据查询

只查询 NetCDF，主要输入方向：

```text
productCode
dataMode
历史时间范围
cycleTime（可选）
leadMinutes（可选）
```

主要输出方向：

```text
assetId
文件名
文件大小
validTime
cycleTime
leadMinutes
```

### 10.3 assetId

`assetId` 是数据库为每一个真实文件分配的唯一内部编号。第一版使用 `BIGINT` 自增主键即可。

下载请求只接收：

```text
assetId
purpose
```

前端不再提交服务器文件路径。`assetId` 本身不代表权限，后端仍需完成身份、资产类型、文件状态和真实路径校验。

---

## 11. 身份认证与权限

### 11.1 认证方式

第一版使用：

```text
Spring Security
+ Session
+ HttpOnly Cookie
+ CSRF
```

不使用 JWT。

原因：当前是同源网站，要求支持立即退出、账号禁用和角色变化；Session 实现更简单且更符合当前业务。

Session 只保存必要身份信息：

```text
userId
role
登录时间
最后活动时间
```

以后多实例部署时，可以通过 Spring Session 将 Session 存储切换到 Redis。

### 11.2 权限矩阵

| 能力 | 访客 | 注册用户 | 管理员 |
| --- | ---: | ---: | ---: |
| 查看产品目录 | ✓ | ✓ | ✓ |
| 浏览近期 WebP | ✓ | ✓ | ✓ |
| 查询历史 NC 元数据 | ✓ | ✓ | ✓ |
| 下载 NC | ✗ | ✓ | ✓ |
| 填写下载用途 | — | 必须 | 必须 |
| 管理产品 | ✗ | ✗ | ✓ |
| 手动触发索引扫描 | ✗ | ✗ | ✓ |
| 查看下载审计和统计 | ✗ | ✗ | ✓ |
| 启用/禁用用户 | ✗ | ✗ | ✓ |

NC 查询结果不向前端返回服务器物理路径。

---

## 12. 下载传输与审计

### 12.1 生产传输方式

职责划分：

```text
Java：登录、权限、用途、assetId、路径安全、审计
Nginx：从硬盘高效传输 NC
```

生产环境使用 Nginx `X-Accel-Redirect` 内部转发。内部下载位置必须配置 `internal`，用户不能绕过 Java 直接访问。

本地开发环境可以提供 Java 分块传输适配器；切换传输实现不得改变业务授权规则。

### 12.2 下载审计语义

第一版主要状态：

```text
REQUESTED
AUTHORIZED
DENIED
```

第一版不把 Java 授权成功表述成“客户端已完整保存”。管理员统计应使用“下载申请数”或“授权下载数”。

为以后通过 Nginx 日志补充传输结果，预留：

```text
expectedBytes
deliveredBytes
authorizedAt
finishedAt
status 可扩展为 DELIVERED / INTERRUPTED
```

Java 在授权时生成 `downloadEventId`。未来 Nginx 日志可记录该 ID、状态码、发送字节数和完成情况，并异步回写审计记录。

服务端最多能够证明完整字节已经发送，不能绝对证明用户已将文件保存到本地硬盘。

---

## 13. Redis 缓存

### 13.1 第一版缓存内容

```text
已发布产品目录
每个产品的最新可用时次/最新预报批次
最近三天的 WebP 时间轴查询结果
```

### 13.2 第一版不缓存

```text
WebP/NC 文件内容
历史 NC 搜索结果
下载用途和下载审计
用户密码哈希
管理员统计
```

### 13.3 缓存模式

采用 Cache Aside：

```text
查 Redis
→ 命中则返回
→ 未命中查 MySQL
→ 写入 Redis
```

索引扫描发现文件变化后，删除相关产品的最新批次和时间轴缓存；产品管理操作后删除产品目录缓存。

Redis 故障时直接查询 MySQL，系统可以变慢，但核心查询不能因此完全不可用。

缓存开关和 TTL 均通过配置提供，不写死在代码中。

Redis 还可以用于：

```text
登录失败计数
接口限流计数
未来的分布式 Session
```

---

## 14. 旧接口兼容

迁移期间同时提供：

```text
旧 `.php` URL 兼容层
新 `/api/v1` 接口
```

兼容层只负责转换旧参数和旧 JSON 格式，必须调用同一套 Java 业务服务，不复制业务逻辑。

现有前端可以先用于验证 Java 后端。新版前端稳定后，再停止使用并移除旧接口兼容层。

---

## 15. AI Copilot 边界

Copilot 定位为数据发现能力的自然语言入口：

```text
自然语言
→ 结构化查询条件
→ 调用受控 Discovery 能力
→ 返回查询结果和解释
```

允许调用：

```text
查询产品目录
查询近期 WebP
查询历史 NC
查询可用预报批次
```

禁止：

```text
直接访问 MySQL
直接扫描硬盘
执行下载
代替用户填写用途
绕过登录
调用管理员能力
修改产品和索引
发送密码、Session、其他用户信息或历史用途给模型
```

AI 不可用或返回错误时，普通产品浏览和搜索界面必须正常工作。

---

## 16. 初步模块边界

当前建议的业务模块：

```text
Catalog       产品定义
AssetIndex    文件索引
Discovery     WebP/NC 查询
Identity      用户与登录
Download      下载授权与审计
Operations    管理与监控
Copilot       AI 自然语言查询
```

接入和基础设施层：

```text
LegacyAdapter   兼容旧 PHP 接口
Infrastructure  MySQL、Redis、文件系统、Nginx、模型适配
```

模块职责、输入、输出和依赖方向尚未逐个定稿，是下一阶段讨论内容。

---

## 17. 仍待确认但不阻塞表结构的事项

- `BT.nc` 七个波段的生产程序和准确数组顺序；
- RGB/FRGB 上游数组的具体加载与校准程序；
- FRGB 的 `bt` 数组准确波段顺序；
- 降水 WebP 的实际生产脚本和上线时间；
- 线上实际 WebP 清理任务与参数；
- 定时索引扫描的最终生产间隔；
- 新版前端当前预加载窗口和资源释放机制；
- 未来是否开放多 DPI 自动选择。

以上内容通过配置或补充生产契约解决，不阻塞当前领域模型和模块拆分。

以下事项已经明确不需要继续追查，也不应成为开发阻塞项：

- WebP 是否一定通过重新打开 NC 文件生成；
- 上游是否从同一内存数组并行写出 NC 和 WebP；
- FY-4B/AGRI HDF 的内部读取和校准实现。

---

## 18. 下一步

按照架构设计流程，下一阶段逐个模块确定：

1. 模块职责和明确不负责的事项；
2. 输入命令和查询；
3. 输出 DTO 和事件；
4. 模块依赖的接口；
5. 模块之间的调用方向；
6. 需要优先详细设计的高风险流程。

完成后再定稿数据库 DDL、HTTP API 和可编译空骨架。

