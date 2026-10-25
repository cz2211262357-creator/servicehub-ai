# ServiceHub AI Java Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可独立运行的 Spring Boot / React 客服平台，保留现有业务，增加可靠 RabbitMQ 任务和可验证的对话打断、话题隔离及恢复能力。

**Architecture:** 模块化单体 API 与独立 Worker 共用 Java 代码及 MySQL，RabbitMQ 仅承担任务投递，Redis 承担会话、限流、向量检索与通知。业务提交、取消与执行权以数据库为准；模型和网络调用在事务外执行。React 复用现有页面，在原 WebSocket 协议上扩展话题与执行标识。

**Tech Stack:** JDK 21 LTS、Maven Wrapper 3.9.11、Spring Boot 4.1.1、Spring AI 2.0.1、MyBatis-Plus 3.5.17 Boot 4 starter、MySQL 8.4、Redis 8.4、RabbitMQ 4.3.6-management、Spring Security / AMQP / Flyway / Actuator、JUnit / Testcontainers、现有 React / TypeScript 锁定依赖。

**Spec:** `D:/研究生/简历1/qz-project/docs/superpowers/specs/2026-10-22-servicehub-java-design.md`；必读 `../specs/2026-10-22-conversation-interruption-design.md`、`../specs/2026-10-22-execution-consistency-design.md`。验收矩阵为 `../../verification/java-contract-matrix.md` 与 `../../verification/java-concurrency-acceptance.md`；后者 A01–A20 是 Java 退出条件，P01–P03 留给独立 Agent。

**Execution:** 用户已选择当前会话逐项实现，使用 superpowers:executing-plans。本文只计划 Java 主线；独立 InsightPilot 的具体实施计划在 Java 验收后编写，其打断、话题与检查点要求已经写入设计。

## Global Constraints

- 所有由项目直接管理的源码、工具、构建缓存、测试输出和持久数据留在 `D:/研究生/简历1/qz-project` 内；不改全局 JDK、Maven 或 Python 配置。Docker 引擎的全局镜像/层/构建缓存/虚拟磁盘属于引擎存储例外，不宣称均位于 R，也不改用户全局 Docker 设置。
- 执行开始时读取 using-git-worktrees；从已提交设计所在 HEAD 建立 `.cache/worktrees/servicehub-java/` 隔离工作树、分支 `codex/servicehub-java`，已有同名分支/目录先核对绝对路径、common-dir、分支、HEAD、工作区和使用归属，只有匹配才复用；不覆盖/reset 未提交内容。本文后续相对路径均相对于该工作树 W。
- 工具和运行数据的物理根 R 固定为原项目根，不能误用 W 的嵌套位置。工具 `R/.tools/`；Maven 调用显式 `-Dmaven.repo.local=R/.cache/m2`；Wrapper 设置 `MAVEN_USER_HOME=R/.cache/maven`；临时目录 `R/.cache/java-tmp/`；数据 `R/.data/java/`。
- Java Compose 项目名 `servicehub-java`；仅回环绑定 Web 8081、API 8001、MySQL 3307、Redis 6381、RabbitMQ 5673、管理界面 15673。集成测试使用随机映射端口与独立测试数据，不访问旧库。
- 保留 39 个原 HTTP 接口和 1 个 WebSocket，新增 3 个停止/话题接口，总计 42 个 HTTP 接口。保留 snake_case、admin/agent/customer、Cookie / CSRF、原金额/UTC 时间格式和错误结构，不能替换为 JWT 或响应套壳。
- 单次上传最多 10 MiB；Embedding 为 text-embedding-v4 / 1024；对话默认 qwen-plus，生产运行没有固定回答降级。
- 项目全局执行并发 4、模型请求并发 4，两层 BACKGROUND 均最多 3，保留至少 1 个 INTERACTIVE 名额，多 Worker 共用原子准入；任务租约 60 秒、续期 10 秒；消息限流每用户每 60 秒 30 次。单次模型 timeout=min(45秒,remaining)；前台排队上限60秒，从首次成功领取起持久化120秒硬deadline，分类/资源等待/工具/供应商重试/退避/故障恢复均计入，自动重试不重置。导入排队/执行各600秒。最多4次工具执行、最多输出1600 Tokens；只在规则无法决定时至多一次分类并计入总预算。模型最多 6 次请求，输入每次最多 24000 字符，预算用尽明确终止。
- 每批最多3次总执行尝试（含首次及租约恢复），前两次失败后退避5/30秒，权限/取消/永久错误不重试；人工重试是显式新预算批次，单独计数并审计，不能复活已取代或取消的前台run。队列、数据库与供应商无法保证模型恰好调用一次；只承诺受保护的业务提交。
- 对话上下文仅用本topic已持久化、routing_state=RESOLVED且context_status=ELIGIBLE的消息与重验事实，总计最近20条/16000字符；取消/partial/失败输出排除。同topic换目标清空旧业务槽位；跨topic只允许显式重新授权的对象引用，不能继承确认。
- HTTP/WS 权限从服务端会话推导；所有查询含租户与业务对象归属。网络、Embedding 和模型调用不持有 MySQL 业务行锁。
- `.env`、`.env.java`、上传内容、真实个人信息、日志与凭证不可进入 Git；公开前重新扫描。旧 Python 环境与公开版本保留，Java 未通过验收前不改简历技术栈。
- 下列类型中的时间统一 `Instant`、金额 `BigDecimal`；DATETIME(6)由显式UTC TypeHandler转换LocalDateTime/Instant、截断微秒，JDBC会话UTC，不依赖JVM默认时区；金额DECIMAL(12,2)，超精度/越界拒绝，JSON定点字符串，显式统计舍入HALF_UP。复杂事务用明确 SQL 和行锁，避免泛用 CRUD 绕开并发条件。

## Review Focus

1. 同一 client_message_id 的网络重试恰逢新回答生成：不得再次推进 epoch、取消新任务或创建第二条消息（任务 4、7、13）。
2. 两个页面同时停止/切换，或 A→B→A 时旧分片和旧分类迟到：旧结果不能切换话题、显示或提交；冲突明确（任务 7、10、11、13）。
3. 人工接管、成员禁用或文档停用发生在模型/工具处理中：返回前与提交前复核，原权限不能被恢复检查点继承（任务 3、9、10、13）。
4. RabbitMQ 已接收而 confirm 丢失、Worker 退出或 Redis 通知丢失：逻辑任务可恢复，已取消任务不会复活，旧执行不能写正式结果（任务 6、7、13）。
5. 不同 topic 的相似订单/条件及旧确认卡片：模型上下文不能串话题；有效业务草稿仍可显式办理，Agent 的旧执行确认不能自动复用（任务 5、7、10、11）。

## 文件结构与运行命令

Java 包根为 `backend-java/src/main/java/cn/servicehub/`（下文 J），测试根为 `backend-java/src/test/java/cn/servicehub/`（下文 T），资源根 `backend-java/src/main/resources/`（下文 S）。表设计按现有 18 张业务表逐项映射，另外增加 topic 与停止幂等表，不删除旧项目文件。

