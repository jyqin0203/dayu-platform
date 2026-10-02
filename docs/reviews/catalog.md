# Catalog 真实实现与验收说明

本分支把产品目录从 skeleton 内存数据替换为 MariaDB 持久化实现。默认（非 skeleton）使用 `CatalogService`；显式 skeleton 仍使用原 Mock。数据库结构沿用 V1—V4。

## 调用关系

```text
CatalogController / CatalogAdminController
  → CatalogQueryService / CatalogAdminService
  → CatalogService（事务和业务规则）
    → CatalogRepository → JdbcCatalogRepository → MariaDB
    → ColorbarVerifier → FileColorbarVerifier → 受控色标目录
```

Catalog 没有新增跨业务模块依赖。Repository 和文件验证端口通过构造器注入；沿用现有 Spring JDBC、事务和 Jackson 依赖。

## 输入、行为和输出

| 方法 | 输入 | 行为 | 输出 |
|---|---|---|---|
| `listPublishedProducts` | 无 | 查 PUBLISHED，按 sortOrder/id 排序 | 摘要列表 |
| `listPublishedProductDetails` | 无 | 单条 JOIN 查询产品与模式，避免 N+1 | 完整详情列表 |
| `findProduct` | ProductCode | 按唯一编码查询；供模块调用时保留全部状态 | Optional 产品详情 |
| `listManagedProducts` | 可选 family/status/code | 参数化筛选和稳定排序 | 摘要列表 |
| `listManagedProductDetails`（新增） | 同上 | 批量读取完整管理资料和模式 | 详情列表 |
| `resolveProductsForAssetFamily` | familyCode | 返回已定义的产品族映射，不以是否发布过滤 | 编码集合 |
| `createProduct` | 创建资料、可信管理员身份 | 校验字段和安全相对路径，创建 DRAFT，写 CREATE 审计 | 持久化详情 |
| `updateProduct` | 产品 ID、可变资料、管理员 | 锁产品，保留编码/族/状态，已发布产品重新检查色标，写 UPDATE 审计 | 修改后详情 |
| `configureProductMode` | 产品 ID、模式、开关、10—10080 整分钟阈值、管理员 | 锁产品后更新模式；已发布产品至少保留一种模式；UPDATE 审计含模式前后快照 | 模式配置 |
| `publishProduct` | 产品 ID、管理员 | DRAFT/DISABLED → PUBLISHED，要求启用模式和必要色标；PUBLISH/REPUBLISH 审计 | 已发布详情 |
| `disableProduct` | 产品 ID、管理员 | 仅允许 PUBLISHED → DISABLED；保留历史与首次发布时间 | 已停用详情 |

产品不存在返回 NOT_FOUND；重复 code/非法状态/关闭最后模式返回 CONFLICT；字段或色标不合法返回 VALIDATION_FAILED。管理命令检查管理员身份；HTTP 管理列表也明确检查 ADMIN。

## 事务与并发

每次写操作先锁定 products 父行，再读取或修改模式。发布、停用、资料修改和模式修改遵循同一锁顺序；两个请求同时关闭不同模式时，后获得锁的请求会看到最新模式，不能把最后一种模式关闭。两个并发发布请求仅一个成功。

产品变更和 product_admin_events 在同一事务中提交。审计写入失败会回滚产品创建/修改和模式变更；不会产生已修改但无审计的记录。REPUBLISH 保留第一次 publishedAt。数据库 DATETIME 使用显式 UTC LocalDateTime 转换，避免 JDBC 默认时区改变业务时间。

## HTTP 修正

- 管理列表改用新增批量详情端口，不再逐产品 findProduct。
- 管理响应按 OpenAPI 输出扁平字段及 `colorbarUrl`，不返回 `colorbarPath`。
- 必填 boolean/int 使用包装类型和 NotNull，避免缺字段被当作 false/0。
- 所有管理员产品 ID 路径在构造领域 ID 前检查正数；0、负数以及非法管理查询 code 返回 422，且不调用业务服务。
- 公开 URL 转换拒绝绝对路径、反斜线、驱动器、`.`/`..`、控制字符和预编码路径，并正确编码空格。

