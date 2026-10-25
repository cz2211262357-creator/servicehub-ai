# 话题与持久停止协议

对应迁移计划 Task 7。Java 仍负责完整客服后端；本阶段不引入 Python 网关。真实分类模型与回答工具链在 Task 10 接入。

## 新增接口

| 方法与路径 | 输入/结果 |
|---|---|
| POST /api/v1/conversations/{id}/interrupt | expected_generation_epoch；返回 ConversationDto |
| GET /api/v1/conversations/{id}/topics | 返回最近至多100项 items 和 active_topic_id |
| POST /api/v1/conversations/{id}/topics/switch | mode=NEW/RESUME、expected_generation_epoch、可选 topic_id/pending_message_id；返回 ConversationDto |

保持 Cookie、CSRF 和原错误信封。缺字段/不合法参数422，epoch或pending冲突409，不存在/越权话题404。关闭会话不能创建新的前台执行。人工处理状态可以切换查看话题，但不会因此回到 AI_ACTIVE。

停止先锁当前授权用户与会话，再检查同 actor/目标 epoch 的停止回执。已存在回执时重放原快照，不推进版本或取消后来任务。客户端处理快照仍须遵守单调事件版本，不能用旧回执覆盖更新后的页面状态。

切换没有独立重试回执，旧 epoch 请求409。恢复已经活动的话题且无 pending 是无副作用读取；切换到其他话题才推进 epoch。没有 pending 时不自动运行旧工具。有 pending 时仅客户本人可解析当前最新问题，旧 run 被取代，明确创建新的 run/epoch/预算；过期问题不能恢复。

## 消息、路由和执行关联

client_message_id 判重在推进 epoch 之前。重试返回原 id、accepted_generation_epoch 和 trigger_run_id/job_id；routing_state/context_status 是会话推进后可能变化的当前字段。

trigger_run_id/job_id 永久表示消息最初触发的执行。context_run_id 表示实际解析并用于上下文的执行：用户选择等待中的话题后可指向新 run，不改原触发记录。所有 run/topic 关联都有 tenant/conversation 组合外键。

普通问答与“那运费呢”走确定性 CONTINUE。“换另一个订单”走 REVISE，缺少新编号则需要澄清；“另开话题”及明确话题编号恢复分别走 NEW_TOPIC/RESUME。冲突指代最多调用一次分类边界；本阶段没有真实分类 provider 时保守进入 AMBIGUOUS，等待用户选择，绝不默认为确认。

分类调用在数据库事务外；提交路由时重新锁授权、会话、run、job，检查当前 epoch、owner、execution_version、budget_revision、有效租约与截止时间。A→B→A 的旧 A 仍然失效。分类结果不授予权限，RESUME 目标还需按真实会话归属检查。

WAITING_INPUT 释放 lease/执行资源并持久保存最长24小时；到期 run=EXPIRED，job=FAILED/error_code=input_expired。回收与消费不能重新领取它。

## 上下文与业务对象

只读取同租户/会话/话题、RESOLVED、ELIGIBLE 的消息。助手回答还必须关联成功完整 run；失败、取消、超时、被取代内容与没有成功来源的 partial 均排除。最近最多20条，正文、引用与本轮事实共用16000字符预算。

REVISE 清空旧对象事实/摘要，标记此前话题上下文已被取代；历史页面记录保留。新话题没有旧确认或对象槽位。显式输入订单号可进入新问题，但每次都重新查询当前归属与状态。事实必须有同话题来源消息，EXCLUDED 来源不能作为事实依据，不保存可复用的“已授权”结论。

取消通知只用于加速。CancellationGuardFactory 即使没收到 Redis pub/sub，也会以数据库当前状态拒绝后续调用。领域最终写事务仍必须经过 requireCurrentForWrite，再与 JobCoordinator.finish 一起提交；短暂的执行检查不能代替最后的数据库条件更新。

知识引用的文档版本与可见性复核在 Task 9 接入；真实模型预算、工具白名单和输出提交在 Task 10 接入。前台分片显示与旧回执过滤在 Task 11 前端/实时协议中验证。