| 路径 | 职责 |
|---|---|
| `backend-java/pom.xml`, `.mvn/`, `mvnw.cmd` | 锁定构建、依赖 BOM、Surefire / Failsafe |
| `J/ServiceHubApplication.java`, `J/config/`, `J/common/` | 应用入口、类型、配置、请求 ID 与错误 |
| `J/auth/`, `J/conversations/`, `J/orders/`, `J/aftersales/` | 权限与业务事务 |
| `J/jobs/`, `J/realtime/` | 事务消息、执行租约、取消与事件回放 |
| `J/knowledge/`, `J/ai/`, `J/operations/` | 私有知识、检索、模型工具和运维 |
| `S/db/migration/`, `S/mapper/` | Flyway SQL 和 MyBatis 明确 SQL |
| `T/support/`, `T/contract/`, `T/system/` | 测试依赖容器、HTTP 合约、多进程故障 |
| `scripts/java-env.ps1`, `scripts/bootstrap-java.ps1` | 项目内工具与环境，不输出密钥 |
| `scripts/java-system-tests.ps1`, `scripts/java-online-check.ps1` | 独立故障验收与真实供应商验收 |
| `compose.java.yaml`, `.github/workflows/java-ci.yml` | 本地隔离环境与 CI |
| `frontend/src/api/`, `frontend/src/features/portal/`, `frontend/src/features/inbox/` | 新类型、流过滤和话题控制 |

Wrapper 与 Maven 依赖库是两种缓存；下面所有 Maven 命令由 java-env.ps1 通过当前进程 MAVEN_ARGS 注入绝对 `-Dmaven.repo.local=R/.cache/m2` 与 settings 路径，并实际验证。每次新终端从 W 执行 `. ./scripts/java-env.ps1 -WorkspaceRoot 'D:/研究生/简历1/qz-project'`，仅设置当前进程环境。常规命令：

- 单元测试：`./backend-java/mvnw.cmd -f backend-java/pom.xml "-Dtest=测试类名" test`。
- 真实依赖集成测试：`./backend-java/mvnw.cmd -f backend-java/pom.xml "-Dit.test=测试类名IT" verify`。Surefire 仅运行 `*Test`，Failsafe 运行 `*IT`，失败必须使 verify 非零。
- 完整离线供应商验证：`./backend-java/mvnw.cmd -f backend-java/pom.xml verify`；这里仍使用真实 MySQL / Redis / RabbitMQ，只有供应商使用可控 HTTP 替身。
- 前端：`npm --prefix frontend ci`，随后 `npm --prefix frontend run test -- --run`、`run typecheck`、`run build`。复用 lock，不无关升级。
- 每项提交只暂存本项列出的文件；Git 身份用命令级 `-c user.name=陈震 -c user.email=254845121+cz2211262357-creator@users.noreply.github.com`，不改全局设置。

## 共用类型边界

类型均在各自包内单独文件。任务1同时提供`ai.CancellationGuard.check(): void`函数接口和`ai.ExecutionCancelledException`；任务 1 提供 `common.Actor(long tenantId,long userId,Role role,long authVersion)`、`auth.Role {ADMIN,AGENT,CUSTOMER}`（序列化原小写值）、`common.BusinessException(String code,String message,int status,Map<String,Object> details)`。

任务 2 提供 `jobs.JobCommand(long tenantId,String kind,ExecutionClass executionClass,String businessKey,Map<String,Object> payload)`、`jobs.JobRef(long id,String state)`；任务2同时提供 `jobs.ExecutionClass {INTERACTIVE,BACKGROUND}`；任务 6 提供 `jobs.JobLease(long jobId,long tenantId,String owner,long executionVersion,Instant expiresAt)`。任务 7 提供 `conversations.ExecutionFence(long tenantId,long conversationId,long topicId,long runId,long generationEpoch,long executionVersion)`。

DTO 以冻结的 JSON Schema 为依据定义在业务包 `*Dtos.java`；方法中的 `ConversationDto`、`MessageDto`、`DraftDto`、`TicketDto`、`DocumentDto` 分别归属对应业务包，不再另造同义返回类型。数据库 Mapper 只供包内事务服务调用；跨模块使用下列 Interfaces。

---

## 审查后必须遵守的执行语义

消息判重、epoch推进、旧前台失效、不可变触发关联、run/job/outbox/event全在同一事务。后台导入不绑定前台epoch；独立Agent后台报告用自身task_revision而不是active_topic判断。用户权限行与对象版本锁、数据库租约token CAS必须与写结果处于同一事务，锁顺序为授权用户（升序）→会话/topic→业务资源→run→job→outbox。旧Worker即使token未被更新，只要lease已过期也不能写。具体状态转换与人工重试例外以一致性规范第3–5节为准。

原Python自动最多4次改为3次是用户此次审查要求；自动退避改5/30秒；stale/cancelled前台admin retry明确409；新增话题字段和waiting_input状态为加法扩展。以上差异单列D01–D03，不能让契约测试假装完全相同或静默忽略。

### Task 1: 可复现构建、健康接口与统一 HTTP 基础

**Files:** Create `backend-java/pom.xml`, `backend-java/mvnw`, `backend-java/mvnw.cmd`, `backend-java/.mvn/wrapper/maven-wrapper.properties`, `backend-java/.mvn/maven.config`, `backend-java/.mvn/settings.xml`, `J/ServiceHubApplication.java`, `J/config/ServiceHubProperties.java`, `J/common/{Actor,BusinessException,ApiErrorAdvice,RequestIdFilter,HealthController}.java`, `J/auth/Role.java`, `J/ai/{CancellationGuard,ExecutionCancelledException}.java`, `S/application.yaml`, `T/support/DependencyContainers.java`, `T/contract/HealthContractTest.java`, `scripts/{bootstrap-java,java-env}.ps1`, `compose.java.yaml`; Modify `.gitignore`。

**Interfaces:** Produces `ServiceHubProperties`：workspaceRoot、JDBC / Redis / AMQP 连接、模型端点与 Secret 配置、Global Constraints 中各限制；`HealthController.liveness(): Map<String,String>`、`readiness(): ResponseEntity<Map<String,Object>>`。根健康路径和JSON从现有main.py固定：`/api/v1/health/live` 与 `/api/v1/health/ready`。

- [x] Step 1：先写 `HealthContractTest`：健康返回原路径/字段；校验错误为 422 且含 error.code/message/request_id；依赖未就绪不能报 ready；日志不含测试密钥。仅先安装可运行该测试的最小 Maven/JUnit 引导。
  关键断言：`validationUsesBaselineEnvelope()`：`assertThat(response.statusCode()).isEqualTo(422)`；`assertThat(errorFields).contains("code", "message", "request_id")`。

