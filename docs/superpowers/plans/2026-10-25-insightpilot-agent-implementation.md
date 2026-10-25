# InsightPilot 智能问数 Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 用户已选择当前会话逐项实现，不重新询问执行方式。

**Goal:** 交付可独立运行的经营分析 Agent，真实完成指标口径确认、受控 SQL 查询、图表与带证据报告，并验证多租户、暂停恢复、任务取消与旧执行隔离。

**Architecture:** 独立 FastAPI API、Python Worker、受保护的 MCP Streamable HTTP 数据服务及 React 工作台。PostgreSQL 分离业务只读、应用状态和官方 LangGraph checkpoint 连接；Redis 仅协调资源和通知。每个 run 固定所属 topic，后台报告不依赖当前前台 epoch；所有 checkpoint 与报告写入和数据库执行权检查在同一事务。

**Tech Stack:** Python 3.12、uv 0.11.16、FastAPI 0.162.0、LangGraph 1.2.17、langgraph-checkpoint-postgres 3.2.0、MCP Python SDK 2.3.0、SQLGlot 30.24.0、psycopg 3.3.6 / psycopg-pool 3.3.3、Redis Python 8.1.0、Pandas 3.1.0、Plotly 7.1.0、langchain-openai 1.7.2、PostgreSQL 18、Redis 8.4、React / TypeScript。这些是2026-10-25官方PyPI元数据确认的稳定版本，Task 1必须用uv真实解算并锁定兼容依赖，不使用预发布包或悄悄改版本。

**Spec:** `../specs/2026-10-22-insightpilot-agent-design.md`；同时遵守 `2026-10-22-conversation-interruption-design.md`、`2026-10-22-execution-consistency-design.md` 和 `../../verification/java-concurrency-acceptance.md` 的 P01–P03。Java 主线验收后才开始本项目代码实施。

## Global Constraints

- 项目 P=`projects/insightpilot-agent/`，独立 pyproject/uv.lock/frontend/Compose/tests/README，不要求 ServiceHub 启动。不将 Java 业务改为 Java+Python 混合后端。
- 物理根 R=`D:/研究生/简历1/qz-project`；源码、工具、环境、缓存、测试输出、数据均在 R 内。新工作树 `R/.cache/worktrees/insightpilot-agent`、分支 `codex/insightpilot-agent` 从已完成 Java 的实际本地主线建立；先核验 common-dir/HEAD/状态/归属，不 reset 或覆盖已有内容。Docker 引擎层/镜像为既有例外，不改全局配置。
- uv 的 cache/python install dir 为 R/.cache/uv 和 R/.python；虚拟环境为 P/.venv。临时文件和报告为 R/.cache/insightpilot-verification，持久化为 R/.data/insightpilot；私有环境文件位于 R/.env.insightpilot。脚本接收绝对 WorkspaceRoot；不把嵌套工作树误当物理根。
- 端口仅127.0.0.1：Web8090/API8010/MCP8011/PostgreSQL5433/Redis6382；启动前查占用。测试只用独立随机回环端口、独立数据库/账号；不访问原 Python 或 Java 运行库。
- 运行默认真实百炼 qwen-plus；MCP 工具真实 HTTP 执行。确定性供应商仅用于故障测试，不成为产品固定回答降级。不保存或展示内部思考链，不外发真实业务资料；在线评测只发送明确的虚构数据。
- 只读分析，不写业务数据、不退款、不发送外部消息、不执行模型生成的 Python/JS/Shell、任意网络或任意 MCP 服务。模型不控制租户、数据库、DSN、账号、权限或连接地址。
- 只允许单条 SELECT，递归检查 CTE；拒绝写语句、SELECT INTO、锁定、COPY、系统关系、非白名单表列及函数。SQL AST 与参数共同校验，数据库只读事务、强制 RLS、statement_timeout 再防护。
- 每查询1000行、1MiB、3秒；每run最多8次模型、4次查询执行、2次SQL修正，先持久预留再调用，恢复不归零。EXPLAIN根Total Cost>10000或Plan Rows>100000需要HITL；批准不解除权限、只读或硬限额。
- 全项目执行和模型各最多2，BACKGROUND最多1，保留至少1个INTERACTIVE名额；多进程共用原子准入，不能每进程自称全局2。前台排队60秒/执行120秒；后台排队600秒/累计执行300秒；每次模型min(45秒,remaining)。这些 Agent 后台时限为本计划显式初始配置，不套用Java聊天120秒。
- 租约60秒、续期10秒；每run最多3次自动执行尝试，失败退避5/30秒，永久错误、撤权和取消不自动重试。HITL最长24小时，不持执行资源；等待时间不消耗累计执行预算，恢复保留已用预算，故障/自动重试不延长执行限额。
- 同run只允许一个有效executor；checkpoint、query evidence、最终报告必须按tenant/topic/run/task_revision/lease token/auth_version复核。不能使用当前active_topic/epoch拒绝合法后台完成。
- 每个不可变run一个graph thread_id。用户修改任务会原子地使旧run SUPERSEDED并创建新run、新thread，task_revision递增且记录supersedes_run_id；取消推进旧revision使旧确认失效。普通前台切换不修改后台revision。
- 语境只用同topic最近20条/16000字符已完成、有效的消息和重验事实；取消/partial/失败输出排除。新话题不继承SQL、参数、确认或已授权结论。新消息不是HITL批准。
- 金额使用Decimal/NUMERIC(18,2)，JSON定点字符串，统计舍入HALF_UP；时间用aware UTC / TIMESTAMPTZ，UI明确指标时区Asia/Shanghai及半开时间区间。数据为空和分母为0与数值0区分。
- Cookie登录使用opaque随机令牌、CSRF与Origin校验；MCP使用短期受众绑定签名上下文，并逐次复核当前数据库权限。密钥、凭证、私有报告/原始日志、个人简历和上传不入Git。

