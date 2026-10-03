# 历史亮温 WebP 本地联调验收

日期：2026-10-03。只操作本地开发库与开发进程，未修改生产环境、源图片或默认三天预览规则。

## 数据与配置

- 来源：用户提供的 `FDU-CRIAS/WebP/WebP_V2_Dpi500_4KM/forecast`。
- 共 252 张非空 WebP，约 59.7 MB；2025-10-28 的 16:00、16:15、16:30 UTC 三批，7 个亮温波段，每波段每批 12 帧。
- 为不扫描旁边的 realtime，复制 forecast 到本地 Git 忽略目录 `tmp/historical-webp/forecast`。原数据未移动或改名。
- `.env.local` 的 `DAYU_WEBP_ROOT` 指向本仓库绝对路径 `tmp/historical-webp`；NC、色标根仍为空。
- 当前 Java 进程通过环境变量 `DAYU_PREVIEW_RETENTION=400d` 临时放宽历史预览。定时扫描、Redis 和 Copilot 仍关闭。

## 输入、操作和输出

管理员 Session/CSRF → 手动扫描 API → 文件元数据写入 MariaDB → Discovery 返回历史预报列表 → 开发代理传输真实 WebP → Cesium 绘制 → 页面播放/时效切换。

扫描任务 1：SUCCEEDED；扫描 252、创建资产 252、更新 0、撤下 0、错误 0。没有导入 NC 或修改产品配置。

## 实际验证

- 7 个波段均返回最新批次 `202510281630`，每个 12 帧。
- 每波段抽取第一帧，经前端代理读取均为 HTTP 200，文件头为真实 RIFF/WEBP，而非错误 HTML。
- 使用 Playwright 技能执行浏览器交互，并检查截图：10.8 μm 图片已覆盖地图；自动播放时帧号、有效时间变化。
- 点击 `+2h` 后停止播放并显示 `2025-10-28 18:30 UTC`、`4 / 12 帧`；对应起报 `16:30 UTC`，计算一致。
- 页面 Cesium 错误面板数 0；浏览器控制台 0 错误、0 警告。
- 截图保存在本地忽略目录 `output/playwright/historical-bt108.png`，不提交科学图像。

## 尚未覆盖

这证明真实扫描→索引→查询→图片传输→页面显示/切换链路可用，不证明科学数值或投影定位精度。
本批没有云产品和降水；亮温公开产品详情的 colorbarUrl 为空，页面如实提示未配置色标。本轮没有擅自修改 Catalog。
历史日期不改成今天，页面仍显示真实数据时间；默认首页 CTH 实况无数据，需选择预报下的亮温产品。

## 本机查看与恢复

打开 `http://127.0.0.1:15173/` → 底部“预报” → 选择“10.8μm 亮温”。

当前后端与前端保留运行。若重启后仍要看历史样本，可在 Java 17 环境下执行本地忽略脚本 `tmp/start-historical-preview.ps1`（先用 scripts/stop-local.ps1 停止已有后端）。该脚本仅对启动子进程设置 400d，不扫描、不调用模型。

恢复正常三天窗口：停止本项目后端，用常规 `scripts/start-local.ps1 -DisableCopilot` 启动，且不要设置 DAYU_PREVIEW_RETENTION。索引记录保留，但历史帧被默认窗口过滤。
若不再使用本地样本，可将 `.env.local` 的 DAYU_WEBP_ROOT 清空后重启；本轮没有自动删除样本或索引。

未 commit/push。下一项待确认：配置已核实的色标路径并验证色标；云/降水仍需对应真实样本。

## 后续验收：7 个亮温色标（2026-10-03）

本节更新上文“亮温色标未配置”的状态，其余历史记录保留。

- 输入：旧系统 `CPP_Colorbar/horizontal/BT*_Colorbar.webp` 中对应 7 个亮温波段的文件。
- 操作：复制到忽略目录 `tmp/historical-colors/CPP_Colorbar/horizontal`，将 `.env.local` 的 DAYU_COLORBAR_ROOT 指向 `tmp/historical-colors`，重启本地后端并保留 400d 历史预览窗口。
- 经 Session/CSRF 登录本地管理员，通过 PUT `/api/v1/admin/products/{productId}` 只补 colorbarPath。逐字段检查名称、单位、说明、来源、排序、colorbarRequired、状态和模式均未改变；没有发布草稿，没有写 SQL 绕过应用服务。
- 修改前的 7 条产品详情保存在忽略文件 `tmp/products-before-colorbars.json`，本地一次性操作脚本为 `tmp/configure-local-colorbars.mjs`；该脚本拒绝覆盖已配置色标，不可当作任意覆盖工具重复运行。
- 输出：7 个公开详情的 colorbarUrl 均有值；经前端代理逐一请求均为 HTTP 200 / image/webp，且文件头为 WEBP。
- Playwright 浏览器检查：BT108 色标成功解码，尺寸 1222×302、hidden=false、单位 K。本次是配置联调，不涉及生产代码修改，未重复执行后端全量测试。
- 切换到 BT108 预报后色标仍可见并成功解码，播放显示 `10 / 12 帧`，实况与预报共用同一产品色标。
- 云产品、降水色标未在本轮配置；未将图片、凭据或本机配置加入 Git。

下一步建议将当前前端目录/时间轴适配与验收记录整理成阶段 PR；缺少样本的云/降水验收仍需保留为明确限制。
