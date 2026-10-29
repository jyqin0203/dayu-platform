# 复现命令与安全边界

这些命令针对本次服务器及明确的隔离路径；不要改成旧库、不要在运行的业务库上加索引或清空表。使用现有已校验的运行JAR、MariaDB二进制和JDK17，不安装新中间件。源码生成脚本的行为和输入输出均见docstring及README。

## 已有快照复跑（保持数据相同）

```bash
cd /home/ubuntu/dayu-platform
sudo systemctl start dayu-perf-db.service
# 等待下述只读检查返回13307和独立datadir，再启动后端
mariadb --defaults-file=/home/ubuntu/dayu-test/performance/nc-search-20261007/config/client.cnf -e 'SELECT @@port,@@datadir;'
sudo systemctl start dayu-perf-backend.service
# 等待后端GET接口返回200，不把冷启动失败计入预热后结果
curl --noproxy '*' -f 'http://127.0.0.1:18081/api/v1/scientific-assets?productCode=PRECIP&dataMode=REALTIME&from=2026-09-02T06:00:00Z&to=2026-09-08T06:00:00Z'
python3 docs/performance/nc-search-20261007/scripts/benchmark.py --label repeat-baseline --requests 1000 --warmup-per-case 200
python3 docs/performance/nc-search-20261007/scripts/capture_actual_sql.py --label repeat-actual-sql-plans
sudo systemctl stop dayu-perf-backend.service dayu-perf-db.service
```

`--label`必须用新名字，以保留之前所有轮次。脚本guard校验独立数据库13307，不接受任意目标URL。

`benchmark.py`读取仓库外快照作为独立预期结果，返回状态、延迟和内容比较写入results；不写业务表。`capture_actual_sql.py`只在隔离库短暂启用general log并用finally关闭，计时阶段必须保持general log关闭。SQL执行计划的ANALYZE会真实执行SELECT，但不修改业务行。

## 从新快照重新搭建（数据会不同，不可当作相同数据A/B）

先确认目标路径尚不存在、端口13307/18081未占用。`prepare_isolate.py`只适用于新目录，并拒绝覆盖已有凭据；不要直接重跑在现有环境上。若要另开实验，请审查脚本中的固定路径/端口及保护断言。

```bash
# 创建一次性隔离目录及config/db/run/logs/snapshot子目录（0700）后：
python3 docs/performance/nc-search-20261007/scripts/capture_snapshot.py \
  --source-client /home/ubuntu/dayu-test/config/db-client.cnf \
  --private-dir /home/ubuntu/dayu-test/performance/nc-search-20261007 \
  --results-dir docs/performance/nc-search-20261007/results
python3 docs/performance/nc-search-20261007/scripts/prepare_isolate.py
# 安装仅这两个新unit，不修改现有服务
sudo install -m 644 /home/ubuntu/dayu-test/performance/nc-search-20261007/config/dayu-perf-db.service /etc/systemd/system/dayu-perf-db.service
sudo install -m 644 /home/ubuntu/dayu-test/performance/nc-search-20261007/config/dayu-perf-backend.service /etc/systemd/system/dayu-perf-backend.service
sudo systemctl daemon-reload
sudo systemctl start dayu-perf-db.service
# 等待socket ready后再导入；首次过早导入曾失败，但目标库尚不存在，等待后重试成功
python3 docs/performance/nc-search-20261007/scripts/import_snapshot.py
sudo systemctl start dayu-perf-backend.service
python3 docs/performance/nc-search-20261007/scripts/make_queries.py
python3 docs/performance/nc-search-20261007/scripts/explain.py
# 后端ready后进行三轮；均为未优化实现
python3 docs/performance/nc-search-20261007/scripts/benchmark.py --label baseline --requests 1000 --warmup-per-case 200
python3 docs/performance/nc-search-20261007/scripts/capture_actual_sql.py
python3 docs/performance/nc-search-20261007/scripts/summarize.py
sudo systemctl stop dayu-perf-backend.service dayu-perf-db.service
```

快照采用MariaDB只读一致性事务；只读产品/模式/资产/关联/Flyway历史，产品操作者ID置NULL，不复制账号。文件核对是已索引路径的stat，不遍历额外目录。snapshot SHA可关联同一次试验，索引库动态更新会使新快照不同。

## 结果文件

- `inventory.json`、`environment.json`：数据口径、硬件和资源限额、版本hash。
- `baseline-requests.jsonl.gz`：54,000条正式逐请求记录，gzip无损压缩；`pilot-requests.jsonl.gz`是10,800条预试验。
- `baseline-summary.json/.csv`：54组逐轮摘要，含cgroup CPU节流增量。
- `baseline-sql-digests.json`：实际HTTP请求的SQL次数、累计时间、扫描/排序行数差值。
- `list-count-costs.json`：列表/COUNT分拆，SQL耗时来自server计数而非启动CLI耗时。
- `plans.json`、`actual-sql-plans.json`：初始与负载后计划，原始JSON及保留重复键的解析结构。
- `expected-signatures.json`：各场景预期total、顺序ID及响应hash，不包含真实科学文件内容。

解压查看示例：`gzip -cd docs/performance/nc-search-20261007/results/baseline-requests.jsonl.gz | head`。

复跑会生成未压缩JSONL；保留后可用Python gzip无损压缩。原始时间和错误记录不得删改来改善指标。没有后测时不要生成“提升比例”。

停止时临时Java进程以143响应SIGTERM；临时unit未配置SuccessExitStatus=143，systemd将此次停止标成failed，但MainPID为0、端口关闭。见completion.json。这是关闭状态标记，不是负载请求失败；恢复时systemctl start仍可启动。