## Review Focus

1. 两页并发提交相同client_message_id、相同key不同正文、旧HTTP响应迟到：只产生一个逻辑run，原回执不改写后续epoch（Task 5、11）。
2. A后台报告执行时切B再回A，或在B发送“确认”：后台A合法完成只归A，不能被B隐式批准/取消；旧前台事件过滤（Task 9–12，P01/P02）。
3. 用户撤权恰逢SQL结果返回或checkpoint落库：同事务授权与fence锁使结果不能越权提交，恢复不继承旧授权（Task 3、6、8、12）。
4. lease到期但尚无新owner、A检查后暂停而B恢复、进程在查询后checkpoint前退出：旧checkpoint/报告拒绝；已发生只读查询可重复但不冒称供应商恰好执行一次（Task 5、8、10、12，P03）。
5. 嵌套CTE/别名/注释/字符串、危险函数、池连接租户残留、分页截断导致比例失真：SQL权限不可绕过，预算/截断明确，报告不编造完整汇总（Task 2、4、6、9、12）。

## 实现前已消除的设计歧义

1. **security_invoker与底表权限。** PostgreSQL要求invoker对底表具有SELECT。实现将底表放`business_private`，给只读角色所需SELECT但不给该schema的USAGE；只给`analysis`白名单视图schema的USAGE和SELECT，视图security_invoker+底表FORCE RLS。直接命名底表应42501，视图仍按调用者RLS过滤。不是宣称数据库只授予了视图SELECT；用真实角色证明实际可达边界。
2. **官方checkpointer的原子fence。** 使用官方AsyncPostgresSaver的公共aput/aput_writes等接口，传入已经开启事务的同一psycopg连接；该事务先调用受限SQL函数锁当前授权行与run、核对lease/revision，再执行官方保存，直到外层提交才释放锁。不能在状态连接检查后换另一个自动提交连接写checkpoint。setup在专门迁移连接autocommit=True、dict_row下运行；运行期禁止setup。严格msgpack、禁止pickle。
3. **恢复预算。** 暂停释放lease/执行槽，保留已消耗模型/查询次数和累计执行时间；显式恢复获得新token并只使用剩余预算。任务修改是新run，不偷偷复用旧SQL批准。应用超时只承诺阻止后续调用/提交，不承诺供应商停止计算或计费。

## 文件边界与公共类型

以下路径相对P；Python包根`src/insightpilot/`简称S，测试`tests/`简称T。按职责拆文件，不引入共享ServiceHub运行库。

