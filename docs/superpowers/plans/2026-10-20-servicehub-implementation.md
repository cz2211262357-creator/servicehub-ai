# ServiceHub AI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 交付可在 PyCharm 打开、用 uv 管理环境、具备真实业务后端和可验证并发保证的电商售后客服与知识库问答项目。

**Architecture:** React/TypeScript 前端通过 HTTP 和 WebSocket 连接模块化 FastAPI API；MySQL 为权威数据源，Redis 承接会话、限流、跨进程通知、向量索引与任务分发。独立 Worker 处理模型和文档任务，使用 Outbox、租约、幂等和执行版本保证故障恢复。

**Tech Stack:** Python 3.12、uv、FastAPI、SQLAlchemy 2 asyncio、asyncmy、Alembic、redis-py asyncio、MySQL 8.4、Redis 8.4、React、TypeScript、Vite、TanStack Query、pytest、Vitest、Docker Compose。

**Spec:** `D:/研究生/简历1/qz-project/docs/superpowers/specs/2026-10-20-servicehub-design.md`，已获用户确认；目录和 uv 要求来自后续用户指令。

## Global Constraints

- 项目根目录固定为 `D:/研究生/简历1/qz-project`；所有新建源码、虚拟环境、包缓存、模型缓存和运行数据放在其下。
- 后端采用 Python 3.12；MySQL 8.4 与 Redis 8.4；前端 React/TypeScript。依赖解析后提交 uv.lock 和 package-lock.json。
- 首版单一网站入口，包含客户门户、客服工作台、订单、售后工单、知识库、审计及真实指标；不执行真实支付退款。
- 服务端会话：256 位随机标识、摘要存储、8 小时有效期；密码 Argon2id；写请求 CSRF；实时通道鉴权和 Origin 检查。
- 所有业务表和服务执行 tenant_id 隔离；客户仅能访问自己的资源；租户从认证身份确定。
- 上传只允许 TXT、Markdown、文本层 PDF，单文件上限 10 MiB；扫描 PDF 不做 OCR。
- 模型、消息与任务的最终一致性由 MySQL 事务、唯一约束和执行版本保证，不能依赖单进程内存。
- 自动重试最多三次；Redis 不可用时不能静默使用进程内队列和限流；生产模式不能静默生成演示回复。
- 所有时间存 UTC，金额用 Decimal；未运行的集成测试或真实模型调用必须明确标记，不能伪报通过或预写 QPS。

## Review Focus

1. 同一消息标识、不同正文的重放：拒绝冲突，不能把新正文静默合并；Task 5 断言 409。
2. 长连接权限变更或账号停用：重验身份、撤销读取能力，不能仅在握手时鉴权；Task 7 测试。
3. PDF 伪后缀、扫描文件、无文本及超限：解析失败可追踪且不启用空索引；Task 9 测试。
4. 文档停用与检索并发：已返回候选也必须再次检查可见性，避免引用失效资料；Task 10 测试。
5. Worker 失去租约后供应商才返回：旧执行者不能提交消息或覆盖成功结果；Task 12 测试。

## 文件与责任边界

```text
qz-project/
  pyproject.toml, uv.lock, uv.toml, .python-version
  .venv/                 # Python 环境，忽略 Git
  .python/               # uv 托管 Python，忽略 Git
  .cache/{uv,npm,...}/    # 项目缓存，忽略 Git
  .data/{mysql,redis,uploads}/
  backend/src/servicehub/
    main.py
    core/{config,errors,logging,dependencies}.py
    db/{base,engine,metadata}.py
    auth/{models,schemas,security,service,routes}.py
    conversations/{models,schemas,service,routes,events}.py
    orders/{models,schemas,service,routes}.py
    after_sales/{models,schemas,service,routes}.py
    jobs/{models,schemas,persistence,service,outbox,worker,handlers}.py
    realtime/{hub,routes}.py
    limits/{rate,leases}.py
    knowledge/{models,schemas,storage,parser,indexer,retrieval,service,routes}.py
    ai/{models,schemas,embedding,provider,tools,agent,service}.py
    operations/{models,audit,metrics,routes}.py
  backend/migrations/, backend/tests/{unit,integration,concurrency}/
  frontend/src/{app,api,components,features,styles}/
  frontend/package.json, frontend/package-lock.json
  scripts/{env,bootstrap,dev,verify}.ps1
  scripts/{seed,load_test,fault_test}.py
  deploy/{api.Dockerfile,web.Dockerfile,nginx.conf}
  compose.yaml, .env.example, .run/
  docs/{architecture,operations,demo,verification}/
```

