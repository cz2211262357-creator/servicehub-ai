# 验证与测量

普通测试使用真实 MySQL 8.4 的 servicehub_test 数据库和独立 Redis 8.4（6380/db0）。测试只清理各自创建的租户；Redis 清理有固定测试端点保护。应用 Redis 6379 不会被测试清空。Redis Search 只允许 db0，因此不能用应用 Redis 的其他 db 代替隔离实例。

在项目根目录执行：

~~~powershell
. .\scripts\env.ps1
docker compose up -d mysql redis redis-test
uv run pytest -q
uv run python scripts/fault_test.py --scenario all --output .cache/fault-report.json
uv run python scripts/load_test.py --users 8 --duration 5 --scenario business --output .cache/load-business.json
uv run python scripts/load_test.py --users 4 --duration 5 --scenario live --output .cache/load-live.json
~~~

脚本启动两个独立 Uvicorn 进程，经真实 HTTP 登录和发送请求。business 测量消息持久化接口，live 测量从发送请求到另一进程 WebSocket 收到同一消息。每个虚拟客户有独立账号和会话。两种场景先转人工等待，避免把网络模型调用混入业务测量；高负载 API 子进程显式放宽消息频控，正式应用默认仍为每客户每分钟 30 次，另有独立限流测试。这些是闭环负载观测，不代表机器的性能上限，也不代表模型 TPS。

fault_test 实际终止正在执行的测试 Worker，等待数据库租约自然过期后由另一进程恢复，并核对版本围栏。Redis 实验仅停启 compose 的 redis-test，检查 API 返回 503、MySQL 消息未丢失、恢复后任务可以从数据库重新投递。测试任务使用确定性探针处理器，不生成客服回答。

真实模型单独显式执行，使用本地 .env 的百炼凭证，有实际 API 费用：

~~~powershell
uv run pytest backend/tests/online -m online -q
uv run python scripts/load_test.py --users 1 --duration 1 --scenario agent --allow-paid-models --output .cache/load-agent.json
~~~

agent 场景测量完整在线任务完成时间。普通单元测试中的确定性协议 fixture 只用来验证流式解析、授权与竞态，不是产品模型提供者；项目运行时使用真实在线接口。未执行的场景必须记录为未执行。脚本保留子进程失败退出码，失败断言不能被统计器吞掉；每次执行前移除同名上次测量文件，避免把历史数字当成本次结果。

实际记录见 [2026-10-21-validation.md](2026-10-21-validation.md)。原始报告保存在项目 .cache，可能被重复执行覆盖。发布文档中的数字需要与当次原始报告一致。