- [x] Step 2：运行 `-Dtest=HealthContractTest test`，记录缺少 Controller/错误处理导致的失败；不可将构建无法解析依赖当成行为测试的 RED。
- [x] Step 3：实现上述入口和配置；bootstrap 下载官方 JDK 21 与 Maven Wrapper，核验 SHA256 并记录来源/版本。Compose 锁定三个镜像及 digest，API/Worker 通过 profile 选择，暂不伪造业务接口。设置项目缓存与TEMP在R；Compose明确127.0.0.1发布，持久数据bind mount到R/.data/java；Testcontainers使用现有引擎的随机端口，getHost/getMappedPort取得真实连接，不修改旧容器。Docker层/镜像例外单列。依赖 BOM 实际解析必须成功。
- [x] Step 4：同命令通过；`dependency:tree` 无冲突且没有预发布依赖；`docker compose -p servicehub-java -f compose.java.yaml config --quiet` 通过；不输出展开后的凭证配置。
- [x] Step 5：暂存本项文件并提交 `build: bootstrap isolated Java service and health contracts`。

### Task 2: 数据库结构、事务任务入口与合约基线

**Files:** Create `S/db/migration/V1__business_schema.sql`, `S/db/migration/V2__topics_and_interruptions.sql`, `S/db/migration/V3__execution_authorization_and_budgets.sql`, `J/jobs/{JobCommand,JobRef,ExecutionClass,JobJournal,JobMapper}.java`, `S/mapper/JobMapper.xml`, `J/config/PersistenceConfig.java`, `T/db/{SchemaIT,NumericTimeContractIT}.java`, `J/common/UtcInstantTypeHandler.java`, `T/jobs/JobJournalIT.java`, `scripts/freeze-servicehub-contract.py`, `contracts/python-v1/{openapi.json,http-contracts.json,websocket.json}`。

**Interfaces:** Produces `JobJournal.enqueue(JobCommand command): JobRef`（必须加入调用方事务，不启动独立事务）；`JobJournal.cancelForConversation(long tenantId,long conversationId,long beforeEpoch): int`。所有 payload、事件及 DTO 的 JSON 用配置的 snake_case 映射；金额与日期按冻结样本校验；payload含服务端actor/auth_version和scope，不能由broker参数覆盖。

- [x] Step 1：写 `SchemaIT`：跨租户复合外键失败、同幂等键冲突；NumericTimeContractIT覆盖三种JVM时区/DST、UTC微秒往返、DECIMAL(12,2)与JSON金额字符串、超精度/溢出拒绝；写 `JobJournalIT`：业务事务回滚后 job/outbox 均为零，同 businessKey 得到同逻辑任务。
  关键断言：`rollbackRemovesJobAndOutbox()`：`assertThat(jobCount).isZero()`；`assertThat(outboxCount).isZero()`。

- [x] Step 2：运行 `-Dit.test=SchemaIT,NumericTimeContractIT,JobJournalIT verify`，期望因缺表/缺事务语义失败。
- [x] Step 3：建立现有18张表及topic/停止记录、复合外键与唯一键；复用users.session_version作为Java授权版本（Actor.authVersion），新增message不可变触发关联/context_status、run业务槽位/预算计数、job执行类别/started_at/deadline_at/budget_revision/lifetime_attempts；Agent草稿增加来源run/topic/epoch/budget_revision/action_hash，唯一(tenant_id,origin_run_id,origin_budget_revision,action_hash)，人工草稿来源可空。实现显式UTC TypeHandler与JDBC会话UTC。实现 JobJournal；使用已导出的docs/verification/servicehub-python-openapi-2026-10-22.json（SHA256见接口矩阵）冻结39个HTTP的method/path/schema，加上新增 3 项扩展文件与 WS 事件样本；只导出 Schema，不导出真实数据。Flyway 正式运行不能依赖 ORM 自动建表。
- [x] Step 4：同命令通过；空库迁移和再次迁移均通过；基线统计原 39、新增 3、WS 1，后续变化显式审查。
- [x] Step 5：提交 `feat: add Java business schema and transactional job journal`。

### Task 3: Cookie 会话、CSRF、租户隔离与成员管理

**Files:** Create `J/auth/{AuthDtos,AuthController,AuthService,AuthorizationService,SessionStore,SecurityConfig,MemberService,MemberMapper}.java`, `S/mapper/MemberMapper.xml`, `T/auth/{AuthContractIT,MemberRevocationIT}.java`。

**Interfaces:** Produces `AuthService.authenticate(String sessionId): Actor`（每次确认用户仍启用）；`AuthService.requireConversationAccess(Actor actor,long conversationId): void`；`AuthorizationService.requireForWrite(Actor): void`要求已有事务并锁当前授权行，校验enabled/role/auth_version；`SessionStore.create(Actor actor): SessionDto`、`revokeUser(long tenantId,long userId): void`。其余 6 个 HTTP 输入输出逐项由基线声明。

- [x] Step 1：写 AuthContractIT：正确登录、错误密码统一拒绝、HttpOnly Cookie、CSRF 缺失/错误、204 退出、跨租户 404；MemberRevocationIT：已登录用户被禁用/降权后旧会话即失效，不能靠 Redis 旧数据继续请求。
  关键断言：`disabledUserCannotReuseCookie()`：`assertThat(nextRequest.statusCode()).isEqualTo(401)`。

- [x] Step 2：运行 `-Dit.test=AuthContractIT,MemberRevocationIT verify`，期望接口或权限断言失败。
- [x] Step 3：实现随机 256 bit opaque session、恒定安全比较与密码哈希；Cookie 名 `servicehub_java_session`、Redis 前缀 `shj:`、TTL 28800 秒。写请求需匹配会话 CSRF；生产显式 HTTPS/Cookie Secure，Origin 白名单；session 决定租户。安全错误沿用统一 error 结构，成员变更事务内增加session_version（逻辑auth_version）并按稳定用户锁顺序写审计；写服务统一调用requireForWrite，避免TOCTOU。测试管理员先撤销/业务先持锁两种事务顺序，不能只测试请求开始前禁用。
- [x] Step 4：同命令通过；测试 Redis 故障时鉴权拒绝并给可识别依赖错误，不能绕过认证。
- [x] Step 5：提交 `feat: secure Java sessions and tenant member administration`。

### Task 4: 会话、订单、消息幂等与事件事务

**Files:** Create `J/conversations/{ConversationDtos,ConversationController,ConversationService,ConversationMapper,EventStore}.java`, `J/orders/{OrderDtos,OrderController,OrderService,OrderMapper}.java`, `S/mapper/{ConversationMapper,OrderMapper}.xml`, `T/conversations/{ConversationContractIT,MessageRaceIT}.java`, `T/orders/OrderAccessIT.java`。