| 路径 | 职责 |
|---|---|
| pyproject.toml / uv.lock / compose.yaml / Dockerfile / scripts/ | 独立依赖、进程与环境 |
| S/config.py / types.py / db.py / api.py | 配置、Actor/Fence/DTO、连接池和入口 |
| S/auth/ / S/catalog/ / migrations/ / fixtures/ | 身份、指标、角色、RLS和虚构数据 |
| S/runs/ / S/checkpoints.py / S/admission.py / S/worker.py | 状态、租约、预算、检查点与恢复 |
| S/query/ / S/mcp_server.py / S/mcp_client.py | AST策略、只读查询、证据与真实MCP |
| S/agent/ / S/reports/ / frontend/ | LangGraph、确定性报告与工作台 |
| evals/ / T/unit/ / T/integration/ / T/system/ / docs/ | 金标准、真实依赖、故障及讲解证据 |

`Actor(tenant_id:UUID,user_id:UUID,role:Role,auth_version:int)`、`RunFence(tenant_id:UUID,run_id:UUID,topic_id:UUID,task_revision:int,execution_version:int,owner:UUID,auth_version:int)`为frozen dataclass，不能由HTTP body或模型填充。`Role={ADMIN,ANALYST}`，`JsonScalar=str|int|bool|None`（金额和时间参数用受校验字符串）。模型公开输入/输出为Pydantic extra=forbid DTO，所有日期/Decimal显式序列化。所有SQL/HTTP业务错误返回`{error:{code,message,request_id}}`，422输入、401身份、403权限、404对象不可见、409版本冲突、429配额、503依赖不可用。

### Task 1: 独立构建、运行配置与健康契约

**Files:** Create P/{pyproject.toml,uv.lock,.python-version,.env.example,.dockerignore,Dockerfile,compose.yaml}, S/{__init__,config,types,db,api}.py, scripts/{env.ps1,env.sh,bootstrap.ps1}, T/unit/test_config.py, T/integration/test_health.py, frontend/{package.json,package-lock.json,tsconfig.json,vite.config.ts}。
**Interfaces:** `Settings.from_environment(workspace_root:Path)->Settings`；`create_app(settings:Settings)->FastAPI`；`DatabasePools.open()/close()`分别持有state/checkpoint/query池；GET `/api/v1/health/{live,ready}`。

- [ ] Step 1 写test_config拒绝R外数据/临时路径、缺真实模型凭证、明文输出secret；test_health验证依赖未就绪503且无DSN，live200。
- [ ] Step 2 `uv run --project P pytest T/unit/test_config.py T/integration/test_health.py -q`观察行为RED；先只建立最小可跑测试的引导，依赖解算失败不是RED。
- [ ] Step 3 实现配置和健康；uv真实解算上述稳定版本并锁依赖；Python3.12环境在P/.venv，前端从现有lock确认精确React/TS/Vite版本后独立锁定。Compose固定Postgres18当前稳定patch与digest、沿用已核验Redis8.4digest，API/Worker/MCP分别启动；记录下载来源与SHA，不改全局工具。
- [ ] Step 4 同命令GREEN；`uv sync --frozen`、Compose config和两种cwd启动路径检查通过，端口全为127.0.0.1。
- [ ] Step 5 提交 `build: bootstrap independent InsightPilot runtime and health contracts`。

### Task 2: 数据角色、RLS、指标目录与可复现经营数据

**Files:** Create migrations/{001_roles,002_business,003_state,004_rls_views}.sql, S/catalog/{models,repository}.py, fixtures/generate.py, fixtures/metrics.json, evals/gold_cases.json, T/integration/test_rls_catalog.py。
**Interfaces:** `CatalogRepository.list_datasets(actor:Actor)->Catalog`、`get_metric_definition(actor:Actor,metric_id:str)->MetricDefinition`；`seed_synthetic(conn,seed:int=20261025)->DatasetVersion`；六指标ID为paid_sales/net_sales/order_count/aftersale_rate/refund_amount/avg_resolution_hours。

