# Frontend

基于 2026-10-03 生产现代版迁移，保留观测台视觉与 Cesium，已接入新后端。source-manifest.json 记录的是迁移前来源哈希，不代表适配后文件仍逐字相同。

## 当前入口

- /：观测台，顶部独立数据检索、AI、账号入口。
- /admin.html：管理员概览、产品资料与模式/生命周期、扫描、用户和下载授权审计。
- 经典页/旧英文页不再提供独立入口，页面不链接旧 PHP 管理页。
- Catalog/身份/检索/下载/管理/AI 使用 v1；地图帧列表保留已确认 Legacy 兼容接口。
- Cesium 固定为 npm 1.146.0 的本地浏览器资源，不依赖天地图 CDN。

## 本地开发

需要 Node.js24（最低22）以及已启动的 Java17 后端：

~~~powershell
cd frontend
npm.cmd ci --ignore-scripts
npm.cmd run check
npm.cmd test
npm.cmd run dev
~~~

打开 http://127.0.0.1:15173/ 。默认代理后端 http://127.0.0.1:18080 ；自定义用 DAYU_FRONTEND_PORT/DAYU_BACKEND_URL，仅支持本机HTTP。
静态服务只开放 public 与 Cesium浏览器资源，保留Cookie/CSRF和二进制传输；不开放配置、数据库或NC物理目录。

## 构建与验证

~~~powershell
npm.cmd run build
~~~

输出 dist/，包含 Cesium、版权声明和前端页面；构建只替换本项目标记的目录，拒绝删除未知输出。node_modules/dist/真实数据不提交Git。
CI执行锁文件安装、全部浏览器脚本语法检查、所有 *.test.mjs、构建与Cesium文件检查。通过不等于真实数据/生产部署已经验收。

现有本机证据和分步输入输出见 docs/reviews/frontend-*.md；真实亮温/色标、Session和一次Qwen查询已联调。管理员写操作主要使用隔离响应，不应冒充生产写操作验收。
新电脑必须重新生成配置、初始化产品并扫描样本；本机400d历史窗口、色标配置和数据库不会跟随Git迁移。详见 [迁移与验收指南](../docs/前后端迁移与最终验收指南.md)。

生产静态路由参考 nginx-static.example.conf，需与现有API/受控下载配置合并；不要开放 /netcdf/。本仓库没有自动发布生产环境。