**Interfaces:** Consumes Actor、AuthService、JobJournal；Produces `ConversationService.send(Actor actor,long conversationId,String clientMessageId,String body): MessageDto`、`get(Actor,long): ConversationDto`、`lock(Actor,long): ConversationDto`（MANDATORY事务，FOR UPDATE）；`OrderService.get(Actor,String orderNumber): OrderDto`、`lockByNumber(Actor,String orderNumber): OrderDto`（MANDATORY事务）；`listForCustomer(Actor,long customerId,int limit): List<OrderDto>`；`EventStore.append(long tenantId,long conversationId,String kind,Map<String,Object> payload): long` 加入现有事务。

- [x] Step 1：写 10 个会话和 2 个订单 HTTP 合约测试；MessageRaceIT 20 并发相同消息仅 1 message/1 job，内容不同 409；旧client ID重试不推进epoch，返回原trigger_run_id/trigger_job_id/accepted_epoch；20 名客服抢单仅一人成功；当前客户不能读取其他客户订单。
  关键断言：`twentyRetriesCreateOneMessageAndJob()`：`assertThat(messageCount).isEqualTo(1)`；`assertThat(jobCount).isEqualTo(1)`；`assertThat(epochAfterOldRetry).isEqualTo(epochBeforeOldRetry)`。

- [x] Step 2：运行 `-Dit.test=ConversationContractIT,MessageRaceIT,OrderAccessIT verify`，期望接口/幂等断言失败。
- [x] Step 3：实现游标分页、订单金额、抢单条件更新和状态机；会话→执行/任务统一锁顺序。新消息在同一事务先判重，再推进generation_epoch、仅使旧前台任务失效，写客户消息/不可变触发关联/AI run/job/outbox/event；确定路由直接RESOLVED，需要分类才PENDING；人工消息绑定当前 topic。人工接管、关闭推进 epoch 并阻止旧回答提交。
- [x] Step 4：同命令通过；人工接管与消息并发不产生 AI_ACTIVE 下的错误归属或两个赢家；事件 seq 每会话单调。
- [x] Step 5：提交 `feat: port conversation and order workflows with message idempotency`。

### Task 5: 售后草稿、明确确认与工单状态机

**Files:** Create `J/aftersales/{AfterSaleDtos,AfterSaleController,AfterSaleService,AfterSaleMapper}.java`, `S/mapper/AfterSaleMapper.xml`, `T/aftersales/{AfterSaleContractIT,ConfirmationRaceIT}.java`。

**Interfaces:** Produces `AfterSaleService.prepare(Actor,long orderId,String kind,String reason): DraftDto`、`confirm(Actor,long draftId,long version,String idempotencyKey): TicketDto`、`transition(Actor,long ticketId,long version,String status,String note): TicketDto`；Controller 承接原 6 个接口。

- [x] Step 1：写 6 接口合约和 20 并发确认→同一工单断言；覆盖同 key 不同输入 409、草稿过期、跨客户、状态越级、重复确认，以及切换话题后的有效草稿仍可由客户显式确认，不能自动提交。
  关键断言：`twentyConfirmationsReturnOneTicket()`：`assertThat(distinctTicketIds).hasSize(1)`；`assertThat(ticketCount).isEqualTo(1)`。

- [x] Step 2：运行 `-Dit.test=AfterSaleContractIT,ConfirmationRaceIT verify`，期望缺接口/重复工单断言失败。
- [x] Step 3：实现 draft 唯一工单约束、行锁/版本/订单归属核对和事件审计；权限和有效期沿用原服务。prepare 只产生草稿，模型工具没有 confirm 权限；确认页面必须展示具体订单和原因。
- [x] Step 4：同命令通过；取消聊天任务和已提交工单状态互不误伤。
- [x] Step 5：提交 `feat: port explicit after-sale confirmation and ticket transitions`。

### Task 6: RabbitMQ 投递、租约、重试与故障恢复

**Files:** Create `J/jobs/{JobLease,OutboxDispatcher,JobCoordinator,JobConsumer,JobHandler,WorkerConfiguration,JobRecoveryScheduler,AdmissionController,ExecutionPermit}.java`, `J/ai/GlobalModelSlots.java`, `T/jobs/{OutboxDeliveryIT,LeaseRecoveryIT,RetryPolicyIT,AdmissionIsolationIT}.java`; Modify `JobMapper.java`, `S/mapper/JobMapper.xml`, `S/application.yaml`, `compose.java.yaml`。

**Interfaces:** Produces `JobCoordinator.claim(long jobId,String owner): Optional<JobLease>`、`renew(JobLease): boolean`、`requireValid(JobLease): void`、`finish(JobLease,String state,Map<String,Object> result): void`；`JobCoordinator.load(JobLease): JobCommand`；`JobHandler.handle(JobLease lease): void`；`AdmissionController.tryAcquire(ExecutionClass,String owner): Optional<ExecutionPermit>`；ExecutionPermit实现AutoCloseable并校验owner、续期，执行准入总4/后台3，未获准入不claim不消耗尝试。`GlobalModelSlots.withPermit(ExecutionClass,Duration maxWait,Callable<T> action,CancellationGuard guard): T`同时提供模型总4/后台3的原子Redis准入与owner续期；通用取消接口已由任务1提供。终态提交/失败持久化必须在合法执行版本条件下完成；领域事务统一先锁领域对象再锁任务。

- [x] Step 1：写 OutboxDeliveryIT 模拟 broker 接收后 confirm 丢失，重复投递只产生一个合法执行；LeaseRecoveryIT覆盖续期失败、过期未重领、重领后旧owner迟到均不能完成；AdmissionIsolationIT验证三后台占执行/模型名额时前台≤1秒获得保留名额、多Worker计数不超总4/后台3；RetryPolicyIT 断言 3 次终态、5/30 秒退避、先落库再 ack、取消不重试。
  关键断言：`expiredOwnerCannotCompleteRecoveredJob()`：`assertThat(oldLeaseAccepted).isFalse()`；`assertThat(newExecutionVersion).isGreaterThan(oldExecutionVersion)`。

- [x] Step 2：运行 `-Dit.test=OutboxDeliveryIT,LeaseRecoveryIT,RetryPolicyIT,AdmissionIsolationIT verify`，期望投递/执行权断言失败。
- [x] Step 3：持久 direct exchange `shj.jobs`，持久 quorum 队列 `shj.ai`/`shj.ingest`，持久消息只传 job_id/schema_version；mandatory publish+confirm且未return后CAS对应outbox.lease_version标记DISPATCHED，迟到confirm不能覆盖新发布者；丢失可重复投递。manual ack，未知/终态重复消息安全 ack，坏协议消息拒绝进死信；业务重试由新 outbox 的 available_at 控制，避免 broker 无限重投。投递/租约续期/资源续期/取消/回收独立调度；交互/后台独立消费，不让后台预取耗尽前台listener；无资源以有界等待或持久延后处理，不忙循环重投。提交用owner/token/state/lease>DBnow的条件UPDATE，affected_rows=0整体回滚。
- [x] Step 4：同命令通过；真实 RabbitMQ 暂停时业务提交成功且 outbox 待投递，恢复后完成；测试时钟/短租约仅用于加速，生产默认仍为 60/10 秒。
- [x] Step 5：提交 `feat: execute durable RabbitMQ jobs with fenced leases and recovery`。

