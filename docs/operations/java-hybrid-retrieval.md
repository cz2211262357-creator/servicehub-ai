# Java 混合检索与引用

检索固定使用 MySQL ngram 全文与 Redis HNSW/COSINE 两路，各最多 30 个候选。两路均在排名/候选限制之前约束 tenant 和当前激活的版本/执行代次。全文以 MATCH NATURAL LANGUAGE MODE 排序；完全无结果时保留旧 Python 的有界词项 LIKE 回退（256 字符、最多 16 词项、转义通配符）。此回退不用于掩盖 Redis 故障。

向量检索明确指定 LIMIT 0 30，避免 FT.SEARCH 默认只返回 10 条。给定两路排名后，RRF 使用 k=60，同路重复 ID 只计一次；融合分数降序、相同分数按 chunk ID 升序。返回前再次查询有效版本、授权和执行 guard，最终最多 6 条。向量模型配置变更后，旧模型索引需要重新导入，不悄悄混用不同模型的向量。

问答查询的 Embedding 使用所属 INTERACTIVE 任务名额；文档导入使用 BACKGROUND 名额。三路后台模型请求占用时，前台仍能取得第四个名额。调用截止时间继续来自持久任务。

## 当前引用与历史文件

内部 Evidence 保持 documentId/versionId/chunkId/filename/page/excerpt/score。前端原 citation 仍使用 chunk_id/document_id/version_id/page/text/score/title；title 是文档标题，不能拿内部 filename 替换。

CitationAuthorizer.requireReadable 加入调用者业务事务：授权用户 → 会话 → 按 ID 排序的文档。它校验当前激活版本/执行代次、原始分块正文、文件名和页码；最终 AI 提交须在这些资源之后锁定 run/job，避免停用与提交之间发生竞争。

旧助手消息若引用了已停用、已删除或已换版的知识，整个助手消息退出后续模型上下文，避免只删 citation 却继续把过期正文提供给模型。空 citation 的正常历史消息仍按话题/运行状态规则处理。

旧版公开下载 URL 只有 document_id 和可选 version_id，继续允许客户通过任一本人会话中的原始 AI 引用下载历史版本；当前文档仍须启用/未删除。新内部 openHistorical(actor,conversationId,versionId) 还要求该特定会话中存在引用，不借用另一个本人的会话。员工公开下载权限沿用旧接口。

## 故障行为

Redis 查询失败返回 retrieval_unavailable，绝不把单路全文结果冒充完整混合检索。全 Redis 不可用时，执行资源准入也无法被确认，任务按 guard 失败关闭。故障测试分别针对搜索索引和执行资源，不把两种边界混为一谈。