每个模块通过 DTO 和服务接口交互，不在路由中堆业务逻辑。下列测试片段是必须证明的断言，不是产品实现代码。

---

### Task 1: 本地工程、uv 与最小可运行 API

**Files:** 新建根目录依赖、.gitignore 与 .dockerignore 文件、`scripts/env.ps1`、`scripts/bootstrap.ps1`、`.run/API.run.xml`、`backend/src/servicehub/main.py`、`core/config.py`、`core/errors.py`；测试 `backend/tests/unit/test_app.py`、`test_settings.py`。
**Interfaces:** `Settings` 定义数据库、Redis、运行模式、前端 Origin 和项目数据路径；`load_settings() -> Settings`；`create_app(settings: Settings | None = None) -> FastAPI`；`GET /api/v1/health/live` 返回进程健康。
- [x] 声明依赖和打包路径 `backend/src/servicehub`；脚本从自身路径计算根目录，设 UV_CACHE_DIR、UV_PYTHON_INSTALL_DIR、UV_PROJECT_ENVIRONMENT、UV_TOOL_DIR、UV_TOOL_BIN_DIR 和 npm 缓存；托管 Python 安装不写 PATH 或 Windows 注册表。.gitignore 与 .dockerignore 排除环境、缓存、运行数据、真实凭证和前端依赖。
- [x] 通过 uv 安装项目内托管 Python 3.12，建立根 `.venv` 并同步后端开发依赖。原有 Anaconda 环境不安装项目包，不修改机器级环境变量。
- [x] 写失败测试：`assert response.json()["status"] == "ok"`；生产模式使用演示凭证或不受限 Origin 时配置校验失败；未处理错误包含 request_id、不泄露堆栈。
- [x] 运行 `uv run pytest backend/tests/unit/test_app.py backend/tests/unit/test_settings.py -q` 确认 RED。
- [x] 实现应用工厂、配置、统一错误与请求标识；导入模块不建立数据库连接。
- [x] 重跑上述测试得到 PASS，验证 `.venv/Scripts/python.exe` 能导入应用；提交 `feat: bootstrap local uv FastAPI project`。

### Task 2: 真实 MySQL/Redis、数据模型与迁移

**Files:** `db/base.py`、`db/engine.py`、`db/metadata.py`、各业务模块的 `models.py`、`backend/migrations/env.py`、`backend/migrations/versions/0001_initial.py`、`compose.yaml`、`deploy/api.Dockerfile`；测试 `backend/tests/integration/test_schema.py`。
**Interfaces:** `SessionFactory = async_sessionmaker[AsyncSession]`；`create_database(settings) -> tuple[AsyncEngine, SessionFactory]`；Redis 使用有界连接池；`GET /health/ready` 校验依赖。
- [x] 在 compose 中建立 MySQL 8.4 和 Redis 8.4、健康检查、项目内数据挂载与 noeviction/AOF；加入 API/Worker 镜像定义，但未完成的入口不宣称可用。
- [x] 写失败测试：全新迁移成功且可重复升级；跨租户外键失败；消息、AI 正式回复及工单幂等约束拒绝重复；Redis 的 FT._LIST 与 Streams 命令可执行。
- [x] 启动真实依赖后运行 `uv run pytest backend/tests/integration/test_schema.py -q` 确认 RED；没有 Docker/真实实例时先记录并解决运行环境，不改为 SQLite 冒充通过。
- [x] 建立规格第 6 节所有数据实体、组合索引、UTC/Decimal 字段和 Alembic 迁移；每个并发事务独立 Session。
- [x] 重跑 schema 测试和两次 `uv run alembic -c backend/alembic.ini upgrade head`，均成功；提交 `feat: add persistent schema and real service dependencies`。

### Task 3: 身份、租户、角色与审计基础