### Task 7: 停止、话题路由与隔离上下文

**Files:** Create `J/conversations/{ExecutionFence,TopicDtos,TopicController,TopicService,TopicMapper,TopicRouter,TopicDecision,ContextBuilder,ContextPolicy}.java`, `J/ai/CancellationGuardFactory.java`, `S/mapper/TopicMapper.xml`, `T/conversations/{InterruptionIT,TopicIsolationIT,TopicRoutingTest}.java`; Modify `ConversationDtos.java`, `ConversationService.java`。

**Interfaces:** Produces `TopicRouter.route(Actor,long conversationId,long messageId): TopicDecision`（显式操作/确定性追问优先；仅有歧义时调用分类模型，不携带模型授权）；`TopicService.resolve(JobLease,long messageId,TopicDecision): Optional<ExecutionFence>`、`requireCurrentForWrite(Actor,JobLease,ExecutionFence): void`（MANDATORY事务，调用方先锁授权/会话/资源，本方法最后锁run/job校验fence）；`interrupt(Actor,long conversationId,long expectedEpoch): ConversationDto`、`switchTopic(Actor,long conversationId,TopicSwitchCommand): ConversationDto`、`list(Actor,long): TopicPage`；`ContextBuilder.build(Actor,ExecutionFence): List<ContextMessage>`；`ContextPolicy.verifyReferences(Actor,ExecutionFence): VerifiedFacts`仅返回当前run有效业务对象，REVISE清旧目标、显式跨topic引用重新查询授权；`CancellationGuard.check(): void` 为绑定本次执行的函数接口，抛出不可重试的 ExecutionCancelledException；`CancellationGuardFactory.forRun(ExecutionFence,JobLease): CancellationGuard`、`forJob(JobLease): CancellationGuard` 负责创建数据库校验闭包。TopicSwitchCommand 和 TopicDecision 定义严格沿补充设计，ContextMessage 为 role/body/message_id/citations。

- [x] Step 1：写 InterruptionIT：重复旧消息不打断新执行，停止幂等、两页版本冲突、队列取消不调用 handler；TopicIsolationIT：A→B→A、订单不串上下文、跨租户话题拒绝、AMBIGUOUS 不调用业务工具、迟到分类不改变 active_topic_id；路由单测含五类输入，普通追问分类调用数为0；同topic换另一个订单而未给新编号必须澄清，取消/partial/失败消息不进入ContextBuilder。
  关键断言：`lateRouteCannotReplaceLatestTopic()`：`assertThat(activeTopicId).isEqualTo(latestTopicId)`；`assertThat(otherTopicOrderIds).doesNotContain(orderA)`。

- [x] Step 2：运行 `-Dtest=TopicRoutingTest test` 与 `-Dit.test=InterruptionIT,TopicIsolationIT verify`，期望新增接口/隔离断言失败。
- [x] Step 3：实现补充设计 3 个接口与持久取消，Redis 提示只加速；锁内只提交决策，不调用分类模型。显式动作优先，不明确等待选择；选项通过 pending_message_id 只解析最新未归属消息。历史仅来自当前topic白名单消息/有效事实，按context_status排除被取代指令/取消输出/partial；恢复前重验权限与业务状态，后台导入不绑定当前epoch；没有待处理问题时切换不自动执行旧工具。暂用测试替身注入 TopicRouter，真实分类绑定在任务 10。
- [x] Step 4：同命令通过；取消后刷新状态保持取消，Redis 通知丢失仍被数据库 guard 拒绝；list 上限 100 且只返回同会话记录。
- [x] Step 5：提交 `feat: isolate topics and persist conversation interruption`。

### Task 8: 私有知识上传、文档版本与真实 Embedding 导入

**Files:** Create `J/knowledge/{KnowledgeDtos,KnowledgeController,KnowledgeService,KnowledgeMapper,PrivateFileStore,DocumentParser,IngestionHandler,EmbeddingGateway}.java`, `J/ai/SpringAiEmbeddingGateway.java`, `S/mapper/KnowledgeMapper.xml`, `T/knowledge/{KnowledgeContractIT,IngestionIT,BackgroundScopeIT,PrivateFileStoreTest}.java`; Modify `pom.xml` 加入 BOM 管理的 PDF reader，`WorkerConfiguration.java` 注册导入 handler。

**Interfaces:** Consumes JobJournal、JobCoordinator；Produces `KnowledgeService.upload(Actor,String title,UploadInput file): DocumentDto`、`setEnabled(Actor,long documentId,long version,boolean enabled): DocumentDto`；`DocumentParser.parse(Path file,String mediaType): List<TextPage>`（TextPage 为 pageNumber/text）；`EmbeddingGateway.embed(List<String> texts,CancellationGuard guard): List<float[]>`；`IngestionHandler.handle(JobLease): void`。上传服务只接受经长度/媒体检测的 UploadInput，文件落在 R/.data/java/uploads 内。

- [x] Step 1：写原 7 个知识接口合约；IngestionIT 验证 TXT/MD/文本 PDF 页码与分块、1024 维、旧版本任务迟到不能激活、失败版本可重试；BackgroundScopeIT验证前台切topic/停止不取消导入、导入用户撤权则不能激活；PrivateFileStoreTest 验证路径穿越、伪 PDF、空文本、10 MiB 边界和越界拒绝。
  关键断言：`wrongDimensionDoesNotActivateVersion()`：`assertThat(embedding.length).isNotEqualTo(1024)`；`assertThat(activeVersionId).isEqualTo(previousVersionId)`。

- [x] Step 2：运行 `-Dtest=PrivateFileStoreTest test` 与 `-Dit.test=KnowledgeContractIT,IngestionIT,BackgroundScopeIT verify`，期望解析/权限/激活断言失败。
- [x] Step 3：实现 SHA256、私有随机文件名、正文解析、800 字符块/100 重叠（页码保留）；以BACKGROUND准入分批真实Embedding，600秒持久执行deadline；网络在数据库事务外。导入先写待激活分块与向量，再在合法租约、当前版本和权限条件下激活；失败/取消残留定期按版本清理。向量维数和 NaN/Infinity 立即拒绝，索引错误不能显示导入成功。接口不暴露文件物理路径。
- [x] Step 4：同命令通过；真实 Redis 向量写入可检索，PDF 只是扫描图片时明确 unsupported/empty-text，不能伪称 OCR 成功。在线供应商验证留任务 13。
- [x] Step 5：提交 `feat: ingest private versioned knowledge with Spring AI embeddings`。

### Task 9: 全文与向量混合检索、引用与权限复核

**Files:** Create `J/knowledge/{HybridRetriever,VectorIndex,CitationAuthorizer,Evidence}.java`, `T/knowledge/{HybridRetrievalIT,CitationAccessIT}.java`; Modify `KnowledgeMapper.java`, `S/mapper/KnowledgeMapper.xml`。