- [ ] Step 1 写真实角色测试：两租户、同池A→B→无上下文；只读视图成功、直接底表/状态/checkpoint/system敏感关系不可达，写视图/业务表拒绝；invoker和FORCE RLS实际生效；未知指标404。
- [ ] Step 2 `pytest T/integration/test_rls_catalog.py -q`观察无角色/策略时RED，不用SQLite模拟RLS。
- [ ] Step 3 创建迁移owner(NOLOGIN)、app_state、graph_checkpoint、business_reader独立角色；reader无SUPERUSER/BYPASSRLS/owner/CREATE/TEMP/写权。业务覆盖orders/items/products/channels/regions/aftersales，维表也带tenant。金额/状态/退款分配粒度写进口径，避免join扇出重复销售额。按已说明schema授权方案建立视图，tenant由可信事务set_config(...,true)注入。建立users/sessions/conversations/topics/messages/runs/query_evidence/confirmations/events/audits及复合FK、幂等唯一键；固定数据含空/退款/跨月边界。提供20题gold SQL而非答案常量。
- [ ] Step 4 同命令GREEN；迁移可重复、两次相同seed生成hash相同，20条gold实际只读SQL均可执行；确认pool事务退出后tenant为空。
- [ ] Step 5 提交 `feat: add isolated analytics roles RLS catalog and reproducible fixtures`。

### Task 3: 登录、动态授权与管理边界

**Files:** Create S/auth/{service,routes,passwords}.py, T/integration/test_auth.py, scripts/accounts.py。
**Interfaces:** `AuthService.authenticate(cookie:str)->Actor`、`require_current(conn,actor:Actor,for_write:bool)->None`；HTTP login/me/logout、GET/PATCH `/api/v1/members/{id}`；锁顺序user按ID升序→conversation/topic→run→evidence/confirmation。

- [ ] Step 1 测登录/CSRF/Origin/租户/同租户他人对象；已登录用户被禁用、角色变化、auth_version提升后旧会话401；两个真实事务分别覆盖撤权先提交和合法写先持锁，不能靠事务外if。
- [ ] Step 2 `pytest T/integration/test_auth.py -q`先RED。
- [ ] Step 3 Cookie随机256bit且只存摘要，TTL28800秒、HttpOnly/SameSite、生产Secure；Argon2密码初始化只存R私有文件，不给默认公开密码。请求与工具复核当前enabled/role/auth_version；变更和版本提升同事务。数据库不可用失败关闭；checkpoint不保存可复用授权结论。
- [ ] Step 4 同命令GREEN；CLI拒绝覆盖已有账号/导出密码到Git，所有角色错误不泄露对象内容。
- [ ] Step 5 提交 `feat: secure InsightPilot identities and transactional revocation`。

### Task 4: SQL AST白名单、参数与查询指纹

**Files:** Create S/query/{models,policy,parameters}.py, T/unit/test_sql_policy.py。
**Interfaces:** `SqlPolicy.validate(sql:str,params:dict[str,JsonScalar],catalog:Catalog)->ValidatedSql`；`compile_bound(query:ValidatedSql)->tuple[str,dict]`；ValidatedSql含规范SQL/hash、数据集/列、参数与策略版本。

- [ ] Step 1 参数化测试正常SELECT/聚合/CTE/别名/子查询与注入：多语句、CTE写入、SELECT INTO、FOR UPDATE、COPY、pg_sleep/set_config/文件网络/UDF、系统表、跨schema、未准许列、混淆引号和参数。assert只允许受管analysis视图，拒绝码稳定；字符串`:start`及`%`不能被参数替换破坏。
- [ ] Step 2 `pytest T/unit/test_sql_policy.py -q`先RED。
- [ ] Step 3 SQLGlot postgres解析整棵树，递归解析CTE作用域与列来源；只接受白名单节点和COUNT/SUM/AVG/MIN/MAX/DATE_TRUNC/COALESCE/NULLIF/ROUND及必要CAST/CASE/算术。模型SQL使用`:name`参数；AST Placeholder经专用generator输出psycopg命名绑定，值绝不字符串拼接，文字百分号转义。限制SQL16000字符、参数32个、连接/子查询深度8，递归CTE暂拒绝。SELECT *只能在目录允许的安全展开后使用；LIMIT<=1000不足以证明汇总完整。
- [ ] Step 4 同命令GREEN；保存策略允许/拒绝矩阵，不依赖正则删关键字来保证只读。
- [ ] Step 5 提交 `feat: validate analytics SQL through recursive AST and bound parameters`。

