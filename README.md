# ServiceHub AI · Python

**React / TypeScript + FastAPI + MySQL + Redis 的电商售后客服平台。** 将知识问答、本人订单查询、售后资格校验、草稿准备、客户确认和人工接待串成完整流程，接入真实百炼对话与 Embedding API。

这是独立的 `python-quality` 分支。Java / Spring Boot 版本继续保留在 [main](https://github.com/cz2211262357-creator/servicehub-ai/tree/main)；两个版本使用独立数据库和端口。Python 的模型工具循环由本项目实现，不依赖 LangChain / LangGraph，也不包含独立的 InsightPilot 问数项目。

[![Python verification](https://github.com/cz2211262357-creator/servicehub-ai/actions/workflows/ci.yml/badge.svg?branch=python-quality)](https://github.com/cz2211262357-creator/servicehub-ai/actions/workflows/ci.yml?query=branch%3Apython-quality)

## 本轮升级

- **Redis BM25STD + HNSW + RRF**：中文词法与向量双路检索，租户及有效版本过滤，新索引完整写入后才激活；保留 MySQL ngram 基线和旧向量用于对照、回滚。
- **可信售后规则**：按服务端品类、签收时间、完好性、已确认质量事实和保修期限判定；准备与确认都检查当前资格、权限和执行版本。缺事实时澄清或人工核验，模型不能自行改写订单事实。
- **多轮草稿状态**：普通追问保留已完成草稿，明确撤回或换单使旧草稿失效；失败任务、旧执行和人工接管不能生成新的可确认草稿。聊天中的“同意”不替代客户点击确认。
- **离线评估**：30 份版本化售后政策、150 条固定案例（5 类各 30）和独立开发集，运行真实 HTTP → Job → Agent → 工具 → 数据库链路，记录逐例断言和可选质量裁判。
- **在线观测**：模型、工具与检索逐次持久 Trace；管理端查看失败率、人工接管率、P95、Token 覆盖率和配置后的费用估计。供应商或单价缺失记为“未知”。

合成商家和订单用于本地运行及回归，不是接入了真实 ERP。数据库、索引、队列、权限和模型调用均为实际执行；未连接外部支付退款和物流下单系统。

## 检索实测

在同一语料、分块、qrels、30 条政策问题、真实 `text-embedding-v4` / 1024 维向量下比较，两路候选各 30、RRF k=60：

| 检索方案 | Recall@5 | MRR@10 |
|---|---:|---:|
| MySQL ngram + HNSW + RRF（原版） | 0.8000 | 0.8444 |
| Redis BM25STD + HNSW + RRF（升级） | **0.9000** | 0.8678 |
| BM25STD 单路 | 0.8000 | 0.7281 |
| HNSW 单路 | 0.8556 | **0.9167** |

Recall 的配对差值为 +0.10（95% bootstrap CI [0.0167, 0.1833]）；MRR 差值 +0.0233 的区间 [-0.0500, 0.1000] 跨 0，不能据此断言稳定改善。向量单路本次 MRR 更高，融合并非每项指标都最好。中文查询归一化也同时变更，因此比较的是完整检索方案。30 条合成问题不代表生产泛化效果。

最终150条真实评估：128/150通过业务硬断言，118/150同时满足严格回答质量；异常与多轮仍是主要短板。完整测量口径、分类结果和失败分析见 [升级验证报告](docs/verification/python-quality-upgrade.md)。

## 技术与结构

| 层次 | 技术 |
|---|---|
| 客户、客服与管理端 | React、TypeScript、TanStack Query、WebSocket |
| API / 数据 | FastAPI、Pydantic、SQLAlchemy asyncio、MySQL 8.4、Alembic |
| 检索与任务 | Redis 8.4 Search / BM25STD / HNSW、RRF、Streams、Outbox、数据库租约围栏 |
| 模型 | 百炼 qwen-plus、text-embedding-v4、受控 Tool Calling |
| 质量与交付 | pytest、真实依赖与并发验证、LLM-as-Judge、uv、Docker Compose、GitHub Actions |

`backend/` 是 Python API 与 Worker；`frontend/` 是共用前端；`evals/servicehub/` 保存政策和评估器；`scripts/` 提供初始化、回填与测量命令。`backend-java/` 是保留的 Java 版本，Python 部署不启动它。

## 启动

```bash
git clone --branch python-quality https://github.com/cz2211262357-creator/servicehub-ai.git
cd servicehub-ai
cp .env.example .env
# 在本地 .env 填写百炼对话与 Embedding Key；不要提交 .env。
docker compose --profile app build
docker compose up -d --wait mysql redis
docker compose --profile app run --rm --no-deps api alembic -c backend/alembic.ini upgrade head
docker compose --profile app run --rm --no-deps api python scripts/seed.py
docker compose --profile app up -d --wait api worker web
```

网站 `http://localhost:8080`，API 文档 `http://127.0.0.1:8000/api/docs`。默认开发账号和部署限制见 [Python 操作说明](docs/operations/Python版本使用与验收.md)。升级已有库前应先备份；旧订单没有的事实保持未知，不自动猜测补齐。

需要加载本轮 30 份政策时，在已安装 uv 的源码目录运行：

```bash
uv sync --all-groups --frozen
uv run python -m scripts.seed_quality
```

该命令只在 development 环境导入独立的“星桥商城（合成业务资料）”及新合成订单，不改原商家账号、订单事实或密码；实际索引由运行中的 Worker 执行。

## 复现评估

```bash
docker compose --profile test up -d --wait mysql redis-test
uv sync --all-groups --frozen
uv run pytest -q
uv run python scripts/export_openapi.py
npm --prefix frontend ci
npm --prefix frontend run generate:api:python
npm --prefix frontend test -- --run
npm --prefix frontend run build

# 使用真实百炼 Embedding；缓存只复用实际向量
uv run python -m evals.servicehub.cli retrieval --report .cache/retrieval.json --embedding-cache .cache/embeddings.json --backends legacy,new,bm25,vector
# 受控模式仅验证工程链路，不能作为模型准确率
uv run python -m evals.servicehub.cli agent --provider controlled --report .cache/controlled.json
# 真实模型及隔离裁判，会产生 API 用量；每例实时写入报告
uv run python -m evals.servicehub.cli agent --provider online --judge online --report .cache/agent-online.json --embedding-cache .cache/embeddings.json
```

评估只允许 `servicehub_test` 与独立 Redis 6380；用自有临时租户初始化并清理。请勿指向真实客户库。题库与参考答案在模型执行后评分时使用，不能注入在线 Agent。详情见 [评估数据说明](evals/servicehub/README.md)。

## 范围

当前是可运行、可验证的工程项目；商家政策为合成业务承诺，实际接入须使用授权订单事实和商家审核后的资料。当前仅支持文本 PDF，不含扫描 OCR。跨主机恢复、长期稳定性与生产容量尚未验证；LLM 裁判不是人工审核替代品。外网部署需 HTTPS、Secure Cookie、独立凭证及备份策略。
