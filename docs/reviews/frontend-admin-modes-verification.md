# 实况/预报模式配置验收

日期：2026-10-03；分支 feat/frontend-session。保留此前管理员改动，未提交或推送；真实产品配置未修改。

## 用户路径

管理概览 → 产品资料 → “模式 产品编码” → 选择实况/预报 → 启用状态、延迟阈值 → 保存前确认。
延迟阈值用于判断数据是否更新及时，不是扫描间隔；范围为 10～10080 整数分钟。
一次只保存一个模式，不改变产品状态。草稿/已停用产品配置模式不等于发布或重新上架。

## 输入、行为、输出

| 文件 / 方法 | 输入 | 操作与输出 |
| --- | --- | --- |
| product-admin-client.js / modePayload | 产品快照、模式、enabled、分钟数 | 校验模式与阈值；已发布产品不得关闭最后一种启用模式；仅返回 enabled/staleAfterMinutes |
| saveMode | 原快照和上述设置 | 获取管理员 Session/CSRF、核对 updatedAt，PUT admin/products/{id}/modes/{mode}，返回模式结果 |
| admin-product-modes.js / mountProductModes | 保存后刷新/失权回调 | 绑定独立弹窗，提供 open/reset；回填单模式、确认保存、保留失败输入 |
| admin-products.js | 管理目录 | 各产品增加模式按钮；失权清除模式窗口 |
| admin.html/admin.css | 现有主题 | 实况/预报选择、启用框、阈值输入及反馈；移动端适配 |

没有新第三方依赖或后端修改。延续局部适配技能约束，不改变原产品资料编辑/观测台布局。

## 边界

- 未配置模式不猜默认阈值，显示空输入供管理员明确填写。
- 切换模式或关闭未保存表单需确认；提交中禁止重复点击。
- 成功后重新读取完整产品获取最新 updatedAt，再允许下一次保存；读取失败提示重新打开核对。
- 模式变化不调用 publish/disable，也不发送资料字段、路径或角色。
- 权限和最后启用模式规则由后端再次执行。updatedAt 预检不是原子并发锁，GET/PUT 间仍可能竞争。

## 验证

- npm.cmd test：51 项通过，0 失败/跳过；新增 4 项覆盖阈值边界、最后模式保护、模式 PUT 字段与 CSRF、版本变化和服务端 409 不重放。
- node --check public/flat-20260925/admin-product-modes.js、npm.cmd run build、git diff --check 通过。
- Playwright 真实读取 BT108：实况阈值 90、预报阈值 360，回填正确。
- 隔离浏览器拦截产品写接口：取消确认时 PUT=0；确认修改预报阈值后 PUT=1，请求仅含 enabled/staleAfterMinutes，成功重新回填 120。
- RGB 仅实况启用，尝试关闭时显示“已发布产品必须保留至少一种启用模式”，没有额外 PUT。
- 解除拦截后真实 GET 确认 BT108 预报阈值仍为 360；没有真实配置写入。
- 手机 390×844 截图检查通过，保存在忽略目录 output/playwright/mode-editor-mobile.png。管理员测试 Session 已退出。

## 下一步

产品生命周期：创建草稿、发布、停用、重新发布；操作分别确认，不自动改真实产品。当前模式配置 UI 已接真实接口，用户自行点击确认保存会真正修改本地模式策略。
