# Download 实现与验收说明

## 本轮结果与边界

实现真实下载授权、MariaDB 审计与快照、持久授权取件、管理员审计查询与统计，以及本地流式/Nginx 传输适配。Download 只通过 AssetIndex API 读取资产，不依赖 Catalog；按照用户确认的决定，产品停用本身不撤销仍可用 NC 的下载权限。

数据库沿用 V1—V4，不新增表或修改历史迁移。生产代码只操作 `download_events` 与 `download_event_products`；测试为外键建立合成用户/资产夹具，不代表生产代码跨模块查表。

## 调用链

```text
POST /api/v1/downloads
→ DownloadService.authorizeDownload
→ AssetIndex.DownloadAssetLookup
→ DownloadFiles.verify
→ DownloadAuditWriter.record（独立事务提交事件与全部产品快照）
→ DownloadGrant（含 expiresAt）

GET /api/v1/downloads/{eventId}/content
→ DownloadContentService.prepareContent
→ 读持久事件，检查本人/状态/到期时间
→ AssetIndex 再查资产，重验真实文件与大小/修改时间
→ LOCAL：打开 InputStream → Spring 逐块发送并关闭
→ NGINX：安全内部 URI → X-Accel-Redirect → Nginx 发送
```

## 方法输入、行为、输出

| API | 输入 | 行为与输出 |
|---|---|---|
| `authorizeDownload` | assetId、用途、可信 ActorContext、客户端 IP/UA | 身份及用途校验；确认 NETCDF/AVAILABLE；安全路径与文件校验；提交审计后返回 DownloadGrant |
| `getAuthorizedGrant` | eventId、当前身份 | 从数据库恢复本人未过期的 AUTHORIZED 事件，重验资产与文件，返回内部授权 |
| `prepareContent`（新增） | 同上 | 同一业务校验后按模式返回 DownloadContent；local 流已打开，nginx 流为空 |
| `searchDownloadAudits` | 时间、产品、用户、单位、状态、分页 | 按 requestedAt/id 倒序稳定分页，保留事件发生时快照，不返回 IP/UA/路径 |
| `getDownloadStatistics` | 时间、可选产品 | 申请/授权/拒绝数量、授权唯一资产/用户/机构、授权按产品/机构/用户聚合 |
| `DownloadHttpResponse.from` | DownloadContent | v1/Legacy 可共用；构建 UTF-8 文件名、安全头与真实流响应；转换失败主动关闭已打开流 |

`DownloadGrant` 追加 `expiresAt`，保留原六参数构造器给 skeleton；真实实现使用配置 TTL。`DownloadContent.stream` 的所有权转交 HTTP 响应转换器，Spring 正常发送和发送异常时都会关闭流。Nginx 模式不打开 local 流。

## 审计与一致性

- 授权审计在独立 `REQUIRES_NEW` 事务里写事件及全部产品快照，事务成功后才能返回授权。
- 已认证且资产存在的文件缺失、不可用或路径安全失败，会提交 DENIED；随后抛出的业务错误不会回滚该拒绝记录。
- 未登录、用途不合法、assetId 不存在不伪造业务外键或下载事件。
- 授权后取件失败不将过去确实发生的授权改成 DENIED，也不声称 DELIVERED。
- DENIED 的 `authorizedAt` 为 null；Operations 和 OpenAPI 需按可空字段处理。
- 产品筛选使用 EXISTS；一个 NC 包含 PLP/PRECIP 不使下载事件总数翻倍。按产品聚合允许同一事件同时计入两个产品；总授权数仍只计一次。
- uniqueUsers/uniqueOrganizations 来自同一授权口径的 SQL COUNT DISTINCT，不依赖分组 Map 的长度，未来分组返回 Top N 时总数仍可保持准确。DownloadStatistics 保留旧七参数构造器用于 skeleton。
- 查询时间按 requestedAt 的闭区间 `[from,to]` 筛选，排序为 requestedAt/id 降序。统计各分组仅计已授权事件；若未来增加传输回写，DELIVERED/INTERRUPTED 仍计入已授权。

## 配置与安全

```yaml
dayu:
  indexing:
    roots:
      netcdf-data: /srv/dayu/netcdf
  download:
    grant-ttl: 5m
    transfer-mode: LOCAL # 生产经已配置 Nginx 时设 NGINX
    internal-prefix: /internal-netcdf/
    storage-aliases: []
```

默认直接复用 `dayu.indexing.roots.netcdf-data`。也可设置 `dayu.download.netcdf-root`；同时配置时两者 realPath 必须相同，防止索引与下载指向不同目录。只接受 `netcdf-data`，旧 `netcdf-science` 必须显式加入 storage-aliases；不会忽略 storageKey。

