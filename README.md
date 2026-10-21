# ServiceHub AI

[![CI](https://github.com/cz2211262357-creator/servicehub-ai/actions/workflows/ci.yml/badge.svg)](https://github.com/cz2211262357-creator/servicehub-ai/actions/workflows/ci.yml)

**电商售后客服与知识库问答平台**。采用 React / TypeScript + FastAPI + MySQL + Redis，接入百炼在线模型，串联知识问答、订单核查、客户确认、人工接待和售后工单。

项目重点是将 Agent 接入有权限、状态和并发约束的业务系统：回答可引用原始知识版本，工具只能查询本人订单或准备草稿，正式售后申请需要客户确认，人工接管后旧 AI 执行不能提交回答。

## 业务能力

| 角色 | 可用功能 |
|---|---|
| 客户 | 创建咨询、流式问答、来源引用、本人订单、售后草稿确认、工单进度、转人工 |
| 客服 | 服务队列、竞争接单、人工回复、客户订单上下文、工单审核及时间线 |
| 管理员 | 私有知识上传及版本管理、文档停用、成员权限、失败任务恢复、审计与真实运营统计 |

商家、订单和政策使用可替换的虚构业务资料；模型调用、向量索引、数据库写入和后台执行均为真实链路。当前不对接外部电商、支付退款或物流系统。

![客户会话中的在线问答与人工回复](docs/verification/ui-customer.png)

## 技术栈

| 层次 | 实现 |
|---|---|
| 前端 | React、TypeScript、Vite、TanStack Query、WebSocket |
| API | FastAPI、Pydantic、SQLAlchemy asyncio、Alembic |
| 数据与检索 | MySQL 8.4、Redis 8.4 Search / HNSW、ngram 全文、RRF |
| Agent | qwen-plus、Function Calling、text-embedding-v4 / 1024维、参数校验与工具白名单 |
| 异步与并发 | asyncio、独立 Worker、Redis Streams、事务 Outbox、租约及执行版本、Lua 限流 |
| 工程交付 | uv、Docker Compose、Nginx、pytest、Vitest、GitHub Actions |

## 架构

~~~mermaid
flowchart LR
    UI[React / TypeScript] --> WEB[Nginx]
    WEB --> API[FastAPI 多进程]
    API --> DB[(MySQL 业务权威源)]
    API --> REDIS[(Redis)]
    WORKER[独立 Worker / Outbox] --> DB
    WORKER --> REDIS
    WORKER --> LLM[qwen-plus]
    WORKER --> EMB[text-embedding-v4]
    REDIS --> API
~~~

### 知识与 Agent

- 管理员上传 TXT、Markdown 或文本 PDF，解析后保留文档版本、页码和原始文件；新版本索引完成后才切换有效版本。
- MySQL ngram 全文与 Redis HNSW 双路召回，使用 RRF 融合；候选排名前筛选当前有效版本，提交回答前再次授权复核。
- 引用固定到回答所依据的版本；客户读取历史版本还需存在本人会话中的引用，避免旧回答下载到新政策。
- 只注册 search_policy、lookup_order、prepare_after_sale 三类工具，按当前客户身份二次授权；客户明确确认后才创建工单。
- 设置输入、输出、工具步数、超时和全局模型并发预算，记录实际 Token usage 与工具执行结果；供应商没有返回 usage 时保留为空。

### 并发与故障恢复

| 场景 | 机制 |
|---|---|
| 重发消息 / 重复确认 | 唯一幂等键、请求内容校验、短事务及业务唯一约束 |
| 多客服抢单 | 会话状态、负责人和版本条件更新，竞争失败返回 409 |
| 人工接管 / AI 迟到 | generation_epoch、当前状态、数据库租约与 execution_version 提交围栏 |
| Worker 崩溃 | 到期租约回收、Streams 重新领取、MySQL Outbox 补投和幂等执行 |
| 慢模型任务 | 投递与回收循环独立于执行循环，使用有界任务集合补充空闲槽 |
| 断线或跨 API 实例 | MySQL 单调事件序号、Redis 通知、游标补放与前端去重 |
| 失败后刷新页面 | 权威任务快照区分排队、执行、自动重试和最终失败 |

消息、事件、AI 运行、任务与 Outbox 在同一事务创建。投递语义为至少一次；业务幂等与执行围栏用于避免重复落库。默认客户消息限制为每分钟 30 次，模型全局并发槽为 4；参数可按目标部署环境配置。

## 快速启动

需要 Git、Docker Desktop / Docker Compose，以及可用的百炼 Key。首次部署：

~~~bash
git clone https://github.com/cz2211262357-creator/servicehub-ai.git
cd servicehub-ai
cp .env.example .env
~~~

Windows PowerShell 可用 Copy-Item .env.example .env。编辑本地 .env：

~~~dotenv
SERVICEHUB_MODEL_MODE=online
SERVICEHUB_MODEL_NAME=qwen-plus
SERVICEHUB_MODEL_API_KEY=填入自己的百炼Key
SERVICEHUB_EMBEDDING_MODEL=text-embedding-v4
SERVICEHUB_EMBEDDING_API_KEY=填入自己的百炼Key
SERVICEHUB_EMBEDDING_DIMENSIONS=1024
~~~

当前默认使用北京地域兼容接口，Key、地域与模型权限需要匹配。两个 Key 字段可使用同一具备相应权限的 Key。

~~~bash
docker compose --profile app build
docker compose up -d --wait mysql redis
docker compose --profile app run --rm --no-deps api alembic -c backend/alembic.ini upgrade head
docker compose --profile app run --rm --no-deps api python scripts/seed.py
docker compose --profile app up -d --wait api worker web
~~~

- 网站：http://localhost:8080
- API 文档：http://127.0.0.1:8000/api/docs
- seed 输出两个企业的实际编号。各企业开发账号包括 admin@example.com、agent@example.com、agent2@example.com、customer@example.com、customer2@example.com。
- 默认开发密码为 ServiceHub-2026!；首次初始化前可设置 SERVICEHUB_SEED_PASSWORD。重复初始化不会重置已有密码，种子仅允许 development。
- 若重新创建了 API 而保留旧 Web 容器，可运行 docker compose --profile app restart web 更新 Nginx 的容器地址解析。

本机开发与 PyCharm 配置见 [开发与启动](docs/operations/PyCharm与启动.md)。停止容器可用 docker compose --profile app stop，持久化数据保存在项目 .data/。

## 验证与实测

2026-10-21 本地验证快照：

- 后端 **94 passed**；前端 **18 passed**；Ruff、TypeScript、生成接口类型和生产构建通过。
- 双 API 实际验证：20 次重复消息仅一条消息 / 一个任务；20 次重复确认仅一张工单；20 名不同客服竞争接单，仅一人成功。
- 实际终止 Worker 并等待自然租约到期后恢复；隔离测试 Redis 停启后核对 MySQL 数据及任务补投，两个故障实验通过。
- 真实 text-embedding-v4 入库与授权召回通过；完整 Docker 在线业务验收 **18.297 秒**，覆盖 RAG、订单工具、客户确认、人工回复和工单完成。
- 8 个虚拟客户的消息保存闭环测量：430 请求 / 5.095 秒，P95 102.06 ms；跨进程实时送达场景为 4 个虚拟客户、132 请求 / 5.173 秒，P95 187.49 ms；两组错误率均为 0。

上述接口测量使用双 API 进程、真实 MySQL / Redis，未混入模型调用，也未寻找机器承载上限。它们不能用作生产最大 QPS 或大模型吞吐指标。完整环境、口径、失败回合和原始报告见 [实际验证记录](docs/verification/2026-10-21-validation.md)。

在 Windows 开发环境中：

~~~powershell
. .\scripts\env.ps1
docker compose up -d --wait mysql redis redis-test
uv sync --all-groups --frozen
npm --prefix frontend ci
.\scripts\verify.ps1
.\scripts\verify.ps1 -Faults
.\scripts\verify.ps1 -Online
uv run python scripts/smoke.py --tenant-id 1
~~~

普通测试使用 servicehub_test 与独立 Redis 6380/db0，不调用付费模型。Online 和 smoke 需配置真实 Key，会产生供应商调用费用。GitHub Actions 的当前结果以本仓库 Actions 页面为准；上述数字是已归档的本地实测。

## 目录

~~~text
backend/src/servicehub/    认证、会话、订单、售后、知识、Agent、任务和运营模块
backend/tests/            单元、真实依赖集成、并发、故障及显式在线验证
frontend/src/             客户门户、客服与管理界面、生成接口类型
scripts/                  启动、初始化、完整验收、负载与故障实验
deploy/                   API / Web 镜像、Nginx、MySQL 初始化
docs/                     架构、运维、业务操作和验证报告
~~~

## 范围与凭证

.env、上传文件、数据库、缓存和开发工具不进入 Git。密钥只由后端及 Worker 从环境读取。单文件最多 10 MiB，目前支持文本 PDF，不包含扫描 PDF OCR。

外网部署需要配置 HTTPS、Secure Cookie、独立凭证与备份保留策略。生产容量、长期稳定性与跨主机还原尚未验证；领域回答质量需使用目标商家资料评测。目前没有一键全量索引重建命令，可保留 Redis AOF 或逐文档上传新版本重建。供应商已接受的请求在本地取消后仍可能消耗 Token。