**Files:** `auth/{schemas,security,service,routes}.py`、`core/dependencies.py`、`operations/audit.py`；测试 `backend/tests/integration/test_auth.py`、`test_members.py`。
**Interfaces:** `Principal(user_id: int, tenant_id: int, role: str)`；`require_principal(request) -> Principal`；`require_roles(*roles)`；`AuditService.record(principal, action, target_type, target_id, outcome, request_id)`。
- [x] 写失败测试：登录设置 HttpOnly Cookie；Redis 保存会话摘要且 TTL 不大于 28800 秒；缺 CSRF 的写请求 403；停用/退出后会话失效；客服修改角色 403；其他租户成员不可见。
- [x] 运行 `uv run pytest backend/tests/integration/test_auth.py backend/tests/integration/test_members.py -q`，确认 RED。
- [x] 实现 Argon2id、会话撤销、身份再验证、CSRF、管理员成员接口及脱敏审计；认证查询使用短事务，不把身份查询的 Session 留给业务长事务。
- [x] 重跑 PASS；提交 `feat: enforce session auth tenant isolation and role checks`。

### Task 4: 授权订单与售后工单

**Files:** `orders/{schemas,service,routes}.py`、`after_sales/{schemas,service,routes}.py`；测试 `backend/tests/integration/test_orders.py`、`test_after_sales.py`。
**Interfaces:** `OrderService.get_order(principal, order_number) -> OrderDTO`；`TicketService.create_draft(principal, order_id, kind, reason) -> TicketDraftDTO`；`confirm_draft(principal, draft_id, version, idempotency_key) -> TicketDTO`。
- [x] 写失败测试：他人和跨租户订单返回不可访问；金额保持 Decimal；未确认草稿不产生工单；并发确认同一幂等标识仅一张工单；同标识不同申请内容 409；非法状态流转 409 并记录审计。
- [x] 运行 `uv run pytest backend/tests/integration/test_orders.py backend/tests/integration/test_after_sales.py -q` 确认 RED。
- [x] 实现服务端分页、订单归属校验、带过期时间和版本的草稿、确认事务及状态流转。不加入真实退款接口。
- [x] 重跑 PASS；提交 `feat: add owned orders and confirmed after-sales workflow`。

### Task 5: 会话状态机、幂等消息与客服抢单

**Files:** `conversations/{schemas,service,routes,events}.py`；新增 `jobs/schemas.py`、`jobs/persistence.py`；测试 `backend/tests/integration/test_conversations.py`、`backend/tests/concurrency/test_claim.py`、`test_message_idempotency.py`。
**Interfaces:** `ConversationService.create(principal) -> ConversationDTO`；`send_message(principal, conversation_id, client_message_id, body) -> MessageDTO`；`request_handoff(principal, conversation_id)`；`claim(principal, conversation_id, expected_version)`；`close(principal, conversation_id)`；`list_events(principal, conversation_id, after_seq)`；事务内持久化接口 `enqueue_job(session, kind, business_key, payload) -> JobDTO` 位于 `jobs/persistence.py`，供后续投递器使用。
- [x] 写失败测试：重复相同消息只保存一次；同标识不同正文 409；20 个并发领取只一个负责人；客户越权回复 403；关闭后发送 409；事件 seq 连续且唯一。
- [x] 运行 `uv run pytest backend/tests/integration/test_conversations.py backend/tests/concurrency/test_claim.py backend/tests/concurrency/test_message_idempotency.py -q`，确认 RED。
- [x] 实现规格状态机，业务修改、消息、会话内序号、持久任务和 Outbox 同事务提交；客服领取使用数据库条件更新。
- [x] 重跑 PASS 并核对数据库消息/事件数量；提交 `feat: implement transactional conversations and concurrent claiming`。

### Task 6: Outbox、任务投递与故障恢复