### Task 5: 任务、幂等、话题、租约与持久预算

**Files:** Create S/runs/{models,repository,service,routes,budget}.py, migrations/005_execution_fence.sql, T/integration/{test_run_races,test_budget}.py。
**Interfaces:** `RunService.submit(actor,conversation_id,topic_id,client_message_id,question,scope)->RunReceipt`、`switch_topic(actor,...,expected_epoch)->TopicSnapshot`、`cancel(actor,run_id,expected_revision)->RunSnapshot`、`revise(...)->RunReceipt`；`claim(run_id,owner)->RunFence|None`、`renew(fence)->bool`、`reserve(fence,kind:{model,query,repair})->RemainingBudget`；`require_fence(conn,fence)->None`。

- [ ] Step 1 两连接20路相同key仅1message/1run，epoch仅推进1；不同正文409；A→B→A和两个页面CAS；token过期但未新claim也拒绝，B新token后A拒绝；恢复次数/预算不归零，后台A不以active_topic/epoch失效。
- [ ] Step 2 `pytest T/integration/test_run_races.py T/integration/test_budget.py -q`先RED。
- [ ] Step 3 消息/epoch/run/事件同事务，回执持久且不可变。状态QUEUED/RUNNING/WAITING_INPUT/SUCCEEDED/FAILED/TIMED_OUT/CANCELLED/SUPERSEDED；DB轮询待领提示后按统一授权锁序条件领取，不持run锁反向拿user。lease/token/deadline用数据库时钟；预算预留与fence同事务。暂停保留剩余执行预算、清lease；恢复新token；修改原子取代旧run并新建thread。数据库唯一键保护逻辑幂等。
- [ ] Step 4 同命令GREEN；生成实际状态转换表；本项目按独立OpenAPI清单验收，不套用Java接口数量。
- [ ] Step 5 提交 `feat: persist scoped analysis runs idempotency leases and budgets`。

### Task 6: 真实只读查询、EXPLAIN门禁与查询证据

**Files:** Create S/query/{gateway,repository,evidence}.py, T/integration/test_query_gateway.py。
**Interfaces:** `QueryGateway.validate(actor,fence,sql,params)->QueryTicket(query_id,sql_hash,requires_approval,explain)`、`execute(actor,fence,query_id,approval_id=None)->EvidenceResult`。QueryTicket绑定run/revision/auth_version/catalog版本；EvidenceResult含columns/rows/count/truncated/hash/metric_ids/dataset_version。

- [ ] Step 1 真实PG角色测试：3秒timeout实际触发，1000行/1MiB限制、分母0/空集/金额精度、同池两租户、EXPLAIN两阈值、无批准不执行；注入查询完成后撤权，结果不返回也不入证据；旧run的query_id不可在新run执行。
- [ ] Step 2 `pytest T/integration/test_query_gateway.py -q`先RED。
- [ ] Step 3 validate使用readonly reader TX注入可信tenant、EXPLAIN FORMAT JSON并保存票据；execute重新加载原SQL/params和当前策略/权限，持久预留查询次数后在独立只读TX执行，statement_timeout=3000ms、只读、受管search_path。游标分批读取并限制行/字节；截断明确，报告不能把截断明细当全量汇总。结果返回前在state TX锁授权与run、核验fence并保存不可变证据，撤权丢弃内存结果。不跨模型/MCP网络持状态行锁。
- [ ] Step 4 同命令GREEN；直接通过reader连接验证写/越权确实被DB拒绝，不只验证Python拦截。
- [ ] Step 5 提交 `feat: execute bounded readonly analytics with evidence and explain gates`。

### Task 7: 受保护的真实MCP服务与客户端

**Files:** Create S/{mcp_server,mcp_client}.py, S/auth/tool_tokens.py, T/integration/test_mcp_http.py。
**Interfaces:** SDK2.3.0 `MCPServer`注册list_datasets/get_metric_definition/validate_query/execute_query四工具；`DataToolsClient.call(fence,name,arguments)->ToolResult`。工具schema不包含tenant、user、DSN、auth_version或执行token。