**Interfaces:** Produces `HybridRetriever.search(Actor actor,String query,int limit,CancellationGuard guard): List<Evidence>`；`Evidence(long documentId,long versionId,long chunkId,String filename,Integer page,String excerpt,double score)`；`CitationAuthorizer.requireReadable(Actor,long conversationId,List<Evidence>): void`、`openHistorical(Actor,long conversationId,long versionId): Resource`。模型引用 DTO 仍映射现有基线字段，内部 Evidence 不直接强加给前端。

- [x] Step 1：写 HybridRetrievalIT：中文关键词、语义同义问法、空结果、跨租户相同文本；先过滤当前有效版本再排名，RRF 候选顺序固定；CitationAccessIT 覆盖检索后停用/换版、历史引用本人可下载、他人同租户会话引用拒绝和历史文档权限变化。
  关键断言：`tenantFilterRunsBeforeFusion()`：`assertThat(resultTenantIds).containsOnly(actor.tenantId())`；`assertThat(disabledVersionReturned).isFalse()`。

- [x] Step 2：运行 `-Dit.test=HybridRetrievalIT,CitationAccessIT verify`，期望串租户/版本/引用断言失败。
- [x] Step 3：实现 MySQL ngram FULLTEXT 和 Redis HNSW COSINE 双路各取 30，RRF k=60、最终最多 6。两路均按 tenant 与当前有效 version 预过滤，合并后再次数据库授权；参数绑定，空查询不扫描全库。回答提交时锁定核对当前引用，历史下载依本人会话原始版本引用单独校验。
- [x] Step 4：同命令通过；Redis 检索故障返回可识别依赖异常，不把不完整结果伪装成完整混合检索；记录降级取舍但本期不静默降级。
- [x] Step 5：提交 `feat: retrieve authorized hybrid evidence with versioned citations`。

### Task 10: Spring AI、受控 Agent、主动取消与最终提交

**Files:** Create `J/ai/{ModelGateway,SpringAiModelGateway,SpringAiTopicRouter,SupportAgent,ToolRegistry,AgentResult,AIService,ModelBudget,AIMapper}.java`, `S/mapper/AIMapper.xml`, `T/ai/{ToolBoundaryTest,AgentBudgetTest,AgentCancellationIT,AICommitFenceIT,ProviderContractIT}.java`; Modify `WorkerConfiguration.java`, `TopicRouter` Bean绑定、`J/aftersales/AfterSaleService.java`。

**Interfaces:** `ModelGateway.stream(ModelRequest request,Consumer<ModelEvent> sink,CancellationGuard guard): ModelReply`；ModelRequest 为 messages/toolSchemas/remainingBudget，ModelReply 为 text/toolCalls/nullable actualUsage，ModelEvent 为 delta/toolCall/usage。`ToolRegistry.execute(Actor,ExecutionFence,String toolName,Map<String,Object> args,CancellationGuard guard): ToolResult`；`SupportAgent.execute(Actor,JobLease,ExecutionFence): AgentResult`；`AIService.handle(JobLease): void`、`commit(JobLease,ExecutionFence,AgentResult): boolean`；新增`AfterSaleService.prepareForRun(Actor,ExecutionFence,JobLease,String orderNumber,String kind,String reason): DraftDto`，在单一事务按授权→ConversationService.lock→OrderService.lockByNumber→TopicService.requireCurrentForWrite的顺序校验后插入/复用同action_hash草稿，禁止先写草稿后检查取消。AgentResult 为 body/evidence/draftId/toolTrace/nullable actualUsage；工具轨迹仅公开摘要，不记录内部思考链。ModelRequest/ModelReply/ModelEvent/ActualUsage/ToolCall 定义为 ModelGateway 的嵌套 record；ToolResult 定义为 ToolRegistry 的嵌套 record，其余使用单独文件。调用任务6的`GlobalModelSlots.withPermit(ExecutionClass,Duration,Callable<T>,CancellationGuard): T`为每次供应商请求获取可续期名额，不能在整轮持有槽位后嵌套申请而死锁。

- [x] Step 1：写 ToolBoundaryTest：只有 search_policy、lookup_order、prepare_after_sale，服务端注入 Actor，跨客户订单和未知字段拒绝；AgentBudgetTest覆盖4工具/6模型/120秒硬deadline/1600输出，重复领取和自动重试不刷新started_at/deadline/预留计数，45秒单调用取remaining较小值。AgentCancellationIT 用可控阻塞 HTTP 流精确制造第二条消息、停止、切话题，断言取消后没有后续工具调用。AICommitFenceIT：人工接管、权限撤销、停用引用、topic/epoch/lease/execution_version 不符均不得插入回答；仅合法结果插入一次。
  关键断言：`interruptBeforeToolPreventsCallAndCommit()`：`assertThat(nextToolCallCount).isZero()`；`assertThat(staleAiMessageCount).isZero()`；`assertThat(unknownUsage).isNull()`。

- [x] Step 2：运行 `-Dtest=ToolBoundaryTest,AgentBudgetTest test` 与 `-Dit.test=AgentCancellationIT,AICommitFenceIT,ProviderContractIT verify`，期望工具边界/取消/提交失败；ProviderContractIT 使用本地 HTTP 供应商替身验证分片工具参数、超时、错误与 usage 缺失，不使用固定产品答案。
- [x] Step 3：用 Spring AI 官方 OpenAI-compatible chat/embedding 客户端实现百炼适配，不引入 LangChain/LangGraph；接入规则优先的路由、必要时结构化分类、初次检索、工具回环及证据约束。ToolRegistry核对ContextPolicy对象白名单；创建草稿的工具写事务也必须验证授权和run fence，不能只保护最终回答。每次模型/工具/分片前检查取消，取消本地订阅与 HTTP，供应商不可取消时丢弃结果。全局并发槽以 Redis Lua 带 owner/续期保护，续期独立调度；丢失槽位取消执行，不允许偷偷超额继续。
- [x] Step 4：实现commit短事务：先锁授权用户，再会话/topic和引用资源，后run/job；同事务校验auth_version、AI_ACTIVE、topic、epoch、run、deadline和DB租约条件更新，保存消息/审计/事件及完成任务。管理员撤权与写入两种先后顺序必须满足一致性规范；旧lease的affected_rows=0使整个事务回滚。运行上述全部命令通过；模型调用/工具等待期间用并发请求证明会话行未被长事务锁住。真实返回 usage 缺失保持 null，不能估造。
- [x] Step 5：提交 `feat: run bounded Spring AI support agents with cancellation fences`。

### Task 11: WebSocket 回放、流过滤与话题交互界面

