# 大禹系统 Legacy API 兼容契约

> 状态：评审确认版
> 日期：2026-10-02
> 范围：旧网页调用的 PHP 风格 URL。迁移到 Java 后保留必要 URL 和旧 JSON 形状，但不保留不安全目录扫描和冲突的管理员写模型。

## 1. 总体映射

| 旧端点 | 决定 | 新模块能力 |
| --- | --- | --- |
| GET /api/products.php | 保留适配 | Catalog |
| GET /api/files.php | 保留适配 | Discovery |
| GET /api/fcst_latest.php | 保留适配 | Discovery |
| GET /api/search.php | 保留适配 | Discovery |
| GET/POST /api/auth.php | 保留适配 | Identity |
| POST /api/download.php | 保留适配 | AssetIndex + Download |
| /api/admin_products.php | 返回 410 Gone | 使用 /api/v1/admin/products |
| /api/admin.php | 返回 410 Gone | 使用 /api/v1/admin/* |

common.php 和 product_catalog.php 是旧 PHP 内部文件，不是 HTTP 契约。

Legacy Controller 只能调用模块公开 API，不得直接访问数据库、使用 scandir/glob、接受绝对路径或暴露 NC 存储路径。错误响应不得包含 Java 堆栈、SQL、绝对路径或凭据。

## 2. GET /api/products.php

无需登录。Catalog 中每个 PUBLISHED 产品按启用模式展开：

~~~text
BT855 + REALTIME → product_id=BT855, category=realtime
BT855 + FORECAST → product_id=FCST_BT855, category=forecast
~~~

成功响应：

~~~json
{
  "ok": true,
  "products": [{
    "product_id": "FCST_BT855",
    "path_id": "BT855",
    "category": "forecast",
    "name_zh": "8.55μm亮温",
    "name_en": "8.55μm Brightness Temperature",
    "title_zh": "预报 - 8.55μm亮温",
    "title_en": "Forecast - 8.55μm Brightness Temperature",
    "sort_order": 40,
    "colorbar_required": false,
    "status": "active"
  }]
}
~~~

path_id、FCST_ 编码和标题由适配器生成。旧 PHP 曾公开部分 forecast + draft，新实现不保留该行为。

## 3. GET /api/files.php

| 参数 | 规则 |
| --- | --- |
| path | 必须匹配配置的 WebP 实况或预报目录模式 |
| number | 默认 48，范围 1～200 |

允许解析的语义路径：

~~~text
WebP/<配置DPI目录>/realtime/<产品>
WebP/<配置DPI目录>/forecast/<12位起报时间>/<产品>
~~~

适配器将路径转换成 productCode/dataMode/cycleTime 后调用 Discovery。结果按有效时间正序，截取最新 N 帧。

预报帧可以晚于当前时刻；不能用“截至现在”的实况窗口过滤未来预报。预览保留窗口与 `dayu.preview.retention` 一致。

~~~json
{"files":["WebP/WebP_V2_Dpi500_4KM/realtime/BT855/example.webp"]}
~~~

非法路径或无数据均返回空 files 数组，不尝试访问任意目录。

## 4. GET /api/fcst_latest.php

参数 path 必须是配置的 WebP 预报根；product 可带旧 FCST_ 前缀。适配器查询真实 AVAILABLE WebP 的预报批次：

~~~json
{"latest":"202609280200"}
~~~

无数据或参数无效返回空字符串。目录存在但没有资产不能成为最新批次。

## 5. GET /api/search.php

| 参数 | 规则 |
| --- | --- |
| type | 仅接受 multi |
| dir | 只接受已知 WebP/NC 语义路径 |
| start/end | 12 位 UTC yyyyMMddHHmm |
| product | 可选，允许旧 FCST_ 前缀 |

WebP 请求调用预览查询，NC 请求调用科学资产查询；dir 包含预报批次时限定该 cycleTime。

成功响应保留三个平行数组并按时间倒序：

~~~json
{
  "files": ["netcdf/forecast/202609020600/example.nc"],
  "sizes": ["19.41 MB"],
  "times": ["2026-09-02 08:00"]
}
~~~

times 无时区后缀但明确表示 UTC。非法目录、时间或产品返回三个空数组；type 不是 multi 时返回旧式 422。NC 的 files 字段只供旧下载表单回传，Nginx 仍禁止直接访问 /netcdf/。

实现通过内部分页/时间分段收集结果，共享同一个 NC 的产品按 assetId 去重。文件大小使用真实字节数除以 1024²，显示两位小数 MB。为避免旧版无分页响应无限占用资源，`dayu.legacy.max-search-results` 默认 2000；超过上限明确返回旧式 422，提示缩小范围，不静默截断。这个上限不影响 `/api/v1` 的分页检索。

另有单请求内部分页/分段调用预算 `dayu.legacy.max-internal-queries`，默认 100；即使极大时间范围只产生空结果也受此预算保护，超限返回 422，不继续无界查询。

## 6. GET/POST /api/auth.php

| 旧调用 | 新能力 |
| --- | --- |
| GET ?action=status | 查询当前 Session |
| POST ?action=register | 注册，数据库事务提交后建立 Session |
| POST ?action=login | 登录 |
| POST ?action=logout | 退出 |

接受旧 JSON 或表单字段，并校验与当前 Session 匹配的 csrf_token 或 X-CSRF-TOKEN。

~~~json
{
  "ok": true,
  "authenticated": true,
  "user": {
    "id": 101,
    "email": "user@example.com",
    "organization": "复旦大学",
    "role": "user"
  },
  "csrf_token": "..."
}
~~~

角色转换为旧版小写。登录和注册成功后返回最新 Token。退出保持旧行为：返回 200、匿名身份和新的匿名 Session Token，而不是 /api/v1 的 204。所有响应使用 Cache-Control: no-store。

## 7. POST /api/download.php

旧表单字段：

~~~text
csrf_token
file_path
purpose
~~~

处理流程：

~~~text
规范化相对路径
→ 限制在 netcdf 逻辑存储空间
→ AssetIndex 通过 storageKey + relativePath 查找 assetId
→ Download 按统一规则授权并写审计
→ 统一内容服务再次校验授权并返回文件（LOCAL 流式；NGINX 使用 X-Accel-Redirect）
~~~

该接口不使用 /api/v1 的两阶段响应，以保持旧网页“新标签页直接下载”的行为。路径不存在、不是 NC、资产不可用或发生路径逃逸时拒绝；用途仍为 10～2000 字符。

AssetIndex 需要提供仅供服务器内部 Legacy Adapter 使用的路径查找端口，该端口不暴露为 /api/v1。

## 8. 退役管理员接口

以下接口统一返回：

~~~http
410 Gone
Content-Type: application/json
~~~

~~~json
{
  "ok": false,
  "message": "This legacy administrator API has been retired. Use the new administrator interface."
}
~~~

### /api/admin_products.php

旧模型将 BT855 与 FCST_BT855 作为独立产品，新模型将其建模为一个产品的两种模式。旧接口对单行执行创建、更新和状态修改无法无歧义映射，因此不保留写兼容。

### /api/admin.php

旧接口在同步 GET 中混合目录扫描、产品健康和“完成下载”统计。新系统采用异步扫描，并严格区分授权与传输完成，因此不伪造旧语义。

生产切换前必须提供新管理员页面；旧 /admin.html 应跳转到新页面。

## 9. 路由与测试

生产 Nginx 只把六个保留 URL 转发给 Java Legacy Controller；两个管理员 URL 返回 Java 的 410。每个保留端点至少验证：

- 旧参数可解析，字段名、空结果和排序兼容；
- 非法目录不会触发文件系统访问；
- Session/CSRF 行为兼容；
- NC 无法绕过 Download 直接访问；
- Legacy Controller 不依赖业务模块的 application、domain 或 infrastructure 包。
