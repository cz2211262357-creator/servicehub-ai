"""CLI entry point. Online secrets are read privately by Settings, never printed."""

import argparse
import asyncio
import inspect
import json
import os
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

from evals.servicehub.runner import EvaluationRunner
from servicehub.core.config import Settings


def evaluation_settings(provider, env_file=None):
    workspace = Path(__file__).resolve().parents[2]
    # A managed worktree lives at R/.cache/worktrees/W; normal checkouts use their root.
    repository = (
        workspace.parents[2]
        if workspace.parent.name == "worktrees" and workspace.parent.parent.name == ".cache"
        else workspace
    )
    settings = Settings(
        _env_file=env_file or repository / ".env",
        project_root=workspace,
        environment="test",
        model_mode="online" if provider == "online" else "demo",
    )
    database = urlsplit(settings.database_url)
    test_database = urlunsplit(database._replace(path="/servicehub_test"))
    return settings.model_copy(
        update={
            "database_url": os.environ.get("SERVICEHUB_TEST_DATABASE_URL", test_database),
            "redis_url": os.environ.get("SERVICEHUB_TEST_REDIS_URL", "redis://127.0.0.1:6380/0"),
            "retrieval_backend": "redis_hybrid",
        }
    )


async def run_agent(args):
    settings = evaluation_settings(args.provider, args.env_file)
    embedding_provider = None
    if args.provider == "online":
        # Parent benchmark shares this real-embedding cache across controlled comparisons.
        try:
            from evals.servicehub.embedding_cache import CachedEmbeddingProvider
        except ImportError:
            CachedEmbeddingProvider = None
        if CachedEmbeddingProvider is not None:
            from servicehub.ai.embedding import HTTPEmbeddingProvider

            embedding_provider = CachedEmbeddingProvider(
                HTTPEmbeddingProvider(settings), args.embedding_cache
            )
            from evals.servicehub.embedding_cache import prewarm_corpus
            from evals.servicehub.schema import DATA_ROOT

            manifest = json.loads((DATA_ROOT / "manifest.json").read_text(encoding="utf-8"))
            await prewarm_corpus(embedding_provider, manifest)
    runner = EvaluationRunner(
        settings, args.provider, args.judge, embedding_provider=embedding_provider
    )
    report = await runner.run(
        args.report, args.dataset, set(args.case_id or []), set(args.category or []), args.limit
    )
    print(
        json.dumps(
            {
                "report": str(Path(args.report).resolve()),
                "provider": args.provider,
                "report_sha256": report["report_sha256"],
                "summary": report["summary"],
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0 if all(case["checks"]["task_pass"] for case in report["cases"]) else 1


def register_agent_command(commands):
    parser = commands.add_parser("agent", help="Execute real Agent with synthetic cases")
    parser.add_argument("--provider", choices=["controlled", "online"], required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--dataset", choices=["cases", "dev"], default="cases")
    parser.add_argument("--judge", choices=["off", "online"], default="off")
    parser.add_argument("--case-id", action="append")
    parser.add_argument("--category", action="append")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--env-file", type=Path)
    parser.add_argument(
        "--embedding-cache", type=Path, default=Path(".cache/evaluation-embeddings.json")
    )
    parser.set_defaults(handler=run_agent)
    return parser


def build_parser(extra_register=None):
    parser = argparse.ArgumentParser(description="ServiceHub frozen synthetic evaluation")
    commands = parser.add_subparsers(dest="command", required=True)
    register_agent_command(commands)
    if extra_register:
        extra_register(commands)
    else:
        # Optional benchmark module supplies its own parser without owning this module.
        try:
            from evals.servicehub.retrieval_benchmark import register_cli
        except ImportError:
            register_cli = None
        if register_cli:
            register_cli(commands)
    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()
    if getattr(args, "limit", None) is not None and args.limit < 1:
        parser.error("--limit must be positive")
    result = args.handler(args)
    return asyncio.run(result) if inspect.isawaitable(result) else result


if __name__ == "__main__":
    raise SystemExit(main())