**Files:** Create `J/realtime/{LiveWebSocketHandler,LiveEvent,LiveEventHub,LiveSocketConfiguration}.java`, `T/realtime/LiveReplayIT.java`, `frontend/src/api/topics.ts`, `frontend/src/features/portal/TopicControls.tsx`, `frontend/src/features/portal/TopicControls.test.tsx`; Modify `frontend/src/api/{live.ts,live.test.ts,live.recovery.test.tsx,generated/schema.d.ts}`, `frontend/src/features/portal/{ChatPanel.tsx,ChatPanel.test.tsx}`, `frontend/src/features/inbox/ConversationWorkspace.tsx`, `frontend/src/features/portal/PortalPage.tsx`。

**Interfaces:** `LiveEvent(String kind,long conversationId,Long seq,long generationEpoch,Long topicId,Long runId,Long executionVersion,Map<String,Object> payload)`；`LiveEventHub.publish(LiveEvent): void`，持久事件由 EventStore 提交后通知，seq=null 仅用于瞬时分片。前端 `interruptConversation(id:number,expectedEpoch:number):Promise<ConversationDTO>`、`switchConversationTopic(id:number,input:TopicSwitchInput):Promise<ConversationDTO>`、`listConversationTopics(id:number):Promise<TopicPage>`。

- [x] Step 1：写 LiveReplayIT：after_seq 补放、通知丢失回放、接收慢客户端断开、Origin/401→4401/无权限→4403/依赖故障→1013、身份撤销后断开。前端测试模拟 A→B→A 与旧流/旧请求迟到，断言 run/topic/epoch 不符的分片从不显示，重复 seq 不重复消息，刷新和重连恢复停止状态。
  关键断言：`dropsLateDeltaFromPreviousTopic()`：`expect(state.partialText).toBe("")`；`expect(state.messages.filter(m => m.id === persistedId)).toHaveLength(1)`。

- [x] Step 2：运行 `-Dit.test=LiveReplayIT verify` 与 `npm --prefix frontend run test -- --run`，期望新增流过滤/控制交互断言失败。
- [x] Step 3：实现 DB seq 回放、Redis 提示、128 事件缓冲、5 秒发送超时、2 秒身份复核与有界重连；订阅先建立通知再补放并去重，避免接入窗口丢事件。界面加入停止、新话题、历史话题恢复与 AMBIGUOUS 选项，发送/停止/切换冲突 409 时刷新状态并显示说明。空闲切话题不自动重跑；切换后原草稿仅在所属话题/主动查看中展示，不自动搬到新话题，保留具体订单与显式确认按钮。
- [x] Step 4：后端和全部前端测试、typecheck、build 通过；重新生成/检查扩展 OpenAPI 类型。现有完整历史可以展示，但按 topic 标注，不能把页面完整历史直接作为模型上下文。
- [x] Step 5：提交 `feat: expose topic controls and reject stale realtime output`。

### Task 12: 运维、审计、任务重试与可观测状态

**Files:** Create `J/operations/{OperationsController,OperationsDtos,OperationsService,AuditService,OperationsMapper}.java`, `S/mapper/OperationsMapper.xml`, `T/operations/OperationsContractIT.java`; Modify `J/config/ServiceHubProperties.java`, `S/application.yaml`, `frontend/src/features/operations/OverviewPage.tsx`, `frontend/src/features/settings/SettingsPage.tsx`。

**Interfaces:** `AuditService.append(Actor,String action,String resourceType,long resourceId,Map<String,Object> safeDetails): void` 加入当前事务；`OperationsService.retry(Actor,long jobId): JobDto`；`runtime(Actor): RuntimeStatusDto` 与原字段一致，新增队列依赖/取消信息使用明确扩展，不泄露 URL 凭证。给任务 3/5/7 已有审计写入统一出口，不重复写两份审计。

- [x] Step 1：写原 6 个运维接口合约；客服与管理员权限分别核对；人工重试保留原同job_id返回；仅failed且领域仍有效、前台仍当前且未取消/取代才允许，stale前台409；显式新budget_revision、manual/lifetime计数审计且下一token增加；指标不能按用户或消息内容产生无限标签，日志与管理端均不含密钥。
  关键断言：`cancelledJobCannotBeAutoRetried()`：`assertThat(newOutboxCount).isZero()`；`assertThat(state).isEqualTo("CANCELLED")`。

- [x] Step 2：运行 `-Dit.test=OperationsContractIT verify`，期望接口/角色/重试断言失败。
- [x] Step 3：实现原任务/指标/审计/runtime API，区分 QUEUED/RUNNING/RETRY/FAILED/CANCELLED；暴露受保护 Actuator 与原 Prometheus 端点。记录排队、执行、取消、模型与工具耗时，Provider 错误脱敏，指标明确真实 usage 和未知值。取消/被取代任务不能通过自动或管理员重试复活；合法failed任务的人工重试例外按一致性规范，用户新问题另建run。HTTP状态映射保持旧小写值，waiting_input是明确加法扩展。
- [x] Step 4：同命令、前端 typecheck/build 通过；RabbitMQ 或 Redis 故障在 readiness/runtime 中可辨识，liveness 只表示进程存活。
- [x] Step 5：提交 `feat: report Java job health and audited operational controls`。

### Task 13: 全量契约、真实多进程故障与在线闭环验收

**Files:** Create `T/contract/FullHttpContractIT.java`, `T/system/{ConcurrencySystemIT,FailureRecoverySystemIT}.java`, `T/build/BuildBoundaryIT.java`, `scripts/{java-system-tests,java-online-check,java-benchmark}.ps1`, `backend-java/src/test/resources/scenarios/{topic-routing.json,online-checks.json}`, `.github/workflows/java-ci.yml`, `docs/verification/java-acceptance.md`; Modify `pom.xml`, `compose.java.yaml`（Java Docker build/profile）、`frontend` 测试按实际缺口补充，`backend-java/Dockerfile`, `.dockerignore`。

**Interfaces:** `java-system-tests.ps1 -WorkspaceRoot R -ReportPath PATH` 启动两个实际 API JVM 和独立 Worker，使用测试专用 DB/Redis key/vhost/随机回环端口；只操作脚本创建并登记的 PID/容器。`java-online-check.ps1 -WorkspaceRoot R -ReportPath PATH` 从忽略配置读 key，不接受密钥命令行参数；输出脱敏 JSON/Markdown，失败返回非零。报告路径必须在 R/.cache/java-verification 下。

- [x] Step 1：写 FullHttpContractIT 覆盖全部 42 个 HTTP/WS 的请求响应与代表错误；ConcurrencySystemIT 使用两个 API 20 路重复消息、20 路确认和 20 名客服抢单，分别断言 1 message+1 logical job、1 ticket、1 winner。FailureRecoverySystemIT 用可控供应商阻塞点覆盖确认丢失、Worker 强制退出/租约恢复、代理重启、Redis 通知丢失、三个后台占满与前台保留名额、新消息取消并发，断言旧执行永远不能提交。不得只依赖同一个 JVM 的锁证明跨进程正确性。
  关键断言：`cancelledRunStaysCancelledAfterWorkerRestart()`：`assertThat(oldRunToolCallsAfterRestart).isZero()`；`assertThat(oldRunFinalMessages).isZero()`；`assertThat(currentRunFinalMessages).isEqualTo(1)`。