- [ ] Step 1 启动真实随机回环HTTP MCP，SDK Client握手并调用四工具；无token401、错误aud/过期/篡改拒绝、缺scope403、伪造租户参数拒绝、token签发后撤权拒绝；并发两个身份不串ContextVar。禁止用内存或stdio测试冒充HTTP认证。
- [ ] Step 2 `pytest T/integration/test_mcp_http.py -q`先RED。
- [ ] Step 3 TokenVerifier+AuthSettings验证应用签发的60秒上下文，issuer/aud/scope/exp/jti固定策略；模型只给业务参数，当前Actor/Fence从已验证请求上下文取得。每工具数据库复核；调用仅固定本地MCP地址，超时/失败为结构化真实错误，协议重试不得重置查询预算。MCP为资源服务器，不开放任意用户签发服务令牌的接口。
- [ ] Step 4 同命令GREEN；客户端schema、工具名和错误契约固定，日志无Bearer/密码/原始SQL敏感参数。
- [ ] Step 5 提交 `feat: expose authenticated analytics tools through real MCP HTTP`。

### Task 8: 官方Postgres检查点的事务fence适配

**Files:** Create S/checkpoints.py, migrations/006_checkpoint_guard.sql, T/integration/test_fenced_checkpoints.py。
**Interfaces:** `FencedPostgresSaver(BaseCheckpointSaver)`公共aget_tuple/alist/aput/aput_writes/get_next_version接口；`checkpoint_scope(actor,fence,thread_id)`提供服务端可信上下文。运行只支持普通StateGraph通道；删除/prune/未封装写接口拒绝，不能回落到无fence写入。

- [ ] Step 1 用真实官方saver+PG：写后断连重读；A lease到期无B时写拒绝；B新token保存后A迟到checkpoint及pending writes均0；在fence检查/写之间设事务闸门，证明B不能跨过持有的同一run锁；撤权前后两种顺序和外层rollback覆盖checkpoint/blobs/writes。P03纳入。
- [ ] Step 2 `pytest T/integration/test_fenced_checkpoints.py -q`先RED，不能mock saver为内存字典。
- [ ] Step 3 官方setup仅在迁移时autocommit+dict_row、固定checkpoint schema执行。运行适配组合官方AsyncPostgresSaver：每次写从checkpoint池取连接，在外层transaction内执行固定SECURITY DEFINER guard函数，按user→run锁序复核可信thread/租户/revision/token/未到期lease/当前授权，再将同连接交给官方aput/aput_writes，外层提交后才释放。guard函数固定search_path、只授权checkpoint角色EXECUTE；该角色不能任意改state。开启LANGGRAPH_STRICT_MSGPACK、禁止pickle。读取也校验thread归属和当前权限；审计锁定版本实际被图调用的所有写接口，未知写路径失败关闭。
- [ ] Step 4 同命令GREEN；真实LangGraph小图跨进程恢复仍能读官方checkpoint，旧执行写数0。
- [ ] Step 5 提交 `feat: fence official LangGraph checkpoints in the same database transaction`。

### Task 9: Agent状态图、口径确认、SQL修正和证据报告

**Files:** Create S/agent/{state,graph,nodes,model,prompts}.py, S/reports/{models,builder,charts}.py, S/runs/confirmations.py, T/integration/{test_agent_graph,test_hitl}.py, T/unit/test_report_evidence.py。
**Interfaces:** `build_graph(model:AnalysisModel,tools:DataToolsClient,saver:FencedPostgresSaver)->CompiledStateGraph`；`AnalysisModel.plan(context:PlanningInput)->PlannedAnalysis`、`sql(step:QueryStep,context:SqlContext)->GeneratedQuery`、`describe(facts:ReportFacts)->ReportNarrative`返回严格DTO并单独保存实际usage；`ConfirmationService.resume(actor,run_id,confirmation_id,decision,key)->RunReceipt`；`ReportBuilder.build(evidence:Sequence[EvidenceResult],claims:Sequence[ReportClaim])->Report`。

