# Frontend

当前阶段：迁入 2026-10-03 的生产新版，不改页面样式或换帧逻辑。来源为 `FDU-CRIAS-source-20261003/FDU-CRIAS`；10 个原始文件的 SHA256 记录在 `source-manifest.json`。用户确认后，仅对两个 HTML 入口替换地图依赖引用，改为本地 Cesium；其余原始文件保持不变。

## 本地运行

需要 Node.js 22 或更新版本；已用 Node.js 24 验证。开发服务使用 Node 内置模块，地图依赖固定为官方 `cesium@1.146.0`，没有引入 React/Vue/Vite：

```powershell
cd frontend
npm.cmd ci --ignore-scripts
npm.cmd run dev
```

打开 <http://127.0.0.1:15173>。Java 后端默认位于 `127.0.0.1:18080`，按仓库的新电脑启动指南先启动后端。

如端口不同，在启动前设置 `DAYU_FRONTEND_PORT` 和 `DAYU_BACKEND_URL` 环境变量。代理仅允许本机 HTTP 后端，不读取任何 `.env` 密钥文件，不转发到生产站点。

## 文件与请求走向

- `public/index.html`、`public/flat-20260925/`：原始生产页面及资源，保留 `<base>` 路径。
- `/vendor/cesium/1.146.0/`：只开放 `node_modules/cesium/Build/Cesium`，不开放整个 node_modules。
- `public/cesium-config.js`：在地图脚本之前设置 `CESIUM_BASE_URL`，Workers/Assets/Widgets/ThirdParty 均走同源地址。
- `/api/`、`/media/webp/`、`/WebP/`、`/CPP_Colorbar/`、`/colorbars/`：同源转发给 Java，保留 Cookie/CSRF、响应状态与文件字节。
- 未复制 PHP、真实 WebP/NC、生产配置、经典版、旧管理页或旧英文页。
- 经典页的功能后续整合进新版，不作为独立入口继续维护。原账号弹窗中的旧链接本轮未改，当前相关地址会返回 404；这是后续任务，不是已经完成的登录/下载界面。
- 统一业务 SDK 和产品/时效/图层参数适配留到下一步；本轮没有把原有请求改写成新逻辑。

## 验证与当前阻塞

```powershell
npm.cmd test
```

本分支 PR 与推送会触发 `Frontend verification`：按锁文件安装依赖、检查脚本语法、运行上述测试、构建并核验本地 Cesium 资源，再保存 `frontend-dist` 构建产物。PR 仍会执行现有后端 CI；这些检查不等于自动完成浏览器或真实气象数据验收。

5 项开发服务测试通过，覆盖静态路径、API/图片代理、Cookie/CSRF 与二进制字节传递、私有路径拦截、后端不可达，以及 Cesium JS/CSS/纹理元数据的本地访问。通过真实代理可读取 25 个公开模式项；因没有扫描数据，文件列表为空。

原天地图 Cesium JS、扩展 JS 和 Widgets CSS 在本地返回 HTTP 418；按用户决定改为官方固定版本。本版应用只调用官方 Cesium API，没有引入天地图扩展。`1.146.0` 是本次选定并验证的版本，不宣称与原生产 CDN 的未知版本完全一致；实际气象影像的定位/裁边仍需后续样本验收。

隔离浏览器已确认桌面 1440×900、手机 390×844 均能加载 Cesium 并创建地图画布，设置/目录/账号弹窗、自然底图及 2D/3D 切换正常，无未捕获 JS 异常或横向页面溢出。本次加载的 HTTP 请求均来自本地同源地址，没有访问天地图 CDN。当前数据为空会显示“暂无可展示数据”；CTH 色标因后端尚未配置色标目录返回 404，页面显示明确的不可用提示，不能据此声称真实数据可视化已完成。

## 服务器静态打包

```powershell
npm.cmd ci --ignore-scripts
npm.cmd run build
```

输出 `frontend/dist/`，其中包含页面、Cesium 浏览器分发资源和版权说明，可交给 Nginx 静态托管。开发时不复制第二份依赖，只有 build 才生成部署目录。构建只替换带有本项目生成标记的 dist，拒绝删除未知目录或链接。

本次测量：完整 node_modules 约 133.8 MB；发布目录约 25.1 MB，其中 Cesium 浏览器资源约 22.9 MB（均为未压缩文件体积，不是每次页面访问的流量）。`node_modules/` 和 `dist/` 均不提交 Git，只提交精确版本与 package-lock.json。

Nginx 静态配置参考 `nginx-static.example.conf`：版本化 Cesium 地址可长期缓存，HTML 与启动配置需重新验证缓存；API、WebP、色标代理和受控下载仍沿用后端配置。本轮没有操作服务器或部署网站。

官方资源路径说明：[CesiumJS Quickstart](https://cesium.com/learn/cesiumjs-learn/cesiumjs-quickstart/)。

本服务只用于本地开发；生产静态托管/反向代理单独配置。当前不承诺地图数据展示、完整账号下载流程、管理页和 Copilot 已完成。
