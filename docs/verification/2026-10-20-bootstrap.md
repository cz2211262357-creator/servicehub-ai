# 重启前验证记录（阶段检查点）

本记录仅覆盖基础工程及 Task 2 的准备工作，不代表项目全部完成。

## 环境与命令结果

| 检查 | 结果 |
| --- | --- |
| WSL 3.0.3 安装 | 退出码 0；Windows 提示重启后生效 |
| Docker Desktop 4.96.0 安装 | 退出码 0；Docker Inc 签名 Valid |
| Docker CLI | 29.9.1，可执行 |
| docker compose config --quiet | 通过；默认启用的依赖为 mysql、redis |
| uv lock --check | 通过 |
| uv run ruff check backend/src | 通过 |
| uv run python -m compileall -q backend/src backend/migrations | 通过 |
| uv run alembic -c backend/alembic.ini upgrade head --sql | 生成初始 MySQL SQL 成功，19 张表；未连接数据库 |
| uv run pytest -q | 14 passed，1 failed，2 errors，14.52 秒 |

3 项未通过的集成检查为：

- test_initial_migration_is_repeatable_and_has_tenant_foreign_keys：MySQL 127.0.0.1:3306 连接被拒绝，迁移无法执行。
- test_cross_tenant_reference_is_rejected：同一迁移前置条件失败。
- test_redis_search_and_streams_commands_use_real_server：Redis 127.0.0.1:6379 连接被拒绝。

Docker 后端日志显示 Virtual Machine Platform not enabled；WSL 安装日志明确提示重新启动系统前更改不生效。因此当前不能验证真实数据库约束、Redis Search 或并发。没有使用 SQLite 或 fakeredis 替代这些结果。

## 已完成与待完成

Task 1 已完成并提交。Task 2 的模型、迁移、Compose、连接工厂和就绪检查已保存为检查点；真实迁移、重复约束和 Redis 命令验证通过之前不标记 Task 2 完成。后续业务、前端、Agent、故障恢复和负载测试仍须按批准计划实施。

重启后的命令及安装位置见 ../operations/安装与续做.md。