**Files:** `jobs/{schemas,service,outbox,worker,handlers}.py`；测试 `backend/tests/integration/test_jobs.py`、`test_outbox.py`、`backend/tests/concurrency/test_worker_recovery.py`。
**Interfaces:** 消费 Task 5 的 `enqueue_job` 和 `JobDTO`；`JobService.acquire(job_id, owner, lease_seconds) -> JobLease | None`；`finish(lease, result)`；`retry(principal, job_id)`；`OutboxDispatcher.tick() -> int`；`Worker.run()`；`JobLease(job_id, owner, version, expires_at)`。
- [x] 写失败测试：Outbox 发送后确认前崩溃会重发但不重复执行正式结果；进程退出后可领取过期任务；旧版本 finish 被拒绝；临时失败最多重试三次；Redis 数据清空后可从 MySQL 对账重建调度。
- [x] 运行 `uv run pytest backend/tests/integration/test_jobs.py backend/tests/integration/test_outbox.py backend/tests/concurrency/test_worker_recovery.py -q`，确认 RED。
- [x] 实现投递租约、Streams 消费组/XACK/XAUTOCLAIM、执行版本、失败记录和管理员重试；网络调用在数据库领取事务外进行；消息仅携带任务标识。
- [x] 重跑 PASS；提交 `feat: add durable outbox jobs and fenced worker recovery`。

### Task 7: 跨进程实时通知与重连

**Files:** `realtime/{hub,routes}.py`；修改 `conversations/events.py`、`jobs/outbox.py`；测试 `backend/tests/integration/test_realtime.py`、`backend/tests/concurrency/test_multiworker_live.py`。
**Interfaces:** `RealtimeHub.publish(event)`；`subscribe(principal, conversation_id, after_seq) -> AsyncIterator[LiveEvent]`；`LiveEvent(kind, conversation_id, seq, generation_epoch, payload)`。
- [x] 写失败测试：两个 API 进程中的客户与客服收到同一正式消息；重连补齐缺失 seq 且不重复；连接后停用账号即失去读取权限；慢客户端缓冲超限断开但可恢复。
- [x] 运行 `uv run pytest backend/tests/integration/test_realtime.py backend/tests/concurrency/test_multiworker_live.py -q`，确认 RED。
- [x] 实现先订阅、再回补与缓冲合并；持久事件从 MySQL 恢复，流式片段通过 Redis 通知；定期重验权限；限制每连接发送缓冲。
- [x] 重跑 PASS；提交 `feat: add authenticated cross-process live conversation events`。

### Task 8: 原子限流与模型执行槽

**Files:** `limits/rate.py`、`limits/leases.py`；测试 `backend/tests/integration/test_limits.py`、`backend/tests/concurrency/test_execution_slots.py`。
**Interfaces:** `RateLimiter.check(tenant_id, user_id, scope) -> RateDecision(allowed, retry_after)`；`ExecutionSlots.acquire(owner, ttl) -> SlotLease | None`；`renew(lease) -> bool`；`release(lease) -> bool`。
- [x] 写失败测试：并发超过测试配置的 5 个请求只放行 5 个；多个 Worker 不超过配置的 2 个有效执行租约；其他 owner 不能释放；过期可回收；Redis 不可用返回 503 而非静默放行。
- [x] 运行 `uv run pytest backend/tests/integration/test_limits.py backend/tests/concurrency/test_execution_slots.py -q`，确认 RED。
- [x] 用短 Lua 脚本原子修改计数与租约；配置 TTL、续租和客户端超时；错误包含 Retry-After。
- [x] 重跑 PASS；提交 `feat: enforce distributed rate limits and model capacity leases`。

### Task 9: 私有文档、解析、版本与索引任务

**Files:** `knowledge/{schemas,storage,parser,indexer,service,routes}.py`；修改 `jobs/handlers.py`；测试 `backend/tests/integration/test_documents.py`、`backend/tests/unit/test_document_parser.py`。
**Interfaces:** `DocumentService.upload(principal, upload) -> DocumentDTO`；`parse_document(path, media_type) -> list[ParsedPage]`；`KnowledgeIndexer.build_version(job_lease, document_version_id)`；`set_enabled(principal, document_id, enabled)`。
- [x] 写失败测试：超过 10 MiB 返回 413；伪后缀和无文本 PDF 失败且可查看原因；路径穿越不能出私有卷；旧版本保持有效直至新版本准备完成；删除后无法下载和检索；越权下载拒绝。
- [x] 运行 `uv run pytest backend/tests/unit/test_document_parser.py backend/tests/integration/test_documents.py -q`，确认 RED。
- [x] 实现受限上传、私有 UUID 文件名、pypdf 文本抽取、带页码分块、后台索引与版本切换；MySQL 全文索引使用 ngram。
- [x] 重跑 PASS；提交 `feat: add private versioned knowledge ingestion`。

