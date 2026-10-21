import math

import httpx

from servicehub.core.errors import BusinessError


class HTTPEmbeddingProvider:
    def __init__(self, settings, client=None):
        self.settings, self.client = settings, client

    async def embed(self, texts):
        cfg = self.settings
        if (
            not cfg.embedding_base_url
            or not cfg.embedding_model
            or not cfg.embedding_api_key.get_secret_value()
        ):
            raise BusinessError("embedding_not_configured", "尚未配置在线 Embedding 服务", 503)
        if not texts or len(texts) > 10:
            raise ValueError("Embedding batches must contain 1 to 10 texts")
        client = self.client or httpx.AsyncClient(
            timeout=cfg.model_timeout_seconds,
            limits=httpx.Limits(max_connections=8, max_keepalive_connections=4),
        )
        try:
            response = await client.post(
                cfg.embedding_base_url.rstrip("/") + "/embeddings",
                headers={"Authorization": "Bearer " + cfg.embedding_api_key.get_secret_value()},
                json={
                    "model": cfg.embedding_model,
                    "input": texts,
                    "dimensions": cfg.embedding_dimensions,
                    "encoding_format": "float",
                },
            )
            if response.status_code != 200:
                code = (
                    "embedding_rate_limited"
                    if response.status_code == 429
                    else "embedding_unavailable"
                )
                raise BusinessError(code, "Embedding 服务调用失败，请稍后重试", 503)
            rows = sorted(response.json()["data"], key=lambda row: row["index"])
            if [row["index"] for row in rows] != list(range(len(texts))):
                raise ValueError("Incomplete embedding response")
            vectors = [row["embedding"] for row in rows]
            for vector in vectors:
                if len(vector) != cfg.embedding_dimensions:
                    raise BusinessError(
                        "embedding_dimension_mismatch", "Embedding 维度与索引不匹配", 502
                    )
                if any(
                    isinstance(value, bool) or not math.isfinite(float(value)) for value in vector
                ):
                    raise ValueError("Non-finite embedding")
            return [[float(value) for value in vector] for vector in vectors]
        except httpx.TimeoutException:
            raise BusinessError(
                "embedding_timeout", "Embedding 服务响应超时，请重试", 504
            ) from None
        except httpx.TransportError:
            raise BusinessError(
                "embedding_unavailable", "Embedding 服务连接失败，请重试", 503
            ) from None
        except (ValueError, KeyError, TypeError):
            raise BusinessError(
                "embedding_invalid_response", "Embedding 服务返回异常", 502
            ) from None
        finally:
            if self.client is None:
                await client.aclose()
