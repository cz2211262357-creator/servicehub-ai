"""Frozen-policy retrieval comparison; scores are measured, never assumed to improve."""

import math
import random


def score_ranking(policy_ids, relevant_policy_ids):
    relevant = set(relevant_policy_ids)
    if not relevant:
        raise ValueError("Recall requires at least one relevant policy unit")
    ranking = list(dict.fromkeys(policy_ids))
    first = next((rank for rank, policy in enumerate(ranking[:10], 1) if policy in relevant), None)
    return {
        "ranked_policy_ids": ranking,
        "recall_at_5": len(set(ranking[:5]) & relevant) / len(relevant),
        "mrr_at_10": 1 / first if first else 0.0,
    }


def paired_bootstrap(baseline, candidate, *, samples=2000, seed=20261029):
    if not baseline or len(baseline) != len(candidate) or samples < 1:
        raise ValueError("Paired bootstrap requires equally sized nonempty samples")
    if any(
        not isinstance(value, (int, float)) or not math.isfinite(value)
        for value in [*baseline, *candidate]
    ):
        raise ValueError("Bootstrap samples must be finite")
    deltas = [right - left for left, right in zip(baseline, candidate, strict=True)]
    rng = random.Random(seed)
    replicates = sorted(
        sum(rng.choice(deltas) for _ in deltas) / len(deltas) for _ in range(samples)
    )
    return {
        "mean_delta": sum(deltas) / len(deltas),
        "ci95": [
            replicates[max(0, math.ceil(samples * 0.025) - 1)],
            replicates[math.ceil(samples * 0.975) - 1],
        ],
        "paired_samples": len(deltas),
        "bootstrap_replicates": samples,
        "seed": seed,
        "method": "paired percentile bootstrap, candidate minus baseline",
    }


def summarize_variants(records, variants):
    def value(row, metric):
        return 0.0 if row.get("error_code") else row[metric]

    scores = {}
    for variant in variants:
        rows = [record["variants"][variant] for record in records if variant in record["variants"]]
        scores[variant] = {
            "samples": len(rows),
            "error_count": sum(bool(row.get("error_code")) for row in rows),
            **{
                metric: sum(value(row, metric) for row in rows) / len(rows) if rows else None
                for metric in ("recall_at_5", "mrr_at_10")
            },
        }
    comparison = {}
    pairs = [
        record
        for record in records
        if "legacy" in record["variants"] and "new" in record["variants"]
    ]
    if pairs:
        comparison = {
            metric: paired_bootstrap(
                [value(row["variants"]["legacy"], metric) for row in pairs],
                [value(row["variants"]["new"], metric) for row in pairs],
            )
            for metric in ("recall_at_5", "mrr_at_10")
        }
    has_errors = any(value["error_count"] for value in scores.values())
    return {
        "variants": scores,
        "new_minus_legacy": comparison,
        "comparison_claim": "incomplete_due_to_errors"
        if has_errors
        else "measured_no_improvement_assumed",
    }


# Runtime dependencies are imported only by the command so the scoring functions
# and cache tests require neither services nor credentials.
async def benchmark_case(case, variants, retrieve, policy_by_document, *, timeout_seconds=30):
    import asyncio
    import time

    row = {"case_id": case["id"], "query": case["user_input"], "variants": {}}
    for variant in variants:
        started = time.monotonic()
        error = None
        evidence = []
        try:
            async with asyncio.timeout(timeout_seconds):
                evidence = await retrieve(case["user_input"], variant)
            if any(item.document_id not in policy_by_document for item in evidence):
                raise ValueError("Retrieved document is outside the frozen corpus")
        except Exception as exc:
            error = safe_error_code(exc)
            evidence = []
        result = score_ranking(
            [policy_by_document[item.document_id] for item in evidence], case["relevant_policy_ids"]
        )
        result.update(
            error_code=error,
            timeout=error == "query_timeout",
            latency_ms=round((time.monotonic() - started) * 1000, 3),
            runner_backend_substitution=False,
            score_kind={
                "legacy": "rrf_k60",
                "new": "rrf_k60",
                "bm25": "bm25std",
                "vector": "rank_surrogate_k60",
            }[variant],
            hits=[
                {
                    "rank": rank,
                    "chunk_id": item.chunk_id,
                    "policy_id": policy_by_document[item.document_id],
                    "score": item.score,
                    "raw_score": item.score if variant == "bm25" else None,
                }
                for rank, item in enumerate(evidence, 1)
            ],
        )
        row["variants"][variant] = result
    row["relevant_policy_ids"] = list(case["relevant_policy_ids"])
    if "legacy" in row["variants"] and "new" in row["variants"]:
        row["new_minus_legacy"] = {
            key: row["variants"]["new"][key] - row["variants"]["legacy"][key]
            for key in ("recall_at_5", "mrr_at_10")
        }
    return row


