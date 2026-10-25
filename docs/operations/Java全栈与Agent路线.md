# Java 全栈与独立 Agent 项目路线

更新日期：2026-10-25。

ServiceHub 已实现 Java 后端与现有 React 的完整业务迁移。当前进行数据移交、部署、代码审查和发布收尾，不能把独立问数 Agent 写成已完成。

## 当前证据

- Java21、Spring Boot4.1.1、Spring Security、MyBatis-Plus3.5.17、Spring AI2.0.1、MySQL、Redis、RabbitMQ 已实际构建与集成。
- 原39个HTTP接口、3个话题/停止扩展和1个WebSocket已验证契约与边界。
- 完整原生回归34单元+299集成通过；两租户业务、文件及账号恢复的6项移交验收另行通过。
- 双API进程、实际Worker、真实数据库和队列验证消息判重、回滚、CAS、抢单、动态撤权、Outbox、租约围栏和资源隔离。
- 真实百炼 qwen-plus 与 text-embedding-v4 已完成问答、引用、工具、客户确认、人工回复及三类流式打断验收。
- GitHub发布、干净启动和简历定稿以[交付证据](../verification/java-delivery-evidence.md)为准；旧Python运行库保持独立。

## 后续顺序

1. 完成 ServiceHub Java 最终审查、发布与简历更新。
2. 按已批准的[InsightPilot设计](../superpowers/specs/2026-10-22-insightpilot-agent-design.md)编写并审阅实施计划。
3. 继续在当前会话逐项实现独立 Python/FastAPI/LangGraph 问数 Agent，使用自己的执行状态、受控只读数据工具、口径确认和可溯源报告。
4. Agent 验收通过后再更新对应源码说明及简历，保留 PAEE 研究项目与证件照。

ServiceHub 用 Java/Spring AI 实现受控客服工具回环，没有引入 LangChain 或 LangGraph。问数 Agent 单独实现，不以框架名字替代真实业务和测试证据。两个项目均要求对话打断、话题隔离和安全恢复，分别按各自验收记录说明。