### Task 10: Embedding、向量索引与混合检索

**Files:** `knowledge/retrieval.py`、`ai/schemas.py`、`ai/embedding.py`；修改 `knowledge/indexer.py`；测试 `backend/tests/integration/test_retrieval.py`、`backend/tests/unit/test_rank_fusion.py`。
**Interfaces:** `EmbeddingProvider.embed(texts: list[str]) -> list[list[float]]`，协议位于 `ai/schemas.py`、HTTP 实现位于 `ai/embedding.py`；`Retriever.search(principal, query, limit=5) -> list[Evidence]`；`Evidence(chunk_id, document_id, version_id, page, text, score)`；`rrf_rank(rankings)`。
- [x] 写失败测试：关键词和向量排名可重复合并；维度不匹配拒绝写入；相同内容不同租户不能串检索；候选产生后文档停用则不返回证据；不同模型不能写入同一索引。
- [x] 运行 `uv run pytest backend/tests/unit/test_rank_fusion.py backend/tests/integration/test_retrieval.py -q`，确认 RED。
- [x] 实现异步 Embedding 适配、FLOAT32 向量序列化、Redis HNSW、MySQL 全文候选、RRF 与数据库二次授权；演示模式仅关键词，不伪造语义检索。
- [x] 重跑 PASS；提交 `feat: implement tenant-scoped hybrid evidence retrieval`。

### Task 11: 模型适配、流式协议与受控工具

**Files:** `ai/provider.py`、`ai/tools.py`；测试 `backend/tests/unit/test_provider_contract.py`、`test_tools.py`。
**Interfaces:** `ModelProvider.stream(messages, tools, budget) -> AsyncIterator[ModelEvent]`；`ModelEvent(kind, text, tool_call, usage)`；`ToolRegistry.execute(principal, tool_name, arguments) -> ToolResult`；`ToolResult(data, evidence_ids, draft_id)`。
- [x] 写失败测试：碎片化流式响应和工具参数可正确拼接；超时/429/无效 JSON 返回明确错误；无凭证在线模式不回退模拟；任意 SQL、未知工具、跨客户订单和直接退款被拒绝。
- [x] 运行 `uv run pytest backend/tests/unit/test_provider_contract.py backend/tests/unit/test_tools.py -q`，确认 RED。
- [x] 用 httpx 异步实现供应商协议适配及显式演示适配；工具只暴露查政策、查本人订单、准备售后草稿；约束输入长度、工具步数和预算。
- [x] 重跑 PASS；真实供应商凭证只经本地环境输入，不写文件日志或命令参数；提交 `feat: add streaming model adapter and authorized customer tools`。

### Task 12: AI 编排与人工接管一致性

**Files:** `ai/agent.py`、`ai/service.py`；修改 `jobs/handlers.py`；测试 `backend/tests/integration/test_agent_flow.py`、`backend/tests/concurrency/test_handoff_race.py`。
**Interfaces:** `SupportAgent.run(principal, conversation_id, run_id, provider) -> AgentResult`；`AIService.commit_result(job_lease, generation_epoch, result) -> bool`；`AgentResult(text, citations, tool_calls, usage)`。
- [x] 写失败测试：政策问答有有效引用；无依据转人工入口可用；订单工具保持客户归属；延迟回复期间接管后不会保存新 AI 正式消息；过期 Worker 返回不得覆盖结果；相同 ai_run 重投最多一条正式回复。
- [x] 运行 `uv run pytest backend/tests/integration/test_agent_flow.py backend/tests/concurrency/test_handoff_race.py -q`，确认 RED。
- [x] 编排检索与工具循环，记录运行/工具轨迹；提交结果时在短事务核验会话状态、代次和任务租约；流式事件携带代次；失败进入可恢复状态。
- [x] 重跑 PASS；提交 `feat: integrate support agent with fenced handoff-safe replies`。

### Task 13: React 基础、登录与类型化接口

