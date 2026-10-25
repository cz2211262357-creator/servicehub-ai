# Java 运维、审计与指标

保留六个接口：GET /api/v1/admin/jobs、POST /api/v1/admin/jobs/{job_id}/retry、GET /api/v1/admin/audits、GET /api/v1/admin/metrics/summary、GET /api/v1/admin/runtime、GET /api/v1/metrics。

任务、审计、手动重试和Prometheus仅管理员可用；汇总和runtime允许客服与管理员。所有业务数据查询限定当前tenant，不能通过任务ID访问其他租户。Actuator的health/metrics同样仅管理员访问，不返回凭证或配置URL。

## 重试与预算

自动总尝试最多3次，首次失败后退避5秒、第二次失败后30秒。runtime.automatic_retries=2表示首次执行后的额外重试数，不是总尝试数。取消、权限失效、永久错误不自动重试。

人工重试是显式的新预算批次。只接受FAILED任务，管理员与原执行者的权限行按ID排序后锁定；前台任务还必须是当前AI_ACTIVE会话的topic/epoch，原授权版本有效，原run处于FAILED。后台知识任务必须仍指向最新、启用、未删除的文档版本与原导入任务。

保持job_id/run_id，manual_retries和budget_revision增加，lifetime_attempts保留。本批attempts及模型/工具预留、分类领取与结果清零，明确重置本批开始和deadline；下一次claim增加execution_version。新Outbox与状态更新、审计同事务。旧Worker即使遇到新预算仍不能写入。自动恢复不会享有这种预算重置。

人工重试内部转为RETRY_WAIT，HTTP显示retrying。旧Python人工重试响应queued；此变化与新的取消/权限限制在契约D03中明确声明。重复或并发手动重试只有一个成功，其他409，不重复写Outbox或审计。被新问题取代的失败任务、CANCELLED任务不能复活。

## 审计与安全状态

AuditService是统一写入出口，MANDATORY加入业务事务：业务回滚则审计也回滚，不在业务外孤立写成功。成员、会话、话题、售后、知识、AI提交与手动重试使用同一出口，保留原action/target_type/outcome/request_id。公开摘要字段限定在角色、状态转换和重试计数等已知字段；不保存密钥、模型内部思考链或整段供应商异常。

liveness只表示进程存活。readiness与runtime对database/redis/rabbitmq使用最多4秒的并行检查，状态只有ok/unavailable。readiness失败保留原错误码和消息，在error.details.dependencies增加安全诊断字段。Redis故障时opaque Cookie认证仍失败关闭，runtime可以503；匿名readiness仍可辨识redis故障，不降级成匿名管理访问。

## 业务汇总

汇总按当前tenant与创建时间窗口查询，时间必须含时区，start<end，最长366天。默认过去7天，使用数据库UTC当前时间。金额和时间仍沿统一精度/序列化规则。

handoff统计审计目标ID使用显式utf8mb4_unicode_ci字符串比较，避免MySQL8默认连接排序规则与旧表不同；不会把任意字符串强转数字冒认会话。总量、日趋势、首条客户消息到首次AI/人工回答、任务状态和用量都来自实际持久记录。

SQL NULL和JSON null的usage均保持未知；只有真实整数total_tokens才计入usage_records及总量，真实0是有效记录。没有已知记录时total_tokens=null。部分记录缺失时，页面明确显示未记录用量的运行数。此汇总不是账号总账：ai_runs.usage仅含已完成对话回环返回的用量，Embedding、分类、失败调用的费用须以供应商账单为准。

## 技术指标

Micrometer记录servicehub.stage.duration，stage限定queue/execution/model/model_wait/tool/cancel，kind限定本项目任务、chat/embedding和三个工具名称；其他输入统一other，outcome限定固定状态。没有用户、会话、任务ID、正文、模型私有URL或原始异常标签。

queue是任务可运行后到取得执行权的等待；execution是本进程执行尝试；model是模型请求链路耗时（包含准入等待），model_wait另记已取得名额的等待；tool是服务端工具处理。cancel记录停止HTTP请求到事务完成的耗时，不能据此声称供应商已停止计算。真实供应商取消延迟在系统验收单独测量。

servicehub.model.usage只记录完成的供应商响应的known/unknown统计。缺失total不注册伪造的零tokens；真实0仍保留known记录。失败或中断请求可能已经消耗供应商计算，不能从本地缺少usage推算免费。技术指标为进程级；租户业务总量仍来自受保护、按tenant筛选的SQL及原Prometheus端点。

## 验证边界

OperationsContractIT覆盖角色/租户、分页和时间窗、真实0/未知、并发重试、取消/取代/撤权、文档版本及实际RabbitMQ/Redis暂停。RuntimeMetricsTest限制标签基数并保留未知；ProviderContractIT及AgentCancellationIT验证真实HTTP替身经过生产gateway/tool后确实产生指标。模型替身测试不能替代后续真实百炼与双API/独立Worker验收。