- [ ] Step 1 可控HTTP供应商+真实MCP/PG执行目录→澄清interrupt→plan→SQL→policy→execute→validate→report；可修复语法错误最多2次，权限拒绝不修复重试。HITL绑定run/revision/SQL hash/auth_version/预算/checkpoint摘要，改SQL/撤权/旧确认409、重复批准只生效一次、新消息不批准。报告未知证据/数字不一致拒绝，空结果不补造值。
- [ ] Step 2 `pytest T/integration/test_agent_graph.py T/integration/test_hitl.py T/unit/test_report_evidence.py -q`先RED。
- [ ] Step 3 实现StateGraph真实节点和LangGraph interrupt/Command；确认前后的节点可重执行，工具调用证据/票据幂等，禁止在interrupt前产生不可重复外部动作。ChatOpenAI对接百炼，max_retries=0，统一预算/timeout外壳预留每次调用；解析失败、实际usage缺失如实保存。模型只输出公开计划、SQL和有证据ID的解释，不保存思考链。Pandas核对金额/占比/分组总量、SQL粒度；报告数字及图表由证据生成，模型文字中的数值声明必须匹配证据。Plotly首版仅白名单bar/line/table及允许字段，禁止可执行内容/任意HTML。
- [ ] Step 4 同命令GREEN；图实际进入WAITING_INPUT后释放lease，显式原run恢复后剩余预算不变；模型错误永不变成伪造成功。
- [ ] Step 5 提交 `feat: orchestrate evidenced analytics with LangGraph human review and bounded repair`。

### Task 10: Worker、资源隔离、硬时限与恢复

**Files:** Create S/{worker,admission}.py, S/runs/recovery.py, T/system/test_execution_recovery.py。
**Interfaces:** `AnalysisWorker.run_once(execution_class)->bool`；`Admission.acquire(class,owner)->Permit|None`；`RecoverySweep.run_once()->RecoveryStats`。模型槽与执行槽独立、Redis原子准入、PG持久任务轮询权威。

- [ ] Step 1 两个真实Worker进程：旧进程暂停→lease到期→新token恢复→旧进程恢复零checkpoint/报告写；退出在SQL后checkpoint前可恢复且查询总预算不超限；Redis取消提示丢失仍不复活。后台占1槽时前台在受控供应商下<=1秒获得执行/模型槽，总active<=2、background<=1。
- [ ] Step 2 `pytest T/system/test_execution_recovery.py -q`先RED并确认每个真实故障命中；缺Docker明确失败，不skip冒充通过。
- [ ] Step 3 独立轮询/续租/回收/通知调度，执行不阻塞续约。取消/撤权/超时持久终态、阻止下一工具与提交；auto最多3次5/30退避，deadline/累计预算不重置。槽owner+到期续期丢失停止后续动作；数据库短暂故障不丢任务、不伪造成功；Redis故障不绕过准入。BG不以active_topic变化取消，FG按epoch失效。
- [ ] Step 4 同命令GREEN；记录实际PID、token变化、故障命中、重复执行与最终报告数，不宣称外部模型恰好一次。
- [ ] Step 5 提交 `feat: recover fenced analytics workers with reserved interactive capacity`。

### Task 11: React问数工作台、任务记录与确认交互

