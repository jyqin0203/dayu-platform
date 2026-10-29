# 候选索引 A/B/A 与入库成本复现

本阶段沿用原快照、JAR、资源限制和benchmark.py协议。所有DDL/写入仅限13307上的dayu_perf与新建dayu_perf_ingest_a/b；原dayu_test:13306和旧fducrias:3306均不在测试目标中。

## 1. HTTP A/B/A

启动步骤同reproduce.md，等待数据库和HTTP ready。每次使用全新的label，脚本会拒绝覆盖已有结果。

```bash
cd /home/ubuntu/dayu-platform
python3 docs/performance/nc-search-20261007/scripts/benchmark.py --label ab-a-before --requests 1000 --warmup-per-case 200
python3 docs/performance/nc-search-20261007/scripts/candidate_index.py add --label ab-b
python3 docs/performance/nc-search-20261007/scripts/benchmark.py --label ab-b-indexed --requests 1000 --warmup-per-case 200
python3 docs/performance/nc-search-20261007/scripts/capture_actual_sql.py --label ab-b-actual-sql-plans
python3 docs/performance/nc-search-20261007/scripts/candidate_index.py drop --label ab-a-return
python3 docs/performance/nc-search-20261007/scripts/benchmark.py --label ab-a-return --requests 1000 --warmup-per-case 200
python3 docs/performance/nc-search-20261007/scripts/capture_actual_sql.py --label ab-a-return-actual-sql-plans
python3 docs/performance/nc-search-20261007/scripts/compare_ab.py
```

上述是本次实际标签；重新执行须改为新标签并在汇总脚本中选取该组名称，不能覆盖本次证据。候选索引增删前后均用CHECKSUM TABLE EXTENDED确认资产与关联数据未变。未加FORCE INDEX、未省COUNT、未改现有索引。

计时阶段不运行入库回放、不执行EXPLAIN，不开general log，不调整CPU配额。初次建库、DDL、计划抓取均不计入查询样本。

## 2. 扫描入库数据库部分的成本

```bash
python3 docs/performance/nc-search-20261007/scripts/prepare_ingest.py
sudo install -m 644 /home/ubuntu/dayu-test/performance/nc-search-20261007/config/dayu-perf-ingest.service /etc/systemd/system/dayu-perf-ingest.service
sudo systemctl daemon-reload
sudo systemctl start --no-block dayu-perf-ingest.service
# 等待oneshot正常退出且console出现Completed replay，再汇总：
python3 docs/performance/nc-search-20261007/scripts/summarize_ingest.py
```

`prepare_ingest.py`只允许创建原先不存在的dayu_perf_ingest_a/b；端口和datadir均有断言。不要为了重跑而删除任何原业务库。已有两个写入试验库时可以复用，但应先归档logs/ingest-results.json和ingest-console.log，再启动同一oneshot；Java程序会删除仅这两个库中的资产/关联数据、重置序号，并按相同快照重放。

Java probe的类和依赖从已记录SHA的同一backend.jar提取，调用**未改动的生产JdbcAssetIndexStore**与Spring事务/Hikari/MariaDB。只枚举仓库外metadata.jsonl，不读取或复制科学文件内容，也不遍历真实数据目录。连接密码通过已有私有EnvironmentFile传入，不打印到日志。

- A：原索引；B：原索引加候选索引。两个库独立，但在同一受限MariaDB进程中串行执行。
- 数据：9270条真实快照元数据、9762条关联；每10条选1条组成927条更新/缺失子集，覆盖WebP与NC。
- 每变体先完整预热1轮不计；正式3轮顺序B/A、A/B、B/A。
- 每次先清理该写入试验库并重置AUTO_INCREMENT（不计时），之后计时新增9270、重复入库9270、元数据变更927、缺失处理927。
- 新增和更新使用真实逐文件事务；无变化分支只更新last_seen_at；缺失WebP删除索引，缺失NC标记MISSING，严格沿用现有仓储实现。
- 每阶段校验资产/关联行数、file_size总和及MISSING数；计时包含每次upsert的Change断言，后置汇总校验不计入。
- 单线程，Hikari最大5；probe CPU100%、CPU2/3、内存512MiB限制，JVM Xmx384MiB；数据库保持原资源限额。

这是数据库入库成本回放，不是文件系统扫描端到端吞吐。性能改动只有索引；未通过修改数据分布/事务大小/并发数制造差异。

## 3. 结束

```bash
sudo systemctl stop dayu-perf-backend.service dayu-perf-db.service
```

oneshot结束后自动退出。保留私有数据库和元数据作证据，不占用运行进程；不自动将实验索引写入正式Flyway迁移或应用到dayu_test。

如果需要复现B方案，必须再次确认目标是隔离dayu_perf后运行candidate_index.py add；当前HTTP试验库在A回测后已恢复原索引。两个写入试验库分别保留A/B索引状态。