**Files:** `frontend/package.json`、Vite/TypeScript 配置、`src/app/{App,Router,AuthProvider}.tsx`、`src/api/client.ts`、`src/api/generated/schema.d.ts`、`scripts/export_openapi.py`、`src/components/{Layout,Feedback}.tsx`、`src/styles/global.css`；测试 `src/api/client.test.ts`、`src/app/AuthProvider.test.tsx`。
**Interfaces:** `api<T>(path, options): Promise<T>`；`ApiError(code, message, requestId, retryAfter)`；`useAuth(): { user, login, logout, loading }`；路由 `/login`、`/portal`、`/app`。
- [x] 初始化前端元数据，npm 缓存指向根 .cache/npm，依赖安装到 frontend/node_modules；使用 `scripts/export_openapi.py` 从不启动依赖连接的应用工厂导出 OpenAPI，以 openapi-typescript 生成 `src/api/generated/schema.d.ts`；不手工维护一套易漂移的响应类型。
- [x] 写失败测试：发送 Cookie/CSRF，401 回到登录，409/429 提供正确反馈，用户退出清理租户数据缓存。
- [x] 运行 `npm --prefix frontend test -- --run` 确认 RED；实现接口层、登录、角色路由和响应式控制台骨架。
- [x] 测试 PASS 且 `npm --prefix frontend run typecheck` 成功；提交 `feat: build typed React app shell and authenticated sessions`。

### Task 14: 客户门户与可靠聊天交互

**Files:** `frontend/src/features/portal/{PortalPage,ChatPanel,OrderPanel,AfterSaleForm}.tsx`、`src/api/live.ts`；测试 `src/features/portal/PortalPage.test.tsx`、`src/api/live.test.ts`。
**Interfaces:** `useConversationLive(conversationId): { messages, status, connectionState }`；`sendCustomerMessage(conversationId, clientMessageId, text)`；复用 Task 13 的 api/useAuth。
- [x] 写失败测试：失败重发沿用原消息 ID；同一 seq 不重复显示；接管后丢弃旧代次 token；引用能定位授权内容；售后草稿明确确认后才提交。
- [x] 运行 `npm --prefix frontend test -- --run src/features/portal src/api/live.test.ts`，确认 RED。
- [x] 实现客户订单、消息、流式回复、转人工、申请确认和结果反馈；补齐加载、空数据、失败、重连状态。
- [x] 重跑 PASS、类型检查通过；提交 `feat: complete customer chat orders and after-sales portal`。

### Task 15: 客服与知识库控制台

**Files:** `frontend/src/features/inbox/{InboxPage,ConversationWorkspace}.tsx`、`features/orders/OrdersPage.tsx`、`features/tickets/TicketsPage.tsx`、`features/knowledge/KnowledgePage.tsx`、`features/settings/MembersPage.tsx`；测试对应 `*.test.tsx`。
**Interfaces:** 各页面只消费前述 /api/v1 业务接口；后台列表筛选与分页由服务端执行，查询缓存键包含当前身份和租户。
- [x] 写失败测试：接单冲突展示新的负责人；客服发送失败可重试；知识上传失败可重试、停用后状态刷新；普通客服看不到成员编辑操作且后端仍拒绝；分页筛选触发真实请求。
- [x] 运行 `npm --prefix frontend test -- --run src/features/inbox src/features/knowledge src/features/settings`，确认 RED。
- [x] 实现三栏会话工作台和可操作的订单、工单、知识库、成员界面，禁止无后端支撑的假按钮。
- [x] 重跑 PASS、类型检查与构建通过；提交 `feat: add functional staff and knowledge management console`。

### Task 16: 运营指标、审计界面与运维可见性

**Files:** `core/logging.py`、`operations/{metrics,routes}.py`、`frontend/src/features/operations/{OverviewPage,AuditPage,JobsPage}.tsx`；测试 `backend/tests/integration/test_operations.py` 和前端对应测试。
**Interfaces:** `MetricsService.summary(principal, start, end) -> MetricsSummary`；`AuditService.list(principal, cursor, filters)`；`JobService.retry(principal, job_id)`；受限 `GET /metrics`。
- [x] 写失败测试：统计与已插入会话/任务数量吻合；时间窗口边界正确；无供应商 usage 显示未知；跨租户审计不可读；重试产生审计；日志不含密码/API Key。
- [x] 运行 `uv run pytest backend/tests/integration/test_operations.py -q` 和前端运营测试，确认 RED。
- [x] 实现真实 SQL 聚合、结构化关联日志、Prometheus 指标及任务/审计界面；限制指标入口，不暴露客户内容。
- [x] 重跑 PASS；提交 `feat: expose real operational metrics audits and job recovery views`。

