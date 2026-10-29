# 复现步骤

以下路径是本次服务器环境。全部写入实验库/工作区；科学数据根目录只读。新机器需对应调整路径和 CPU 编号。本次没有安装新依赖。

## 1. 独立数据库

```bash
cd /home/ubuntu/dayu-platform
bash docs/performance/index-update-20261008/scripts/start-isolated-db.sh
```

此命令前台运行，只绑定 `/tmp/dayu-index-update-20261008/db.sock`。在另一终端完成后续步骤。

首次空环境才执行（现有保留环境跳过）：

```bash
python3 docs/performance/index-update-20261008/scripts/prepare-isolated.py
```

准备脚本按 V1–V5 DDL 建立 `dayu_scan_a` / `dayu_scan_b`，并从已有私有目录快照导入 17 个产品及模式策略（管理员引用清空，无用户数据）。另建空的 `dayu_scan_correctness`。随机密码保存在实验目录 0600 文件，不打印。检测到既有实验凭据或 schema 就拒绝覆盖。

当前实验还依赖此前保留的目录快照 `/home/ubuntu/dayu-prod/performance/nc-search-20261007/snapshot/metadata.jsonl`，用于目录产品元数据，**不是本轮性能资产输入**。本轮资产输入为实验目录的 `frozen-files.jsonl`。

## 2. 编译基线与候选

```bash
bash docs/performance/index-update-20261008/scripts/compile-probes.sh
```

从原部署 jar 提取基线类和原有依赖，校验完整 SHA256。候选仅覆盖三个 AssetIndex 类，其他依赖相同。编译器为现有 JDK 17；没有复制配置、读取 `.env` 或运行应用启动入口。

## 3. 固定真实元数据回放

```bash
bash docs/performance/index-update-20261008/scripts/run-benchmark.sh a 1
bash docs/performance/index-update-20261008/scripts/run-benchmark.sh b 1
bash docs/performance/index-update-20261008/scripts/run-benchmark.sh b 2
bash docs/performance/index-update-20261008/scripts/run-benchmark.sh a 2
bash docs/performance/index-update-20261008/scripts/run-benchmark.sh a 3
bash docs/performance/index-update-20261008/scripts/run-benchmark.sh b 3
python3 docs/performance/index-update-20261008/scripts/summarize.py
```

每次只清空对应实验 schema 的索引/扫描记录，保留产品目录，随后初始化相同的留出数据集。测量前清空 SQL/事务计数。Java 连接私有 socket 后再次核对 `@@datadir` 与 `DATABASE()`，不接受任意 URL。

`ScanBenchmark` 首次会读取原定 WebP/NC 两根目录，保存路径/大小/mtime 到私有快照；后续六次均读取同一文件，不重新采样。固定快照 SHA256 见 environment.json。新数据含发布标记控制批次时该探针会拒绝继续，不能省略 marker 凭空回放完整性；本轮真实快照未含此类批次，它们通过专门正确性测试覆盖。

每次报告的 `sqlExecutions` 是 JDBC execute 调用次数，不含 commit/驱动协议命令；commit 次数单列。没有 JDBC batchExecute 混淆“调用一次但执行多条”的情况。`storeMs` 包含端口内部查询、事务和映射，`jdbcExecuteMs` 不含 commit 等处理。

删除私有快照后，仅凭仓库内聚合报告无法恢复原始清单；源目录会变化。要精确复现实测，需保留快照及原 jar，不能重新采样后声称还是同一批文件。

## 4. 完整目录扫描交叉验证

在固定回放和真实库正确性测试结束后，实际执行：

```bash
bash docs/performance/index-update-20261008/scripts/run-live.sh a
bash docs/performance/index-update-20261008/scripts/run-live.sh b
python3 docs/performance/index-update-20261008/scripts/summarize-live.py
```

直接使用原 `LocalFileInventory` 访问科学源目录，DB 仍为独立 A/B 库。每版先预热一次（round=0，不用于最终统计），再三轮测量。此过程会使实验库跟随当前目录状态更新，因此最终 A/B 库内容不再等同于最初 9,143 文件快照；固定快照和原始结果不覆盖。

探针输出每轮目录元数据与业务状态指纹；汇总脚本要求各轮文件集合/元数据一致，且新增、更新、删除、缺失和错误均为零。若上游变化，必须另取稳定窗口或只报告固定快照回放，不能忽略不一致继续算收益。

文件系统独立分解探针另见 `run-filesystem.sh`，首次三轮原始结果在 `results/filesystem.jsonl`。

## 5. 编译、单元/架构验证

```bash
cd /home/ubuntu/dayu-platform/backend
JAVA_HOME=/home/ubuntu/dayu-prod/tools/jdk17 \
  /home/ubuntu/dayu-prod/cache/maven/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn \
  -o -Dmaven.repo.local=/home/ubuntu/dayu-prod/cache/m2 \
  -Dtest=AssetFilenameParserTest,LocalFileInventoryTest,ReppicReleaseInspectorTest,ArchitectureTest test
```

本次实际执行时增加 nice 15 和 CPU 4 亲和性，以限制编译/验证干扰。结果 17 项通过。没有运行全项目所有集成套件。

## 6. 真实库断言（不依赖 Docker）

生成已有测试依赖路径：

```bash
cd /home/ubuntu/dayu-platform
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as E
p=next(Path('backend/target/surefire-reports').glob('TEST-*.xml'))
r=E.parse(p).getroot()
cp=next(n.attrib['value'] for n in r.find('properties') if n.attrib['name']=='java.class.path')
Path('/tmp/dayu-index-update-20261008/test-classpath').write_text(cp)
PY
```

编译和运行适配器（已通过上一节编译的原测试类），本次 14 个断言用例均通过：

```bash
work=/tmp/dayu-index-update-20261008
jdk=/home/ubuntu/dayu-prod/tools/jdk17
"$jdk/bin/javac" -cp "$(cat "$work/test-classpath")" -d "$work/probe-classes" \
  docs/performance/index-update-20261008/scripts/RunIsolatedAssetTests.java
nice -n 15 ionice -c 3 taskset -c 3 "$jdk/bin/java" -Xmx256m -XX:ActiveProcessorCount=1 \
  -cp "$work/probe-classes:$(cat "$work/test-classpath"):/home/ubuntu/dayu-prod/cache/m2/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar:/home/ubuntu/dayu-prod/cache/m2/net/java/dev/jna/jna-platform/5.17.0/jna-platform-5.17.0.jar" \
  RunIsolatedAssetTests > "$work/correctness.log" 2>&1
```

Flyway 在独立 correctness schema 初始化迁移；合成文件保留在实验根目录。此方法是独立适配器执行测试方法及生命周期，不声称运行了 Testcontainers 扩展。数据库正确性测试不要与性能测量同时执行。

## 7. 停止与清理

停止本次私有实验数据库，不删除其内容：

```bash
mariadb-admin --no-defaults --socket=/tmp/dayu-index-update-20261008/db.sock -uroot shutdown
```

**删除数据库和实验文件必须等用户确认。** 建议至少保留私有元数据快照、基线产物和原日志用于复现；仓库里的脱敏报告和脚本长期保留。正式服务未应用候选变更，上线属于另一个发布动作。
