# 现代页面注册登录闭环

日期：2026-10-03。基于已合并 PR #12（e6e3965），分支 `feat/frontend-session`。本轮未提交、未部署。

## 本轮范围

替换原账号弹窗中指向经典版的链接，提供注册、登录、当前身份和退出。
使用 surgical-web-fusion 的局部改动原则，保留地图、导航位置、原弹窗宽度及暗色/青色视觉；新 CSS 全部限定在 #account 下。
不实现科学数据搜索下载、管理员和 Copilot 页面，不改变后端身份模块。

## 输入、行为、输出

| 文件 / 能力 | 输入 | 行为 | 输出 |
| --- | --- | --- | --- |
| session-client.js / current | 浏览器自动携带 Cookie | GET /api/v1/session，no-store | 服务器当前身份、CSRF |
| login | 邮箱、密码 | 先取当前 Session 的 CSRF，再 POST /api/v1/session | 后端验证后的身份 |
| register | 邮箱、密码、工作单位 | 先取 CSRF，再 POST /api/v1/users | 后端落库并自动登录后的身份 |
| logout | 当前 Session | 取 CSRF 后 DELETE /api/v1/session，接受 204 空响应 | 匿名状态 |
| account.js | 表单、用户点击、客户端响应 | 原生 dialog 切换登录/注册；提交期间禁用重复操作；用 textContent 显示用户资料；清除密码输入 | 登录状态和成功/错误提示 |
| account.css | 现有主题变量 | 仅调整账号表单内部布局、输入框与按钮 | 桌面/手机可用表单 |

两个 HTML 入口均加载 account.js/account.css，移除旧账号跳转；app.js 不再接管账号按钮，账号功能不依赖地图初始化成功。
没有新增第三方运行时依赖。后端仍是身份和权限事实来源，前端按钮不能代替鉴权。

## 安全及错误处理

- Cookie 使用 same-origin，不手工创建 Session，不把密码/令牌放入 localStorage/sessionStorage。
- 每次写入前读取当前 CSRF，兼容 Session 轮换；写入失败不自动重放注册或登录。
- 请求超时 15 秒；401/403/409/422/429 和服务错误使用固定安全提示，不展示原始后端异常。
- 注册长度限制与后端一致（密码 10～128、单位 2～255），后端再次验证。
- 关闭弹窗、提交完成和模式切换清空密码。注册成功后自动登录由后端实现，非前端假登录。

## 验证

- npm.cmd test：21 项通过，0 失败、0 跳过；新增 5 项覆盖 CSRF 顺序、同源 Cookie、登录/注册请求、204 退出、错误状态、无令牌、网络失败以及不重放。
- node --check public/flat-20260925/account.js：通过。
- npm.cmd run build、git diff --check：通过。
- Playwright + 真实本地 Java/MariaDB：注册成功 → 刷新保持登录 → 退出；另一次注册后验证错误密码提示 → 正确密码登录 → 密码输入已清空 → 退出。
- 本地新增 2 个专用普通测试账号（ui-check-* / ui-login-* @dayu.test），未删除；没有改管理员或生产数据。随机测试密码不输出、不保存，测试会话已退出。
- 检查 1440×900 和 390×844 截图，弹窗无横向溢出；截图在本地忽略目录 output/playwright/account-*.png。
- 控制台一条 HTTP 401 是错误密码负向测试的预期结果，不是未捕获 JavaScript 异常。

## 限制与下一步

新增单元测试使用注入 fetch；真实浏览器验收另列，不混称。未重复运行后端 185 项测试（后端无修改）。
不增加邮箱验证、找回密码、第三方登录；这些并非当前契约。
下一闭环：科学数据检索表单与分页结果，明确时间时区；之后再接用途登记与授权下载。