## 文件与配置

新增 `CatalogService`、`CatalogRepository`、`ColorbarVerifier`；新增 `JdbcCatalogRepository`、`FileColorbarVerifier`、`CatalogStorageProperties`；修改 Catalog API/Mock 和两个 Catalog Controller；新增真实数据库测试及管理员 HTTP 测试。

色标配置为 `dayu.catalog.colorbar-root`（环境变量 `DAYU_CATALOG_COLORBAR_ROOT`）。该根对应静态资源根：若数据库相对路径为 `colorbars/cth.png`，验证目标就是根目录下的该路径，公开 URL 为 `/colorbars/cth.png`。必须由部署配置将公开 URL 映射至同一资源。未配置根目录时仍能管理不需要色标的产品，但不能发布需要色标的产品。校验 realPath，符号链接不能逃逸根目录。任何错误都不在 HTTP 中暴露根路径。

RePPIC 产品族统一使用 `REPPIC_PRECIP`；初始化 PLP/PRECIP 产品时 family 应使用该值。Catalog 精确按 family 查找，不猜测旧 `PRECIP` 别名；文件名的规范化由 AssetIndex 负责。

## 验证

执行命令（backend 目录，限制本机测试堆内存）：

```powershell
$env:MAVEN_OPTS='-Xmx256m'
.\mvnw.cmd "-DargLine=-Xmx384m" "-Dtest=CatalogPersistenceIntegrationTest,CatalogModeConfigurationTest,CatalogControllerContractTest,CatalogAdminControllerContractTest" "-Dlogging.level.org.springframework=INFO" test
```

实测结果：21 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS。其中 9 项真实 MariaDB 测试、5 项原模式规则测试、4 项公开 HTTP 测试、3 项管理员 HTTP 测试。

将数据库测试配置标为 `@TestConfiguration`（避免全应用测试误扫描其临时 DataSource）后，再执行 `.\mvnw.cmd "-DargLine=-Xmx384m" "-Dtest=CatalogPersistenceIntegrationTest" test`：9 tests，0 failures/errors/skipped，BUILD SUCCESS。最终数据库源码已重新编译并实测。

主 Agent 验收提出非正数路径 ID 的错误映射问题，修复并新增 1 项边界测试后执行 `.\mvnw.cmd "-DargLine=-Xmx256m" "-Dtest=CatalogControllerContractTest,CatalogAdminControllerContractTest" test`：8 tests，0 failures/errors/skipped，BUILD SUCCESS。本分支最终共有 22 项 Catalog 相关测试（9 数据库 + 5 模式 + 8 HTTP）；最后 HTTP 修复没有修改数据库逻辑。

真实库覆盖：重建应用上下文后仍可读取；重复编码/权限/非法状态；服务入口非法编码与 null 命令不落库；审计失败回滚产品和模式；并发关闭模式仅一个成功；并发发布仅一个审计；必要色标和已发布修改校验；筛选与稳定排序；资料更新保持身份、模式和首次发布时间。

`git diff --check` 通过。敏感字段检查只命中测试用户的 `test-only` 占位密码哈希，没有生产凭据或科学数据。仅运行 Catalog 相关测试；全量及 ArchUnit 由主 Agent 合并验收。

初次测试发现测试装配按实现类取得 JDK 事务代理导致 7 项 setup 错误；改用 class-based 事务代理后重新执行，业务断言没有被删除。首次 MariaDB 初始化还超过默认 120 秒后自动重试；最终容器采用临时 tmpfs 数据目录及 300 秒启动上限，不改生产数据库配置，也不跳过数据库测试。

## 边界

- 本分支没有接 Redis；主 Agent 后续统一接产品目录缓存及写后失效。
- 不迁移生产产品或用户；数据库测试只使用临时 MariaDB 10.6 和合成色标文本。
- Session/CSRF/账号状态实时认证由 Identity 集成提供，此处只消费可信 ActorContext。
- 静态文件部署、真实色标资源和生产访问性能尚需部署集成验证；不声明吞吐指标。
