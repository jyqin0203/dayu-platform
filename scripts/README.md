# Scripts

开发辅助脚本目录。

Windows 首次启动顺序：`setup-local.ps1` → `build-local.ps1` → `start-local.ps1` → `initialize-local-products.ps1 -BootstrapAdmin`。停止用 `stop-local.ps1`（加 `-Database` 同时停止数据库、保留卷）。完整步骤见 [新电脑首次启动指南](../docs/新电脑首次启动指南.md)。

`setup-local.ps1` 检查 JDK/Docker，生成缺失的本地配置和随机密码，不覆盖现有文件；`build-local.ps1` 使用 Maven wrapper 打包，明确不执行测试；`local-common.ps1` 提供配置读取、JDK 校验和实例隔离的公共辅助函数，不执行配置文件中的代码。

`start-local.ps1`：用 `.env.local-db` 启动本地 MariaDB，再启动已构建的后端 JAR。读取 `.env.local` 的目录与端口，以及 `.env` 中的 Qwen 白名单配置；不运行测试、不扫描文件，启动不调用模型。可加 `-DisableCopilot` 临时强制关闭模型。详见 [资源与模型配置](../docs/后端配置与图片资源接入.md)。

`local-runtime.ps1`：将已校验的目录和模型配置映射为 Spring 属性，密钥独立走进程环境。`check-local-runtime.ps1`：使用合成配置做小范围检查，不连接数据库或模型。

`initialize-local-products.ps1`：使用 `.env.local-admin` 登录本地后台，依据 `infra/local-products.json` 创建目录、配置模式、发布不缺必要色标的产品；已有产品跳过。`-BootstrapAdmin` 仅允许在空用户库中注册并初始化首个本地管理员。该脚本只连接固定的本地地址，不可用于生产初始化。

脚本只能处理本地测试数据或显式指定的安全路径，不得默认连接生产数据库、删除生产文件或运行课题组气象算法。
