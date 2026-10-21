# PyCharm 与启动

使用 git clone 得到的项目根目录。使用 PyCharm 打开该目录，在 Python Interpreter 选择现有解释器 .venv\Scripts\python.exe；API 与 Worker 的工作目录均为项目根目录。已经提供 .run/API.run.xml 与 .run/Worker.run.xml，可从运行配置选择。配置由项目根 .env 加载；不要把密钥填进可提交的运行配置 XML。

首次安装或重新同步依赖：

~~~powershell
Set-Location '<项目目录>'
.\scripts\bootstrap.ps1
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
~~~

已有 .env 时不要覆盖。所有 uv 托管 Python、.venv、包缓存、前端 node_modules、私有上传和数据库数据都在项目中；scripts/env.ps1 设置这些路径。Node/npm 使用本机程序，未向 Anaconda 安装项目包。

## 开发方式

~~~powershell
. .\scripts\env.ps1
docker compose up -d --wait mysql redis redis-test
uv run alembic -c backend/alembic.ini upgrade head
uv run python scripts/seed.py
~~~

在三个终端分别运行，或在 PyCharm 启动 API/Worker 并使用终端启动前端：

~~~powershell
.\scripts\dev.ps1 -Service api
.\scripts\dev.ps1 -Service worker
.\scripts\dev.ps1 -Service frontend
~~~

打开 http://localhost:5173。API 文档在 http://127.0.0.1:8000/api/docs。前端 Vite 代理 HTTP 与 WebSocket，因此浏览器不需要直接跨域连接后端。后台文档索引和 AI 回复需要 Worker；只启动 API 不会产生模型回答。

## Docker 完整网站

~~~powershell
. .\scripts\env.ps1
docker compose --profile app build
docker compose up -d --wait mysql redis
docker compose --profile app run --rm --no-deps api alembic -c backend/alembic.ini upgrade head
docker compose --profile app run --rm --no-deps api python scripts/seed.py
docker compose --profile app up -d --wait api worker web
~~~

打开 http://localhost:8080。入口为 Nginx；API 两个 Uvicorn 进程；Worker 独立进程；上传卷共享而非静态公开。迁移和初始化必须显式执行，容器重启不会自动重复创建业务数据。

本机直连 Docker Hub 曾出现授权端点超时，可用 Google 官方 Docker Hub 缓存传递基础镜像参数：

~~~powershell
docker compose --profile app build --build-arg PYTHON_IMAGE=mirror.gcr.io/library/python:3.12-slim --build-arg NODE_IMAGE=mirror.gcr.io/library/node:26-alpine --build-arg NGINX_IMAGE=mirror.gcr.io/library/nginx:1.28-alpine
~~~

若重新创建了 API 而保留旧 Web 容器，执行 docker compose --profile app restart web，使 Nginx 重新解析容器地址。电脑重启后先启动 Docker Desktop，再运行上述启动命令。

停止容器保留项目数据：docker compose --profile app stop。不要使用带删除数据含义的清理命令来“重置问题”。

## 账号与真实模型

seed.py 输出两个企业的实际编号。每个企业新建 admin@example.com、agent@example.com、agent2@example.com、customer@example.com、customer2@example.com。默认开发密码 ServiceHub-2026!，可在首次初始化前设置 SERVICEHUB_SEED_PASSWORD；已有账号密码不会被重置。初始化仅允许 development。

.env 中分别配置 SERVICEHUB_MODEL_API_KEY 和 SERVICEHUB_EMBEDDING_API_KEY，可以使用同一有权限的百炼 Key。当前地址为北京地域兼容接口，模型 qwen-plus 与 text-embedding-v4，向量维度 1024。不同地域或工作空间需使用对应地址。Key 只留在本地 .env，不进入浏览器、日志、Git 或 Docker 镜像。模型配置变更后重启 API/Worker；Embedding 模型或维度变更需要重新上传新版本并重建索引。

官方协议：[Embedding](https://help.aliyun.com/en/model-studio/text-embedding-synchronous-api)、[Function Calling](https://help.aliyun.com/zh/model-studio/qwen-function-calling)。
