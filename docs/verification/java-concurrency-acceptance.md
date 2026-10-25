# Java 并发、故障注入与资源隔离验收矩阵

日期：2026-10-22。状态：验收规范已写入计划；以下测试尚未实现或运行，不是通过报告。

规范来源：[执行一致性与状态表](../superpowers/specs/2026-10-22-execution-consistency-design.md)、[多轮对话设计](../superpowers/specs/2026-10-22-conversation-interruption-design.md)、[逐接口契约](java-contract-matrix.md)。

## 测试环境与判定方法

- 使用两个独立 Java API JVM 和独立 Worker，真实 MySQL / Redis / RabbitMQ；所有测试身份、数据、目录、PID/容器均由本次合成 fixture 创建。模型使用独立可控 HTTP 服务，真实供应商闭环另列。
- 并发开始使用 barrier，故障位置使用 latch / 可观测 gate；不靠随意 sleep 推测已进入竞态。报告保存命中 gate、DB before/after 状态、owner/token、epoch/run/topic、队列投递/confirm/ack 计数和退出码，正文/密钥脱敏。
- A01–A06、A14 的并发竞态每项至少 20 轮，每轮独立数据与限流键；任一安全不变量违反即失败，不能取平均或“绝大多数通过”。故障案例逐项必须注入成功；没有进入预定窗口不能计通过。
- 普通成功调用不应出现未声明 5xx。预期 409/403/404/401/429/503 等按具体注入与契约判定，不能把所有非 200 当失败，也不能忽略意外错误。
- 所有时间等待有显式上限与可读失败原因。租约/截止测试允许 system-test 配置缩短到租约 3 秒/续期 0.5 秒、前台 deadline 5 秒；报告保留实际参数，并用配置测试锁定生产默认 60/10、120/45 和退避 5/30 秒，不能声称短时测试测出了真实供应商 120 秒行为。

## Java 必过案例

