ARG PYTHON_IMAGE=python:3.12-slim
FROM ${PYTHON_IMAGE}
COPY --from=ghcr.io/astral-sh/uv:0.11.16 /uv /usr/local/bin/uv
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_CACHE_DIR=/app/.cache/uv \
    UV_PYTHON_PREFERENCE=only-system UV_PYTHON_DOWNLOADS=never \
    PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY pyproject.toml uv.lock uv.toml .python-version ./
COPY backend ./backend
COPY scripts ./scripts
RUN uv sync --frozen --no-dev --python /usr/local/bin/python
ENV PATH="/app/.venv/bin:$PATH" TMPDIR=/app/.cache/tmp
RUN useradd --create-home --uid 10001 servicehub \
    && mkdir -p /app/.data/uploads /app/.cache/tmp \
    && chown -R servicehub:servicehub /app/.data /app/.cache
USER servicehub
HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/api/v1/health/ready', timeout=4)" || exit 1
CMD ["uvicorn", "servicehub.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2"]