**Files:** Create frontend/src/{api,auth,workbench,history,catalog,components}/，S/runs/events.py，T/integration/test_api_contracts.py，frontend/src/workbench/*.test.tsx，docs/contracts.md。
**Interfaces:** HTTP conversations/topics/messages、runs/{id}/cancel/revise/resume/report/evidence、metrics/datasets及run events SSE。事件`seq/run_id/topic_id/task_revision/execution_version/scope`必带；生成OpenAPI TypeScript。SSE仅阶段/公开计划/安全工具摘要/完成事件，不把partial输出作正式报告。

- [ ] Step 1 组件测试同key网络重试、双页面409、旧HTTP快照/旧SSE不倒退；A后台中切B→A完成仅A任务卡片更新，B发送“同意”不恢复A。HITL展示指标定义/SQL摘要/预算和明确批准按钮；失效确认不可点，取消与切话题分别操作。
- [ ] Step 2 `npm --prefix frontend test -- --run`及`pytest T/integration/test_api_contracts.py -q`先RED。
- [ ] Step 3 实现登录、问数工作台、阶段/证据/表格/Plotly图、分析记录、指标目录、管理员成员管理；安全渲染所有模型字符串，不dangerouslySetInnerHTML。复制现有通用视觉组件时仅引入实际依赖，不带ServiceHub业务或个人资料。HTTP快照序号与SSE游标分离；断线以持久事件补放，轮询作为恢复；原run确认必须精确指定。表格/图/报告导出保留证据和数据版本。
- [ ] Step 4 组件与API契约GREEN、typecheck/build通过，生成类型无漂移；真实浏览器核对各页面、等待确认和后台任务切换。
- [ ] Step 5 提交 `feat: deliver analytics workbench with scoped progress evidence and confirmations`。

### Task 12: 金标准评测、P01–P03故障验收与真实发布

**Files:** Create evals/run.py, evals/gold_cases.json, T/system/test_topic_and_hitl.py, docs/{architecture,operations,key-source-walkthrough,evaluation}.md, .github/workflows/verify.yml, scripts/{verify,online_check}.ps1/py, README.md；简历仅在用户Desktop原目录更新并保留证件照。
**Interfaces:** `uv run python -m evals.run --mode controlled|online --report PATH`；只输出真实评测结果，不预设成功率。报告记录dataset/schema/metric版本、模型实际名称、源提交、query/evidence匹配、口径确认、Token usage、p50/p95、失败/拒绝原因。

- [ ] Step 1 先写20题gold对比及P01–P03：背景A切B后合法提交A；批准变更/重放/撤权拒绝；旧worker checkpoint防旧写和同池tenant清理。另含危险SQL/提示注入/超时/超预算/依赖故障/连接中断，每个注入有命中证据。
- [ ] Step 2 单独运行新增用例观察RED；不能以只有工具/静态查询通过替代整个Agent真实生成链路。
- [ ] Step 3 实现评测器、CI真实PG角色/Redis/MCP和确定性供应商，完整后端+前端+容器构建。修复定向回归后做一次最终完整门禁、独立整分支审查。操作文档包括账号、备份/恢复、版本、并发边界、指标口径与面试源码说明，架构图必须对应实际进程。
- [ ] Step 4 启动独立Compose，通过真实百炼执行20题与澄清/高成本确认/取消/恢复；只使用明确合成数据并报告实际错误/费用usage。不以高成本确认绕过硬上限；报告必须引用实际证据。验收完成才扫描并发布GitHub源码和介绍，保留已有公开历史，不推私有开发历史、env或个人材料；确认远程CI。
- [ ] Step 5 使用documents技能更新简历项目与技能，保留原证件照/AI全栈标题/研究，按真实评测写项目成果；渲染逐页核对并备份原文件。提交 `docs: deliver verified InsightPilot agent evaluation and operational handover`，核对所有Task及P01–P03退出条件。

## 自查与官方依据

- 设计定位/独立部署/缓存边界→1；经营数据/指标/真实角色RLS→2；身份撤权→3；SQL→4/6；幂等/任务归属/预算→5；MCP→7；官方checkpoint/fencing→8；Graph/HITL/证据报告→9；资源/恢复→10；UI/流/话题→11；20题/在线/故障/交付→12。
- Review Focus五类均有具名测试，P01→10–12、P02→9/11/12、P03→2/8/10/12。测试与错误注入必须证明命中，不填空skip。
- 术语统一Actor/RunFence/QueryTicket/EvidenceResult/RunReceipt；checkpoint writer与state授权检查在同一连接事务，query readonly连接不能写checkpoint。
- 官方版本元数据已读取并存于R/.cache/insightpilot-planning/official-package-metadata.json；锁解析失败必须报告冲突并调整明确版本记录，不能以latest/未锁依赖代替。
- [LangGraph interrupt与恢复](https://docs.langchain.com/oss/python/langgraph/interrupts)、[官方Postgres saver](https://github.com/langchain-ai/langgraph/tree/main/libs/checkpoint-postgres)、[checkpoint严格反序列化](https://pypi.org/project/langgraph-checkpoint-postgres/)。
- [MCP Python SDK授权](https://github.com/modelcontextprotocol/python-sdk/blob/main/docs/run/authorization.md)、[PostgreSQL invoker/schema权限](https://www.postgresql.org/docs/18/sql-createview.html)、[事务局部SET](https://www.postgresql.org/docs/18/sql-set.html)。

状态：2026-10-25用户已审阅并回复“按这版计划实施”。执行方式保持当前会话逐项实施；Java最终门禁通过后进入Task 1。尚未实现InsightPilot代码，不写入简历为已完成项目。