| ID / 所属测试 | 精确注入/输入 | 必须同时满足的通过标准 |
|---|---|---|
| A01 MessageRaceIT | 两 API 同时接收 20 个相同 client_message_id，再做 10 次响应丢失重试；合成用户避免与限流案例互相影响 | 1 条客户消息、1 个逻辑 run、1 个 job；epoch 仅 +1；所有成功返回同一 message/run/job/accepted_epoch；无额外 outbox（正常首次投递记录除外） |
| A02 MessageRaceIT | 相同 key 不同规范化 body 或 sender；事务在插消息/写 outbox 各点强制失败 | 冲突 409；消息/epoch/run/job/event/outbox 全部提交或全部回滚，无半条记录；故障后同 key 可安全取得原结果或首次成功 |
| A03 InterruptionIT | 两页面带同一 expected_epoch 同时 NEW 或 RESUME；同 actor 重放停止回执后已存在新 run | 切换恰好 1 成功，其余 409；只创建一个目标 topic/事件；停止重试不再增加 epoch，不取消后来 run |
| A04 LiveReplayIT + 前端 live.test | A1→B→A2 后主动发送 A1 的 delta/分类结果/final commit，epoch 不同而 topic 相同 | active_topic 仍 A2；A1 delta 不出现在当前 partial；A1 最终消息 0；seq 去重；旧分类不改指针/消息归属 |
| A05 AICommitFenceIT | 模型完成后、授权锁前暂停，管理员先提交禁用/降权，再放行提交 | 旧提交/草稿/最终回答 0，错误为授权失效；检查点不接受旧授权快照；模型消费可能已发生，如实记录 |
| A06 MemberRevocationIT | 业务写先持有授权共享锁，管理员同时撤销 | 管理员撤销在业务提交前不能提交；合法写先完成再撤销；撤销提交后的所有新受保护写为 0。通过 latch/事务顺序验证，不靠同一毫秒时间戳猜顺序 |
| A07 OutboxDeliveryIT | 真实 broker 收到消息，但发布者在 confirm 持久标记前断开/退出；过期发布者随后收到迟到 confirm | outbox 未提前 DISPATCHED；重新发布仍同 job_id；旧 outbox lease_version 不能标记新领取；1 逻辑 job、至多 1 正式业务结果。允许重复供应商请求，不要求物理只调用一次 |
| A08 LeaseRecoveryIT | A 持 token=1 停续约并暂停最终提交，租约过期，B 取 token=2 完成，再释放 A | B token > A；A commit affected_rows=0；最终消息/报告只有 B 一份；领域、run、job 一致，无旧 checkpoint；另测“过期但 B 尚未领取”也拒绝 A |
| A09 FailureRecoverySystemIT | 已持久取消后丢弃 Redis 取消通知，再退出/重启 Worker 并重投 broker 消息 | 取消 job 无新 claim/工具/最终回答，状态不复活；当前合法新任务可完成；故障恢复不能依赖进程内标记 |
| A10 FailureRecoverySystemIT | 暂停 RabbitMQ 后发送新消息，恢复代理；强制退出已登记 Worker PID | 消息事务已接受则 run/job/outbox 不丢；broker 恢复且未到期的任务可完成；租约过期+5 秒内取得新 owner（依赖健康、受控模型）；到期则明确超时，无悬挂 running |
| A11 FailureRecoverySystemIT | MySQL 在事务中/消费失败持久化前不可用；供应商临时 5xx、持续 5xx、永久 4xx 分别注入 | DB 不可持久时不 ack；恢复后同 key 不重复。临时故障可恢复；持续可重试故障总尝试≤3，前两次退避5/30秒；永久错误/权限/取消不自动重试；无伪造成功回答 |
| A12 AgentBudgetTest + ProviderContractIT | 模型调用/槽等待/工具延迟/自动重试跨硬 deadline；重启 Worker | deadline_at 不变，timeout=min(45,remaining)；超时+1 秒内本地进入终态，禁止后续调用/提交；模型数≤6、工具数≤4，预留次数恢复后不归零；usage 缺失为 null |
| A13 AdmissionIsolationIT | 3 个后台导入分别占执行槽/模型槽并阻塞，随后接收前台任务 | 正常依赖和受控模型下，前台≤1秒取得执行与模型名额；总 active≤4、background≤3；多 Worker 也满足；后台不能抢保留名额。槽续期丢失即停止后续调用，不能双释放/误释放其他 owner |
| A14 ConfirmationRaceIT | 20 路同一草稿确认、20 名不同客服抢单；换话题后查看原有效草稿 | 1 ticket、1 winner；冲突符合契约；正式工单不能由 Agent 工具自动创建；客户显式确认有效原草稿可成功，取消聊天不撤销已提交工单 |
| A15 TopicIsolationIT + TopicRoutingTest | 两 topic 使用相似订单；同 topic “换另一个订单”却未给新编号；普通“那运费呢”追问 | 跨 topic 上下文无旧业务对象/确认；REVISE 清目标槽位并澄清，不调用旧订单工具；确定性追问分类 LLM 调用数=0；已取消/partial/未归属消息不进入上下文 |
| A16 CitationAccessIT | 检索完成后、回答提交前停用文档/切版本；恢复检查点后访问对象已撤权 | 未通过当前权限/版本核对的引用提交为0；历史下载仅按明确本人会话引用授权；不继承 checkpoint 的“已授权”结果 |
| A17 SchemaIT + NumericTimeContractIT | 同一 Instant 在 UTC/Asia/Shanghai/America/New_York JVM 测试，含 DST 边界；12.30/最大金额/超精度 | 微秒时间点往返完全一致，DB会话UTC，原JSON格式一致；DECIMAL(12,2)保精度，12.30是字符串，越界/超小数位拒绝、不静默舍入 |
| A18 BuildBoundaryIT | 从 R、backend-java 和 W 启动；旧 worktree 有未提交内容；Docker build context 放私有哨兵文件 | 实际 Maven 分发在R/.cache/maven、依赖在R/.cache/m2；不改全局配置/覆盖 worktree；构建上下文/镜像无私有哨兵或.env；Compose host_ip全部127.0.0.1，项目持久目录在R，Docker引擎例外单列 |
| A19 FullHttpContractIT | 逐项 H01–H39、N01–N03 和 W01，六类身份与边界样本 | 所有相关维度都有测试，不只是42路由数量；无未解释协议差异。显式版本差异D01/D02/D03单列，不能用忽略字段规则掩盖 |
| A20 BackgroundScopeIT | 知识导入正在执行时前台 A→B→A/停止回答；导入 owner 权限另行撤销 | 单纯前台切换不取消导入，合法导入激活其文档版本；独立授权撤销则禁止激活；不会为当前聊天生成一条背景“回答” |

## InsightPilot 阶段保留的必过案例

这些不是 Java 阶段已实现测试，进入独立 Agent 实施计划时逐项迁入，不为 Java CI 增加空 skip 冒充通过。

| ID | 场景 | 通过标准 |
|---|---|---|
| P01 | A 后台报告执行中，前台切到 B | A 在权限/租约/task_revision有效时完成并写回A，B当前回答无A报告内容；只有显式取消A才阻止A后续查询/提交 |
| P02 | SQL HITL 暂停后换话题/改SQL/换任务/撤权/重复确认 | 单纯切前台不冒充同意；恢复精确指定run/checkpoint；修改SQL hash/revision或权限后旧批准拒绝；重复确认至多一次生效 |
| P03 | checkpoint写前A lease过期，B恢复后A迟到；同池连接切租户 | A不能写checkpoint/报告；只读角色/RLS实际生效，事务上下文清理，工具不能读取状态库或其他租户 |

## 最终证据与退出条件

每项报告记录测试ID、源代码提交、fixture种子、容器/JDK/依赖版本、两个API和Worker的实际PID、参数、注入位置与命中证据、断言、耗时、退出码。机器结果保存在 R/.cache/java-verification，不将真实密钥/聊天/个人信息放进仓库；公开报告只保存合成数据和脱敏汇总。

Java“通过”要求 A01–A20 全部达到标准，Maven verify、前端 test/typecheck/build、Docker build和GitHub CI通过；在线供应商闭环另有真实Embedding、引用、工具、取消/路由结果，不以替身通过代替。发现竞态违反即修复并重跑该类及受影响流程，不能用重试测试直到偶然绿灯。环境不可用/故障未注入成功/测试跳过均不算通过。