### Task 17: 实例级并发、故障实验与性能报告

**Files:** `scripts/load_test.py`、`scripts/fault_test.py`、`backend/tests/concurrency/test_scenarios.py`、`docs/verification/README.md`；修改已有竞争测试以支持真实 HTTP 进程。
**Interfaces:** `uv run python scripts/load_test.py --users N --duration S --scenario business|live|agent --output PATH`；报告包含环境、模式、并发、吞吐、P50/P95、错误率。
- [x] 写失败测试：统计器正确计算延迟分位数；并发场景失败会非零退出，不能吞掉断言；缺依赖明确停止，不输出通过。
- [x] 运行脚本单元测试确认 RED；实现可配置负载、真实多 API 进程测试编排、Worker 中断和 Redis 短暂故障实验。
- [x] 逐级执行真实依赖的消息去重、抢单、接管、重连和恢复场景，核对业务数据库断言。
- [x] 分别输出模拟模型与真实供应商的验证状态；只有实际执行的数据进入报告；提交 `test: verify concurrent business invariants and recovery scenarios`。

### Task 18: 启动、PyCharm、部署与交付

**Files:** `scripts/dev.ps1`、`scripts/verify.ps1`、`scripts/seed.py`、`deploy/{web.Dockerfile,nginx.conf}`、`compose.yaml`、`.env.example`、`.run/{API,Worker}.run.xml`、`README.md`、`docs/{architecture,operations,demo}/`、`.github/workflows/ci.yml`。
**Interfaces:** `scripts/bootstrap.ps1` 安装项目依赖；`scripts/dev.ps1 -Service api|worker|frontend`；`scripts/verify.ps1` 执行可复现检查；种子初始化幂等且仅开发模式可运行。
- [x] 复用业务验收测试验证种子重复执行不重复建租户、订单；部署 smoke 在全新数据库启动后完成客户问答、人工回复和售后申请。
- [x] 完成 Web 反向代理、WebSocket Upgrade、Docker 健康检查、私有上传卷和项目内数据挂载；迁移与初始化使用独立步骤。
- [x] 编写 PyCharm 导入说明：项目根目录、.venv/Scripts/python.exe、工作目录、API 与 Worker 运行配置；密钥通过本地未跟踪环境文件或安全输入配置。
- [x] 验证 `uv run pytest backend/tests/unit -q`、真实依赖集成/并发测试、`npm --prefix frontend run typecheck`、`npm --prefix frontend test -- --run`、`npm --prefix frontend run build` 和完整浏览器业务流程。
- [x] 保存实际运行记录、启动步骤、备份/恢复说明、演示脚本、与真实实现对应的简历要点；提交 `docs: deliver reproducible full-stack project and verification guide`。

## 总体验收与状态记录

每个任务开始前保留失败测试证据，结束后记录实际命令与结果；纯配置和文档不添加镜像式测试。任务完成后才勾选，不按生成文件数量判断完成。

若本机暂不能运行 Docker 或真实 MySQL/Redis，继续可独立完成的源码和单元检查，并将实例级验证明确留为未执行；不得将整个项目标记为已完成，也不得用模拟服务生成真实承载指标。涉及系统级 Docker/WSL 安装时单独说明所需系统变更。

实现时默认沿用用户指定目录，不另建放在其他位置的工作树。代码和依赖不能散落到最初的简历文件夹。

## 执行方式与审阅

建议采用 Native：在当前会话逐项实施，按测试推进并做最终整体审查。项目模块共享认证、事务、任务和事件契约，连续实施能及时解决接口衔接；只有用户选择或适用技能要求时才调度子代理。

本计划已获用户批准，采用当前会话 Native 执行。任务完成记录与实际验证见 docs/verification。