def safe_error_code(exc):
    import re

    if isinstance(exc, TimeoutError):
        return "query_timeout"
    code = getattr(exc, "code", type(exc).__name__)
    return (
        code
        if isinstance(code, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", code)
        else "benchmark_error"
    )


def source_provenance(root):
    import hashlib
    import subprocess
    from pathlib import Path

    from evals.servicehub.embedding_cache import digest

    root = Path(root)
    files = sorted(
        {*root.glob("backend/src/servicehub/**/*.py"), *root.glob("evals/servicehub/*.py")}
    )
    sources = {
        str(path.relative_to(root)).replace("\\", "/"): hashlib.sha256(
            path.read_bytes()
        ).hexdigest()
        for path in files
    }
    revision, dirty = None, None
    try:
        commit = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=root,
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        status = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=root,
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        if commit.returncode == 0:
            revision = commit.stdout.strip()
        if status.returncode == 0:
            dirty = bool(status.stdout.strip())
    except (OSError, subprocess.SubprocessError):
        pass
    return {
        "git_revision": revision,
        "git_dirty": dirty,
        "source_sha256": digest(sources),
        "source_files": sources,
        "scope": "all backend and evaluation Python sources",
    }


def write_report(path, report):
    import hashlib
    import os
    import tempfile
    from pathlib import Path

    from evals.servicehub.embedding_cache import canonical_bytes, make_private

    report.pop("report_sha256", None)
    report["report_sha256"] = hashlib.sha256(canonical_bytes(report)).hexdigest()
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor, temporary = tempfile.mkstemp(
        prefix=target.name + ".", suffix=".tmp", dir=target.parent
    )
    try:
        with os.fdopen(descriptor, "wb") as stream:
            make_private(temporary)
            stream.write(canonical_bytes(report))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
        os.chmod(target, 0o600)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return report


async def verify_index_vectors(redis, chunk_vectors, legacy_prefix, hybrid_prefix, encode):
    for chunk_id, vector in chunk_vectors:
        binary = encode(vector)
        for prefix in (legacy_prefix, hybrid_prefix):
            actual = await redis.execute_command(
                "HGET", prefix + str(chunk_id), "embedding", NEVER_DECODE=True
            )
            if actual != binary:
                raise ValueError("Both indices must contain identical cached FLOAT32 vectors")
    return len(chunk_vectors)


async def populate_legacy_vectors(environment, cache):
    """Write the authoritative exact chunks with the same cached real vectors."""
    import asyncio
    import hashlib

    from sqlalchemy import select

    from evals.servicehub.embedding_cache import digest
    from servicehub.knowledge.hybrid_index import HybridIndex
    from servicehub.knowledge.models import KnowledgeChunk
    from servicehub.knowledge.retrieval import VectorIndex

    cfg = environment.settings
    tenant = environment.tenants[0].id
    async with environment.sessions() as session:
        chunks = list(
            await session.scalars(
                select(KnowledgeChunk)
                .where(KnowledgeChunk.tenant_id == tenant)
                .order_by(KnowledgeChunk.document_id, KnowledgeChunk.ordinal)
            )
        )
    if not chunks:
        raise ValueError("Frozen corpus produced no authoritative chunks")
    vectors = await cache.embed([chunk.text for chunk in chunks])
    index = VectorIndex(environment.redis, cfg.embedding_model, cfg.embedding_dimensions)
    records = []
    for chunk, vector in zip(chunks, vectors, strict=True):
        await index.write(chunk.id, tenant, vector, version_id=chunk.version_id)
        environment.redis_keys.add(index.prefix + str(chunk.id))
        records.append(
            {
                "policy_id": environment.policy_by_document[chunk.document_id],
                "ordinal": chunk.ordinal,
                "page": chunk.page,
                "text_sha256": hashlib.sha256(chunk.text.encode()).hexdigest(),
                "float32_vector_sha256": hashlib.sha256(index.encode(vector)).hexdigest(),
            }
        )
    # Redis indexing is asynchronous: verify all owned vectors are searchable,
    # rather than comparing against a silently empty baseline vector channel.
    expected = {chunk.id for chunk in chunks}
    for attempt in range(51):
        found = set(
            await index.search(
                tenant,
                vectors[0],
                limit=len(chunks),
                version_ids=sorted({chunk.version_id for chunk in chunks}),
            )
        )
        if found == expected:
            break
        if attempt == 50:
            raise RuntimeError("legacy_vector_index_incomplete")
        await asyncio.sleep(0.1)
    hybrid = HybridIndex(environment.redis, cfg.embedding_model, cfg.embedding_dimensions)
    verified = await verify_index_vectors(
        environment.redis,
        [(chunk.id, vector) for chunk, vector in zip(chunks, vectors, strict=True)],
        index.prefix,
        hybrid.prefix,
        index.encode,
    )
    records.sort(key=lambda value: (value["policy_id"], value["ordinal"]))
    return {
        "byte_identical_vectors_verified": verified,
        "chunk_count": len(chunks),
        "chunks": records,
        "chunks_sha256": digest(records),
        "vector_source": "same validated real-embedding cache as v2 indexer",
    }


def runtime_retriever(environment, principal, cache):
    from servicehub.knowledge.hybrid_index import HybridIndex
    from servicehub.knowledge.retrieval import Retriever, VectorIndex

    cfg = environment.settings
    old = Retriever(
        environment.sessions,
        environment.redis,
        cfg.model_copy(update={"retrieval_backend": "mysql_legacy"}),
        embedding=cache,
        retrieval_backend="mysql_legacy",
    )
    new = Retriever(
        environment.sessions,
        environment.redis,
        cfg.model_copy(update={"retrieval_backend": "redis_hybrid"}),
        embedding=cache,
        retrieval_backend="redis_hybrid",
    )
    hybrid = HybridIndex(environment.redis, cfg.embedding_model, cfg.embedding_dimensions)
    vector = VectorIndex(environment.redis, cfg.embedding_model, cfg.embedding_dimensions)

    async def retrieve(query, variant):
        if variant == "legacy":
            return await old.search(principal, query, limit=30)
        if variant == "new":
            return await new.search(principal, query, limit=30)
        versions = await new.active_versions(principal)
        if variant == "bm25":
            rankings = await hybrid.search_keyword(
                principal.tenant_id, query, limit=30, version_ids=versions, with_scores=True
            )
        elif variant == "vector":
            embedded = (await cache.embed([query]))[0]
            ids = await vector.search(principal.tenant_id, embedded, limit=30, version_ids=versions)
            rankings = [(chunk_id, 1 / (60 + rank)) for rank, chunk_id in enumerate(ids, 1)]
        else:
            raise ValueError("Unknown retrieval variant")
        return await new.finalize(principal, rankings)

    return retrieve


async def run_retrieval(args):
    import json
    from contextlib import ExitStack
    from datetime import UTC, datetime
    from pathlib import Path
    from unittest.mock import patch

    from evals.servicehub.cli import evaluation_settings
    from evals.servicehub.embedding_cache import CachedEmbeddingProvider, digest, prewarm_corpus
    from evals.servicehub.fixtures import evaluation_environment
    from evals.servicehub.schema import DATA_ROOT, load_dataset
    from servicehub.ai.embedding import HTTPEmbeddingProvider
    from servicehub.auth.schemas import Principal

    report = {
        "schema_version": "1.0",
        "created_at": datetime.now(UTC).isoformat(),
        "status": "initializing",
        "cases": [],
        "requested_variants": args.backends,
        "policy_samples_expected": 30,
        "synthetic": True,
        "metric_definitions": {
            "recall_at_5": "unique relevant policy units in first 5 unique retrieved policy units / all relevant policy units",
            "mrr_at_10": "reciprocal rank of first relevant unit in first 10 unique policy units",
            "error_policy": "failed queries contribute zero; errors and measured partial coverage reported separately",
        },
        "pipeline": {
            "latency_scope": "warmed shared embedding cache; excludes real embedding API and corpus indexing time",
            "variant_order": args.backends,
            "candidate_limit_per_channel": 30,
            "rrf_k": 60,
            "comparison_scope": "pipeline upgrade, not an isolated BM25-engine experiment",
            "legacy": "unchanged MySQL ngram MATCH with existing bounded LIKE fallback + same real HNSW + RRF60",
            "new": "Redis BM25STD and query term normalization + HNSW + RRF60",
            "bm25": "new lexical channel only",
            "vector": "same legacy HNSW channel only",
            "legacy_like_fallback_observation": "part of declared baseline; not separately instrumented",
            "authorization": "active-version prefilter and final SQL authorization for every variant",
        },
        "report_hash_method": "SHA256 of sorted compact UTF-8 JSON excluding report_sha256",
    }
    cache = None
    try:
        if not math.isfinite(args.query_timeout_seconds) or args.query_timeout_seconds <= 0:
            raise ValueError("Query timeout must be positive")
        settings = evaluation_settings("online", args.env_file)
        cases, metadata = load_dataset("cases")
        selected = [case for case in cases if case["category"] == "policy"]
        if len(selected) != 30:
            raise ValueError("Frozen retrieval subset must contain exactly 30 policy cases")
        manifest = json.loads((DATA_ROOT / "manifest.json").read_text(encoding="utf-8"))
        report.update(
            dataset=metadata,
            source=source_provenance(settings.project_root),
            policy_subset_case_ids=[case["id"] for case in selected],
            qrels_sha256=digest(
                {case["id"]: sorted(case["relevant_policy_ids"]) for case in selected}
            ),
            corpus_sha256=digest(
                [
                    {key: document[key] for key in ("policy_id", "sha256")}
                    for document in manifest["documents"]
                ]
            ),
            embedding_model=settings.embedding_model,
            embedding_dimensions=settings.embedding_dimensions,
            embedding_model_version_source="configured provider identifier; upstream server build is not exposed by this provider interface",
        )
        cache = CachedEmbeddingProvider(HTTPEmbeddingProvider(settings), args.embedding_cache)
        report["corpus_prewarm"] = await prewarm_corpus(cache, manifest)
        import servicehub.knowledge.indexer as indexer_module
        import servicehub.knowledge.retrieval as retrieval_module

        with ExitStack() as stack:
            for module in (indexer_module, retrieval_module):
                stack.enter_context(
                    patch.object(module, "HTTPEmbeddingProvider", lambda _settings: cache)
                )
            async with evaluation_environment(settings, manifest) as environment:
                report["corpus_index"] = await populate_legacy_vectors(environment, cache)
                # Prewarm once so every timed variant uses exactly the same query vector.
                query_vectors = await cache.embed([case["user_input"] for case in selected])
                import hashlib
                import struct

                query_records = [
                    {
                        "case_id": case["id"],
                        "text_sha256": hashlib.sha256(case["user_input"].encode()).hexdigest(),
                        "float32_vector_sha256": hashlib.sha256(
                            struct.pack(f"<{settings.embedding_dimensions}f", *vector)
                        ).hexdigest(),
                    }
                    for case, vector in zip(selected, query_vectors, strict=True)
                ]
                report["query_vectors"] = query_records
                report["query_vectors_sha256"] = digest(query_records)
                context = await environment.prepare_case(selected[0])
                actor = context["actor"]
                principal = Principal(actor.id, actor.tenant_id, "customer")
                retrieve = runtime_retriever(environment, principal, cache)
                report["status"] = "running"
                for case in selected:
                    result = await benchmark_case(
                        case,
                        args.backends,
                        retrieve,
                        environment.policy_by_document,
                        timeout_seconds=args.query_timeout_seconds,
                    )
                    report["cases"].append(result)
                    report["summary"] = summarize_variants(report["cases"], args.backends)
                    report["embedding_cache"] = {"sha256": cache.fingerprint, "stats": cache.stats}
                    write_report(args.report, report)
        errors = sum(row["error_count"] for row in report["summary"]["variants"].values())
        report["status"] = "completed_with_errors" if errors else "completed"
    except Exception as exc:
        report["status"], report["error_code"] = "failed", safe_error_code(exc)
    finally:
        report["finished_at"] = datetime.now(UTC).isoformat()
        report["policy_samples_measured"] = len(report["cases"])
        report["summary"] = summarize_variants(report["cases"], args.backends)
        if cache is not None:
            report["embedding_cache"] = {"sha256": cache.fingerprint, "stats": cache.stats}
        write_report(args.report, report)
    print(
        json.dumps(
            {
                "report": str(Path(args.report).resolve()),
                "status": report["status"],
                "report_sha256": report["report_sha256"],
                "summary": report["summary"],
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0 if report["status"] == "completed" and len(report["cases"]) == 30 else 1


def register_cli(subparsers):
    import argparse
    from pathlib import Path

    def variants(value):
        result = value.split(",")
        if len(set(result)) != len(result) or not set(result) <= {
            "legacy",
            "new",
            "bm25",
            "vector",
        }:
            raise argparse.ArgumentTypeError(
                "Choose unique comma-separated legacy,new,bm25,vector variants"
            )
        return result

    parser = subparsers.add_parser(
        "retrieval", help="Measure frozen 30-policy Recall@5 and MRR@10 with real cached embeddings"
    )
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--embedding-cache", type=Path, required=True)
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--backends", type=variants, default=["legacy", "new", "bm25", "vector"])
    parser.add_argument("--query-timeout-seconds", type=float, default=30)
    parser.set_defaults(handler=run_retrieval)
    return parser
