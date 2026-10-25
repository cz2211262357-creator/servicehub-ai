# 多轮对话打断与话题切换补充设计

日期：2026-10-22。状态：已按用户逐项审查修订；Java部分已于2026-10-25随实现验收；独立Agent按其已批准的实施计划继续。

## 范围与已有基础

ServiceHub 保持完整 Java 后端和现有 React / WebSocket；InsightPilot 保持独立 Python / LangGraph 项目。图中的 Java+Python 混合架构作为思路参考，不改变已批准的两个项目边界。

现有 Python 新消息事务已经在判重后推进 generation_epoch，最终提交也复核 epoch。新增范围是执行途中取消、显式停止、话题归属、上下文白名单与安全恢复。字段、事务、状态表、权限锁、Outbox fencing、预算与资源规则详见 [执行一致性规范](2026-10-22-execution-consistency-design.md)，不能仅用 if 判断替代数据库约束。

## 话题、任务与取消分别处理

| 用户动作 | 前台回答 | 独立后台任务 |
|---|---|---|
| 继续追问 | 新 run/epoch 取代未完成旧回答，继续本话题有效事实 | 不自动修改/取消所属报告 |
| 修改目标/换订单 | 同 topic 可新 run；清空被替换业务对象的执行槽位与确认 | 只有明确修改指定后台 run 才增加 task_revision |
| 新话题/切回 A | 改 active_topic_id 并推进 epoch；旧前台失效，恢复前重新鉴权 | 保留原 topic/run，可完成并写回原话题 |
| 停止当前回答 | 持久取消前台执行，停止后续调用与显示旧分片 | 不等同“取消所有任务” |
| 显式取消指定报告 | 当前回答不被误伤 | 持久取消该 run，后续查询/checkpoint/报告写入拒绝 |
| 意图模糊 | 优先当前话题指代，必要时请求明确选择 | 不把新消息当作任何旧 HITL 的同意 |

Java 首版后台任务是知识导入，不加入额外报告业务；切换聊天不能取消文档导入。InsightPilot 报告属于 BACKGROUND_TOPIC，即使前台已经切到 B，A 报告在授权/租约/task_revision 有效时仍可完成，只在 A 的分析记录显示，不能成为 B 的当前回答。

停止只控制尚未提交的动作，不能撤销已提交工单，也不能保证供应商瞬间停止计算/计费。不可逆外部写入不在第一版工具范围内。

## 路由原则与示例

路由结果保持 CONTINUE、REVISE、NEW_TOPIC、RESUME、AMBIGUOUS，但不是每条消息都调用分类模型：

1. 明确界面动作直接执行指定路由，服务端仍校验权限/版本。
2. 普通问答和明确追问默认继续当前 topic；可确定的代词引用使用当前已确认对象，不额外调用 LLM 分类。
3. “刚才那个订单不用查了，查另一个订单”默认 REVISE：同订单查询 topic 内创建新 run，旧前台取消，旧 order_id/待确认动作清空。没有新订单号时澄清，不能继续对旧订单调用工具。
4. 明确另开业务话题才 NEW_TOPIC；明确恢复本人历史话题才 RESUME。任务变化不等同 topic 一定变化。
5. 规则无法确定且确有冲突时，最多一次结构化分类，计入同一轮预算；仍不确定或分类无效就 AMBIGUOUS。候选只含当前话题摘要及本人授权话题标题。

模型没有授权或继承确认的能力。界面显示“继续此话题 / 新建话题”并暂停工具；由用户选项解析原 pending 问题。过时分类必须通过 epoch/租约 CAS，不能迟到切回旧 topic。

客户消息先持久化，路由完成前 routing_state=PENDING、topic_id 可空；确定性 CONTINUE 可在接收事务内直接归属。需要模型分类时由 Worker 在事务外执行并受 fence 保护。人工消息归属发送时的当前话题。切换不删除历史消息或草稿。

## 上下文白名单与“已解析”定义

已解析消息至少满足：已经提交数据库、routing_state=RESOLVED、有授权的同一 topic，且 context_status=ELIGIBLE。各类别具体如下：

- 用户消息：有效且未被明确撤回/取代的当前 topic 指令；已被新目标取代的消息标记 SUPERSEDED，不作为待执行指令恢复。
- 助手消息：仅已成功提交的完整回答。取消、超时、失败、被 fence 拒绝的输出和流式 partial 均不进入模型上下文；partial 不作为正式消息持久化。
- 人工消息：归属该 topic 的已提交消息，复核当前访问权限后使用。
- 事实/摘要：只从有来源的对象白名单整理，例如本人已核对订单、有效政策片段、已确认指标口径；包含 topic、来源消息、业务对象与验证版本。恢复前重验。不能保存“已授权=true”作为可复用依据。
- 工具结果：仅合法执行产生并重新校验的必要事实；不跨话题搬运原始工具输出、隐藏提示或旧计划。被取消指令的安全事实可经重验保留，但原动作不恢复。