- [x] Step 2：运行新增测试，真实制造上述故障，先确认每个故障注入点确实触发；在实现系统脚本前因缺服务/故障步骤失败；缺 Docker 必须明确阻塞，不能 skip 后宣称系统验收成功。
- [x] Step 3：完成脚本、Docker build、CI；将A01–A20逐项映射到实际测试和报告，不填空skip；BuildBoundaryIT验证三种cwd的实际Maven路径、127.0.0.1端口、哨兵文件不进构建上下文/镜像及旧worktree不被覆盖。CI 在 Linux 执行 Maven verify、前端 test/typecheck/build 和容器构建；数据库/消息容器使用真实依赖，在线供应商测试默认不进入 CI。修复验收揭示的问题只做定向回归，避免无意义重复全套。
- [x] Step 4：全部离线工程验证通过后，运行真实百炼验收：上传文本 PDF→实际 1024 维 Embedding→中文引用问答→订单工具→草稿→客户确认→人工回复。路由样本至少15条，涵盖五类意图及不需分类的普通追问；真正流式响应中发送新消息/停止/切话题并核对持久结果。记录实际输出、失败及取消延迟；不预设路由准确率。模型调用成本和是否真正被供应商停止如实记录。
- [x] Step 5：运行 `java-benchmark.ps1`：无模型业务接口并发 1/10/20、各 100 请求；模型单独统计样本/排队/首分片/完成时间/Token，排除外部延迟影响解释。报告硬件、版本、进程数、数据规模、p50/p95、错误率。`mvn verify` 与前端三项最终验证通过后提交 `test: verify Java contracts recovery and real model workflows`，报告中使用本版实际测试数。

### Task 14: 数据转移、交付讲解、GitHub 与简历证据

**Files:** Create `scripts/java-data-transfer.py`, `backend-java/src/test/resources/transfer/synthetic-v1.json`, `T/system/DataTransferIT.java`, `docs/operations/{Java启动与故障恢复,Java关键源码与面试讲解}.md`, `docs/verification/java-delivery-evidence.md`; Modify `README.md`, `docs/operations/Java全栈与Agent路线.md`, `docs/superpowers/specs/2026-10-22-servicehub-java-design.md`。简历输出在用户已有 `C:/Users/cz/Desktop/投简历用`，执行时先确认用户最近修改的文件版本。

**Interfaces:** `java-data-transfer.py export --source-env PATH --output PATH`、`validate --input PATH`、`import --target-env PATH --input PATH --allow-empty-target`。只支持空的 Java 目标库；target 与 source 指纹相同、目标非空、重复 ID/坏外键/目录越界即拒绝。文件必须在 R/.data/java/transfer 内；凭证仅读取忽略环境，输出脱敏摘要。初版移交业务和文档元数据/私有文件，历史任务/会话凭证不导入；所有历史消息映射到默认 topic，知识向量在 Java 重新构建。

- [x] Step 1：写 DataTransferIT：两租户虚构订单/消息/售后/知识往返，金额、UTC、归属和文件 hash 一致；非空目标、源目标相同、损坏关系均失败且无部分写入，旧任务/登录凭证不被复活。
  关键断言：`transferPreservesTenantAmountsAndRejectsNonEmptyTarget()`：`assertThat(importedTotal).isEqualByComparingTo(exportedTotal)`；`assertThat(rejectedImportChangedRows).isZero()`。

- [x] Step 2：运行 `-Dit.test=DataTransferIT verify`，期望缺少移交/校验逻辑失败；只使用合成数据，不能为了测试导出用户真实聊天内容到仓库。
- [x] Step 3：实现先校验后事务导入，失败回滚数据库；文件先暂存、校验后提升，异常残留可清理。操作文档包含旧版保留、Java启动、账号初始化、备份/恢复、故障演练、回退及Docker引擎缓存例外；关键源码讲解覆盖 Spring MVC/Security/MyBatis、Outbox/RabbitMQ、租约/幂等、topic/epoch 以及 Spring AI 工具回环。架构图和并发时序必须与实际代码一致。
- [x] Step 4：运行 DataTransferIT 和按文档干净启动；读取 verification-before-completion/requesting-code-review，解决审查中发现的真实问题并定向验证。GitHub 发布沿用已授权账号与已有仓库；仅从当前已验收代码制作干净发布快照，检查私有缓存/密钥/上传/个人文件和完整 Git 历史是否会泄露，保护现有 README 内容和公开历史。推送后确认 GitHub Actions 成功，再把版本/实测证据写入交付记录。
- [x] Step 5：按实际完成情况更新 ServiceHub 简历条目与技能，保留用户证件照、AI全栈开发工程师求职标题及研究项目；编辑 DOCX 时使用 documents 技能并渲染核对。此时不能把尚未实现的 InsightPilot 写成完成。提交 `docs: deliver Java platform with verified deployment and resume evidence`。随后按独立设计编写并审阅 InsightPilot 实施计划，继续同一会话执行，不另问执行方式。

## 自查记录与需求映射

- 架构/版本/工具隔离：任务 1；表结构与合约：2；身份/租户/成员：3；会话订单：4；售后：5；Outbox/队列/恢复：6；打断话题：7；知识导入：8；检索引用：9；模型和提交：10；实时/界面：11；运维：12；故障/在线/性能/CI：13；数据移交、讲解、发布和简历：14。
- Review Focus 五类情况分别已落到上述命名测试，不以“考虑边界”替代断言。多轮要求与并发矩阵A01–A20均映射任务2–13；独立Agent的P01–P03明确保留到第二阶段。
- TopicRouter 的测试替身与真实 Spring AI 分类分开，Guard/JobLease/ExecutionFence 名称保持一致；所有后续调用通过 Interfaces 中声明的方法，不读取其他模块 Mapper。
- 基线 API 不换套壳，不改角色/会话协议；新增接口明确计数。Java 不引入 LangChain/LangGraph；独立 Agent 仍使用 LangGraph。
- RED/PASS 指行为测试，基础依赖解析失败单独修复；在线验证和多进程故障与普通测试各有报告，不拿旧 Python 94/前端 18 测试数替代本版证据。
- 本计划未授权新增支付/退款写工具、OCR、微服务或共享运行库；设计变更先说明影响并修订记录。

## 实施前状态

- [x] 用户已批准两项总体设计和先 Java 后 Agent 的顺序。
- [x] 纳入用户新增的对话打断与话题切换要求。
- [x] 完成计划自查，明确依赖、接口、验证与发布证据。
- [x] 用户已逐项审阅并提出一致性/资源/契约等修改意见，本版已逐项修订。
- [x] Task 1–14实现、真实依赖/模型验收、发布和简历更新已完成；最终任务门禁记录在本计划ledger。
- [x] 在隔离工作树完成任务1–14和验收，公开CI355项通过；后续独立Agent计划已审阅。
