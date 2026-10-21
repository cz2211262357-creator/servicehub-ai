import asyncio
import os
import socket
import subprocess
import sys
from pathlib import Path

import httpx
import pytest


@pytest.fixture
async def live_servers(env):
    ports, processes, logs = [], [], []
    root = Path(__file__).resolve().parents[3]
    try:
        for _ in range(2):
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", 0))
                port = sock.getsockname()[1]
            ports.append(port)
            log = (root / ".cache" / f"uvicorn-test-{port}.log").open("wb")
            logs.append(log)
            process = subprocess.Popen(
                [
                    sys.executable,
                    "-m",
                    "uvicorn",
                    "servicehub.main:app",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    str(port),
                    "--no-access-log",
                ],
                cwd=root,
                env=dict(
                    os.environ,
                    SERVICEHUB_DATABASE_URL=env.settings.database_url,
                    SERVICEHUB_REDIS_URL=env.settings.redis_url,
                    SERVICEHUB_ENVIRONMENT="test",
                    SERVICEHUB_MODEL_MODE="demo",
                    SERVICEHUB_RATE_LIMIT_REQUESTS="100000",
                ),
                stdout=log,
                stderr=log,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
            )
            processes.append(process)
            async with httpx.AsyncClient(trust_env=False) as client:
                for attempt in range(30):
                    try:
                        if (
                            await client.get(
                                f"http://127.0.0.1:{port}/api/v1/health/live", timeout=1
                            )
                        ).status_code == 200:
                            break
                    except httpx.TransportError:
                        pass
                    if process.poll() is not None:
                        pytest.fail(f"Uvicorn stopped; see .cache/uvicorn-test-{port}.log")
                    await asyncio.sleep(0.1)
                else:
                    pytest.fail("Uvicorn readiness timeout")
        yield ports
    finally:
        for process in processes:
            if os.name == "nt":
                subprocess.run(
                    ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                    capture_output=True,
                    creationflags=subprocess.CREATE_NO_WINDOW,
                )
            else:
                process.terminate()
        for process in processes:
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for log in logs:
            log.close()
