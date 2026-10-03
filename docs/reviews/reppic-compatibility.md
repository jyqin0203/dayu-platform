# 降水预报兼容闭环：实现与验收

日期：2026-10-03。依据最新生产副本的 `flat-20260925/app.js` 与 `api/reppic_cycle_gate.php`。

用户已明确：**新系统不保留任何固定历史日期的完成标记豁免**。规则统一适用于已确认的 RePPIC palette-v2 预报批次。本轮不改前端、不新建三个时效产品、不迁移表结构、不扫描真实目录。

## 输入、行为、输出

| 入口 / 方法 | 输入 | 执行行为 | 输出 |
| --- | --- | --- | --- |
| `AssetFilenameParser.parse` | 相对文件路径、类型、DPI | 严格匹配生产 palette-v2 命名；核对目录、起报、有效时间与 1/2/3 小时时效 | `ParsedAsset`：产品统一为 PRECIP、时效 60/120/180，标记需要发布检查 |
| `FileInventory.inspectReppicCycle` / `ReppicReleaseInspector.inspect` | 配置的 WebP 根、起报时间 | 最多读取 16 KiB 完成标记，检查三个时效目录的文件元数据 | READY + 三条元数据；NOT_READY；或 UNREADABLE |
| `PersistentAssetScanner` | 手动/定时扫描调用 | 普通文件维持原规则；受控降水先收集批次，再校验并整批更新索引 | 扫描统计、错误与 AssetIndexChanged 通知 |
| `AssetIndexStore.replaceReppicCycle` | 已验证的三条资产及产品关联，或空列表 | 同一 MariaDB 事务写入三张图；空列表撤下该批次的受控 WebP 索引 | 创建/更新/撤下数量及受影响产品 ID；不修改磁盘文件 |
| `LegacyPathParser` | `PRECIP_1H/2H/3H`，也接受 FCST_ 前缀 | 映射为 canonical code 与 lead，不建立新产品 | PRECIP + 60/120/180 分钟 |
| Legacy `latest/files/search` | 原生产前端的路径/产品参数 | 查完整批次；预览与 NC 搜索都保留时效筛选；冲突时效返回旧式空结果 | 仍是 `{latest}`、`{files}`、`{files,sizes,times}`，不修改旧响应形状 |

示例：

```text
forecast/<cycle>/PRECIP_2H/
  FY4B_AGRI_REPPIC_PRECIP_2H_<cycle>_<valid>_palettev2_Dpi500.webp
    → product = PRECIP
    → dataMode = FORECAST
    → leadMinutes = 120
    → validTime 必须等于 cycleTime + 120 分钟
```

物理路径保留原样，因此旧前端检查 `PRECIP_2H/` 前缀仍成立；索引的产品关联则统一到 PRECIP。

## 完整批次的明确规则

- 批次目录中必须有 `.reppic-complete.json`。
- `schema` 为 `fducrias-reppic-complete/v1`，`complete` 为布尔 true，`cycle12` 对应目录起报，`archive_sha256` 符合 64 位小写十六进制格式。
- 三个时效目录各有且仅有一张匹配该批次 palette-v2 规则的非空图片，有效时间分别为起报 +1/+2/+3 小时。
- 拒绝错误时效、重复候选、空文件、越界链接、异常/重复字段/尾随 JSON 和超大标记；不为任何日期放宽条件。
- 校验只检查发布元数据；**不会读取归档计算 SHA256，不验证科学数组或图片像素内容**。上游仍需可信地写入文件，并在就绪后原子发布完成标记。

READY 后三张图在一个事务中写入，防止查询看见“刚写好第一张”的新批次。标记缺失/撤回或缺图时，下次扫描撤下该批次的 WebP 索引；图片仍在磁盘上。即使一个批次的图片全部消失，也会从已有受控索引中找到并复核该批次。

目录无法读取时与“已明确未完成”区分：记录扫描错误并保留上次索引，避免误删。此时查询反映的是上次成功索引状态，不是磁盘实时健康证明。

未完成但可正常检查的批次可以使扫描正常结束而新增索引为 0，这是“暂不发布”，不是虚构数据已准备好。

## 范围与兼容性

- PRECIP 的 WebP 预报统一通过已确认的 **RePPIC palette-v2 发布协议**；旧通用 PRECIP 预报文件名也会被转入批次检查，不能绕过标记单独入库。不会把这条要求强加给普通 BT/云图片或 NC 下载。
- NC 仍独立入库、独立检索；一个 RePPIC NC 仍可关联 PLP 和 PRECIP。
- 通用旧文件名的识别保留，但识别成功不等于已发布。实况降水若使用另一种尚未提供证据的命名，需要真实样本确认，不能由本次预报文件规则外推。
- 发布/撤下在下一次扫描时生效，查询不实时遍历磁盘；沿用原有缓存失效事件。
- 门控控制索引与可用批次列表，不是图片 URL 的鉴权机制；已配置的公开图片服务仍按静态资源规则工作。
- 原始文件从不由这段逻辑删除，也不会自动给缺失的批次伪造完成标记。

## 验证证据

使用 Java 17、小型合成文件、临时 MariaDB 10.6。没有接触现有开发库或服务器数据。

第一组 **35 项全部通过，0 失败/错误/跳过**：

```powershell
.\mvnw.cmd '-DargLine=-Xmx384m' '-Dtest=AssetFilenameParserTest,ReppicReleaseInspectorTest,LegacyPathParserTest,LegacyDataControllerContractTest,LocalFileInventoryTest,ArchitectureTest' test
```

覆盖三时效归一化、时间/目录/DPI 错配、历史日期同样要求标记、标记伪造/超大/损坏、缺帧/空帧/重复帧、Windows junction 拒绝、Legacy 时效传递与冲突，以及架构约束。

第二组 **10 项全部通过，0 失败/错误/跳过**：

```powershell
.\mvnw.cmd '-DargLine=-Xmx512m' '-Ddebug=false' '-Dtest=AssetIndexIntegrationTest,ReppicWorkflowIntegrationTest' test
```

- 真 MariaDB：第二张图片的产品关联写入故意触发外键错误，第一张也完整回滚；正常三张提交和整批撤下成功。
- 真扫描→真索引→真 Discovery→Legacy HTTP：没有标记时仅 NC 入库；齐全后 WebP 三张入库；三个旧别名分别返回对应一张；NC 的 `PRECIP_2H` 搜索只返回 +2 小时。
- 新批次缺图/空图时仍返回上一完整批次；补齐后成为最新；撤回标记后退回上一批次，磁盘图片未删除。
- 即使有一个更新批次包含三张旧通用 PRECIP 文件，也不能跳过发布标记成为最新预报。原通用部分帧分页测试改用 BT，并额外加入同产品 NC 记录保持文件类型过滤断言；未删除原分页/排序断言。
- V1 同样可查到三张受控图片；没有新增 PRECIP_1H/2H/3H 产品。
- 存储根临时不可用时保留上次三张图的索引；NC 的三条可用资产不受 WebP 门控影响。

合计本轮 **45 项定向测试**，不是全仓测试数量。新增测试没有删除或减弱原断言，没有关闭约束；没有运行全量测试、调用模型、部署、commit/push。

补齐旧通用 PRECIP 文件名不能绕过门控的回归后，最终将上述 8 个测试类合并执行：**45 项全部通过，0 失败、0 错误、0 跳过**，耗时 2 分 15 秒（2026-10-03）。

代码与测试已完成，但当前常驻后端未重新构建/重启，真实服务器样本与新版 Cesium 浏览器联调仍待后续验收。实际 PLP/PRECIP 产品的发布状态未由本轮代码测试修改。