TTL 取正值且至多一天。现有表未保存独立到期时间，因此重启调整 TTL 会按原 authorizedAt 和新 TTL 重新判断存量授权，符合此前“从 authorizedAt 计算期限”的决定。

路径校验拒绝绝对路径、驱动器、反斜线、`.`/`..`、控制字符、空段和预编码路径；realPath 必须留在配置根内。Nginx URI 按路径编码，禁止响应头注入。文件名采用 RFC UTF-8 Content-Disposition。

取件时既比较当前资产大小和授权快照，也比较磁盘大小；授权后 mtime 更新会要求重新申请，能发现同大小的常规文件重写。它不是内容密码学校验：刻意保持大小并回拨 mtime 的改写不能被保证识别；上游仍应以原子重命名发布完整文件，NC 存储应只允许可信生产程序写入。Nginx 与 Java 之间也存在文件打开的时间窗口，不能声称此版本支持任意恶意磁盘写入下的完整性保证。

本地使用文件通道 InputStream，不把整个 NC 载入内存。文件生成/权限边界必须由部署环境维护。Nginx 示例在 `infra/nginx-download.conf`，包括 internal、alias、禁止符号链接以及阻止旧静态 NC 路由。

## 主要文件

- API：DownloadContentService、DownloadContent、DownloadGrant（追加 expiresAt）。
- 应用：DownloadService、DownloadAuditWriter、DownloadRepository/DownloadFiles/DownloadPolicy 端口、StoredDownload。
- 基础设施：JdbcDownloadRepository、LocalDownloadFiles、DownloadSettings、DownloadConfiguration。
- HTTP：DownloadController、可复用 DownloadHttpResponse。
- skeleton：MockDownload 实现新的传输端口，明确只演示 Nginx 头，不提供真实文件。
- 测试：DownloadPersistenceIntegrationTest、DownloadControllerContractTest。
- 传输器测试：NginxDownloadTransferIntegrationTest，真实 nginx:1.26-alpine 容器；同容器 81 端口仅模拟上游授权响应，80 端口验证 X-Accel 传输及 internal 直连隔离，不冒充 Java 身份全链路。

## 验证

测试使用临时 MariaDB 10.6、合成文件、固定可变 Clock 与真实 nginx:1.26-alpine。Maven 设置 `MAVEN_OPTS=-Xmx256m`；数据库/HTTP fork 使用 384m，Nginx fork 使用 256m。没有运行全量工程测试。

| 实际命令（backend 目录） | 结果 |
|---|---|
| `.\mvnw.cmd "-DargLine=-Xmx384m" "-Dtest=DownloadPersistenceIntegrationTest,DownloadControllerContractTest" test` | 17 项，真实 DB/文件 10 项全过，HTTP 有 1 项测试装配失败，0 errors/skipped |
| `.\mvnw.cmd "-DargLine=-Xmx384m" "-Dtest=DownloadPersistenceIntegrationTest#statisticsAndPaginationDoNotDoubleCountMultiProductFiles,DownloadControllerContractTest" test` | 修复后 8 项全过，0 failures/errors/skipped，BUILD SUCCESS；包含新增 DISTINCT 统计真实 SQL |
| `.\mvnw.cmd "-DargLine=-Xmx256m" "-Dtest=NginxDownloadTransferIntegrationTest" test` | 1 项通过，0 failures/errors/skipped，BUILD SUCCESS |

本轮共 18 个独立测试用例，按变更范围分批验证，没有声称一次执行了全部 18 项。真实 Nginx 验证了 byte-for-byte 内容、Content-Disposition、X-Accel 头不透给客户端、internal 直连 404、旧 NC 路由 404 和上游拒绝 403。Nginx 上游只模拟授权响应；Java 用户授权由真实 DB 测试单独验证。

`git diff --check` 通过。敏感信息检查仅命中测试夹具 `password_hash='fixture'`，没有生产凭据、真实 NC/WebP/HDF 或 `.env`。

首次 17 项执行中，真实数据库与文件 10 项全部通过（包括 Windows junction 逃逸，0 跳过）；HTTP 7 项有 1 项因 standalone MockMvc 的 Jackson 默认时间戳序列化失败。已将测试消息转换器配置为与 Spring Boot 一致的 ISO 时间字符串，保留原日期断言，随后针对重跑。主 Agent 追加显式 DISTINCT 用户/机构统计后，也需重新跑相应真实 SQL 用例。

## 暂未实现

不把 AUTHORIZED 描述成客户端已保存。传输日志回写、断点续传、批量打包和生产流量性能验证不在本轮内。管理员权限与账号立即禁用由 Identity 接入层保障，Download 消费可信当前身份。Redis 不缓存用途、审计或 NC 内容。
