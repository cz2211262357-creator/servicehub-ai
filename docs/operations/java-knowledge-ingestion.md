# Java 私有知识库导入

## 接口与存储

沿用原七个知识接口，Cookie、CSRF、租户权限和 snake_case 保持原契约。管理员上传、追加版本、启停和删除；客户仅能检索启用文档的当前有效版本。原文件用随机名称保存在 SERVICEHUB_WORKSPACE_ROOT/.data/java/uploads，HTTP 响应不包含存储键或物理路径。上传前后分别校验当前账号；事务失败删除该次新文件。

接受 UTF-8 TXT/Markdown、可提取文本的 PDF，单文件不超过 10 MiB。PDF 最多 500 页，保留物理页码，解析后正文最多 10 Mi 字符；加密 PDF、伪 PDF、空文本和纯扫描件明确失败。此实现没有 OCR。PDFBox 以 try-with-resources 关闭文档。按 Unicode 码点切成 800 字符块，步长 700，重叠 100。

## 持久任务与激活

上传事务同时写文档版本、BACKGROUND knowledge_index 任务与 Outbox。消费领取后，执行级别、上传者 auth_version、document_revision、最新版本和合法租约共同约束任务；前台停止或切话题不影响它。版本处理及失败可重试保持原 version_id/job_id，重新领取递增 execution_version，保留最初整轮 600 秒截止时间。网络和模型调用在 SQL 事务外。

待激活分块携带 execution_version；向量使用版本号和执行代次构成 generation。Redis 使用单独的 shj 命名空间、FLOAT32/1024/HNSW/COSINE 索引。逐批完成向量写入后，短事务重新校验授权、文档版本、截止时间及数据库租约，完成 job、更新有效版本指针及激活代次，再推进文档修订号；任一步失败全部回滚。旧任务留在 Redis 的向量不会因版本号相同而进入新结果：检索必须同时过滤 tenant_id 与数据库激活的 generation。

## 百炼兼容和并发

默认 text-embedding-v4、1024 维，每批最多 10 个文本；模型名、完整 compatible-mode/v1 地址和密钥来自本地环境。请求不调用 dimensions() 探测，不带 SDK 隐式重试。响应数量、index、维数、有限数值和非零范数都需验证；损坏响应、401/403 等永久错误不自动重试，429/5xx 和网络故障交给持久任务的三次总尝试/5、30 秒退避。

Spring AI 2.0.1 的 OpenAI 映射强制读取 prompt_tokens，而百炼允许只返回 total_tokens。因此使用实现 Spring AI EmbeddingModel 的小型适配器，复用官方 OpenAI SDK 和 Spring AI OkHttp transport；用量只记录已报告值，缺失值保持 null。

每次批次按全局模型名额准入，总上限 4、后台最多 3。单次 HTTP callTimeout 取 45 秒与持久任务剩余时间中的较小值；排队后重新计算。取消顺序是取消本次 OkHttp Call、请求中断、释放该模型名额；不调用全局 cancelAll。供应商侧已执行的计算可能无法撤销，迟到结果仍必须丢弃。执行槽、模型槽和数据库租约都有独立续期。

## 残留回收

knowledge_index_generations 在分块暂存事务中记录版本/任务/执行代次。Worker 独立清理线程每分钟处理最多 100 个候选；只选择已被新执行代次取代、或任务已失败/取消/超时的代次，同时排除当前激活代次。先删除该租户、版本、代次的 Redis 键，再用短事务清理对应分块。

回收记录保留，last_cleanup_at 用于轮询公平性。这样旧 HTTP 操作在第一次清理后才产生的孤立写入，可在后续轮次再次回收。成功历史版本的正文和源文件保留，用于版本审计和历史引用；不将失败清理等同于删除业务文档。文件的物理删除及历史保留期需要单独的数据保留策略。