消息、公开摘要和已确认事实合计最多最近 20 条消息及 16000 字符。新 topic 默认空业务槽位；换订单即使仍在同一 topic，也清空旧目标槽位。ToolRegistry 只接受当前 run 明确指向并经服务端验证的业务对象；模糊“另一个”不允许回退到旧订单。

跨 topic 业务对象只允许用户显式引用：例如在新问题写出具体订单号，或点击历史订单的“引用”生成含明确订单号的新消息；服务端重新查询当前对象权限，仅导入被引用事实，不导入旧执行确认。无需增加第四个 Java 聊天接口。

## Java 数据与新增接口

增加 conversation_topics；conversations.active_topic_id 为当前话题权威指针。messages 增加 topic_id/routing_state/context_status/accepted_generation_epoch/trigger_run_id/trigger_job_id；ai_runs 增加 topic_id、授权版本和预算字段。所有关联按 tenant_id 与 conversation_id 校验，旧消息迁入默认话题。AIRun 的 INTERACTIVE scope 与知识 job 的 BACKGROUND scope 分开。

保留 39 个原 HTTP 与 1 个 WebSocket，新增 3 项：

1. `POST /api/v1/conversations/{conversation_id}/interrupt`，输入 `{expected_generation_epoch}`：授权后短事务推进 epoch、取消当前前台 run、写事件及幂等停止回执。重复同一停止返回回执对应状态但不取消后来的新 run；其他旧版本 409。
2. `GET /api/v1/conversations/{conversation_id}/topics`，返回 `{items: TopicDTO[], active_topic_id}`，仅同会话有权限话题，界面最多列最近 100 条；不删除旧记录。
3. `POST /api/v1/conversations/{conversation_id}/topics/switch`，输入 `{mode: "NEW" | "RESUME", topic_id?, expected_generation_epoch, pending_message_id?}`。RESUME 目标必须在当前会话；CAS 冲突 409。pending 仅能解析当前最新未分配问题一次；无 pending 时只切换，不能重跑旧工具。

ConversationDTO 增加 active_topic_id；MessageDTO 增加上述话题/不可变触发关联字段（非 AI 任务可空），保留原 ai_run_id 含义。TopicDTO 为 `{id,title,status,last_activity_at}`，status 为 ACTIVE/SUSPENDED/COMPLETED，按权威指针导出。会话 CLOSED 拒绝新前台任务；AI_ACTIVE 才能启动新 AI，人工处理状态不能被 topic 切换绕过。

## 流式事件、确认与恢复

前台 ExecutionFence 为 tenant/conversation/topic/run/generation_epoch/execution_version。WebSocket 增加 topic_id/run_id/execution_version，客户端同时检查 epoch，A→B→A 不会接受旧 A 分片。持久事件 seq 单调，Redis 只是提示；前台正式回答仅来自成功持久化的 message.created。

独立报告 fence 为 tenant/固定 topic/run/task_revision/execution_version，不能受前台 active_topic/epoch 约束。后台完成事件只更新所属任务卡片/分析记录，不追加到当前回答。停止通知丢失仍由数据库持久状态和提交 fence 保护。

ServiceHub 售后草稿按来源 topic/run/epoch 标记，切话题不删除有效草稿或工单；不自动搬入新话题的确认卡片。客户主动查看原草稿时仍按 draft_id/version/expires_at/订单归属/CSRF 显式确认。Agent 没有正式 confirm 工具。

Agent 执行确认按任务 scope 管理：前台 epoch/目标改变则旧前台确认失效；后台 HITL 绑定本身 run/task_revision/SQL hash/auth_version/预算及 checkpoint，单纯切前台话题不使后台任务失效，也不允许把批准套给另一个任务。修改 SQL、任务或权限后必须重新确认。

InsightPilot 一个 run 一个 LangGraph thread_id，worker 故障恢复同一逻辑 run，更新执行 token；任务修改用新 revision 或新 run，不能隐式当成旧 checkpoint resume。checkpoint/最终报告与 token、权限、预算验证在同一事务，重新授权不能相信 checkpoint 里的旧结论。

## 验收入口

所有具体通过阈值见 [故障与并发验收矩阵](../../verification/java-concurrency-acceptance.md)，结构与运行时协议见 [接口契约矩阵](../../verification/java-contract-matrix.md)。重点是重复请求不再增加 epoch、双页面 CAS、迟到分类/流、背景任务不随前台切换误取消、动态授权写入竞态，以及恢复不复活旧确认。

确定性替身用于精确制造竞态，真实模型另外执行路由/取消样例并报告实际结果。文档与待写测试不能冒充已经通过的实现证据。
