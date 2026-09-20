"""Retrieval quality baseline for the shared demo corpus.

Run from backend/ai-service:

    python -m eval.run_eval                       # retrieval metrics + markdown report
    python -m eval.run_eval --check-corpus        # verify stored chunk counts match the chunker
    python -m eval.run_eval --min docHit@5=0.6    # fail the build if a metric regresses
"""
from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

from app import (
    QueryRequest, SourceDocument, chunk_pages, local_answer, rank_chunks, select_context,
)

SEED_CORPUS = Path(__file__).resolve().parents[2] / "business-service/src/main/resources/seed/documents.json"
DEFAULT_DATASET = Path(__file__).resolve().parent / "dataset.jsonl"
SEED_CHUNK_SIZE = 512
EVAL_KS = (1, 3, 5, 10)


def load_corpus(path: Path = SEED_CORPUS) -> list[SourceDocument]:
    return [SourceDocument(id=item["id"], name=item["name"], content=item["content"], kbId=item["kbId"])
            for item in json.loads(path.read_text(encoding="utf-8"))]


CASE_FIELDS = {"id", "question", "kind", "gold", "kbId", "shouldRefuse"}


def load_dataset(path: Path = DEFAULT_DATASET) -> list[dict]:
    cases = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        case = json.loads(line)
        missing = CASE_FIELDS - {"kbId", "shouldRefuse"} - case.keys()
        unexpected = case.keys() - CASE_FIELDS
        if missing or unexpected or set(case["gold"]) != {"docs", "passages"}:
            raise ValueError(f"用例 {case.get('id', line[:40])} 字段不合规：缺少 {missing or '-'}，多余 {unexpected or '-'}")
        if not isinstance(case["gold"]["docs"], list) or not isinstance(case["gold"]["passages"], list):
            raise ValueError(f"用例 {case['id']} 的 gold 必须是列表")
        cases.append(case)
    return cases


def normalize(text: str) -> str:
    return "".join(text.split())


def documents_for(case: dict, corpus: list[SourceDocument]) -> list[SourceDocument]:
    kb = case.get("kbId")
    return corpus if kb in (None, "") else [doc for doc in corpus if doc.kbId == kb]


@dataclass
class CaseResult:
    id: str
    kind: str
    should_refuse: bool
    gold_docs: list[str] = field(default_factory=list)
    gold_passages: list[str] = field(default_factory=list)
    ranked_docs: list[str] = field(default_factory=list)
    ranked_passage_hits: list[bool] = field(default_factory=list)
    ranked_new_hits: list[bool] = field(default_factory=list)
    passage_found: dict[str, int] = field(default_factory=dict)
    returned: int = 0
    seconds: float = 0.0
    answer: str = ""


def run_case(case: dict, corpus: list[SourceDocument], top_k: int, options: dict) -> CaseResult:
    result = CaseResult(id=case["id"], kind=case["kind"], should_refuse=case.get("shouldRefuse", False),
                        gold_docs=list(case["gold"]["docs"]), gold_passages=list(case["gold"]["passages"]))
    documents = documents_for(case, corpus)
    request = QueryRequest(question=case["question"], documents=documents, topK=top_k,
                           chunkSize=options["chunkSize"], hybridSearch=options["hybridSearch"],
                           reranking=options["reranking"])
    started = time.perf_counter()
    ranked = rank_chunks(request)
    selected = select_context(ranked, request.topK)
    result.seconds = time.perf_counter() - started

    wanted = {normalize(passage) for passage in result.gold_passages}
    covered: set[str] = set()
    for item in ranked[:max(EVAL_KS)]:
        text = normalize(item.chunk.text)
        found = {passage for passage in wanted if passage in text}
        result.ranked_docs.append(item.chunk.document_id)
        result.ranked_passage_hits.append(bool(found))
        result.ranked_new_hits.append(bool(found - covered))
        covered |= found
        for passage in found:
            result.passage_found.setdefault(passage, len(result.ranked_passage_hits))
    result.returned = len(selected)
    if selected:
        result.answer = local_answer(case["question"], selected)
    return result


def doc_recall(result: CaseResult, k: int) -> float | None:
    if not result.gold_docs:
        return None
    found = {document for document in result.ranked_docs[:k] if document in set(result.gold_docs)}
    return len(found) / len(result.gold_docs)


def doc_hit(result: CaseResult, k: int) -> float | None:
    if not result.gold_docs:
        return None
    return float(any(document in set(result.gold_docs) for document in result.ranked_docs[:k]))


def passage_recall(result: CaseResult, k: int) -> float | None:
    if not result.gold_passages:
        return None
    hit = sum(1 for passage, rank in result.passage_found.items() if rank <= k)
    return hit / len(result.gold_passages)


def reciprocal_rank(result: CaseResult, k: int) -> float | None:
    if not result.gold_passages:
        return None
    for rank, hit in enumerate(result.ranked_passage_hits[:k], start=1):
        if hit:
            return 1.0 / rank
    return 0.0


def ndcg(result: CaseResult, k: int) -> float | None:
    if not result.gold_passages:
        return None
    gains = result.ranked_new_hits[:k]
    dcg = sum(1.0 / math.log2(rank + 1) for rank, hit in enumerate(gains, start=1) if hit)
    ideal = sum(1.0 / math.log2(rank + 1) for rank in range(1, min(len(result.gold_passages), k) + 1))
    return dcg / ideal


def refusal(result: CaseResult) -> float | None:
    if not result.should_refuse:
        return None
    return float(result.returned == 0)


def false_refusal(result: CaseResult) -> float | None:
    if result.should_refuse or not result.gold_docs:
        return None
    return float(result.returned == 0)


def answer_coverage(result: CaseResult) -> float | None:
    if not result.gold_passages or result.returned == 0:
        return None
    answer = normalize(result.answer)
    return sum(1 for passage in result.gold_passages if passage in answer) / len(result.gold_passages)


METRICS = {"docRecall": doc_recall, "docHit": doc_hit, "passageRecall": passage_recall, "rr": reciprocal_rank,
           "ndcg": ndcg, "refusal": refusal, "falseRefusal": false_refusal, "answerCoverage": answer_coverage}
SCALAR_METRICS = ("refusal", "falseRefusal", "answerCoverage")


def aggregate(results: list[CaseResult], ks: tuple[int, ...]) -> dict[str, float]:
    scores: dict[str, list[float]] = {}
    for name, function in METRICS.items():
        if name in SCALAR_METRICS:
            values = [value for value in (function(result) for result in results) if value is not None]
        else:
            for k in ks:
                per_k = [value for value in (function(result, k) for result in results) if value is not None]
                if per_k:
                    scores[f"{name}@{k}"] = statistics.fmean(per_k)
            continue
        if values:
            scores[name] = statistics.fmean(values)
    seconds = [result.seconds for result in results]
    scores["latencyMeanMs"] = statistics.fmean(seconds) * 1000
    scores["latencyP95Ms"] = sorted(seconds)[max(0, math.ceil(0.95 * len(seconds)) - 1)] * 1000
    scores["cases"] = float(len(results))
    return scores


def report(results: list[CaseResult], overall: dict, by_kind: dict, options: dict, corpus: list[SourceDocument]) -> str:
    chunks = sum(len(chunk_pages(document.content, options["chunkSize"])) for document in corpus)
    lines = [
        "# 检索评测基线",
        "",
        "> 由 `python -m eval.run_eval --out docs/eval-baseline.md` 生成，请勿手工编辑；指标解读见 `docs/roadmap.md` 的 S1/S2 结论。",
        "",
        f"- 语料：{len(corpus)} 篇文档（`business-service/src/main/resources/seed/documents.json`），"
        f"{options['chunkSize']} 字符分段预算下共 {chunks} 个片段",
        f"- 用例：{int(overall['cases'])} 条；混合检索 {options['hybridSearch']}，重排 {options['reranking']}",
        "- 覆盖范围：检索层与本地摘录层指标；生成式答案质量需配置模型网关后另行评测",
        "",
        "## 总体指标",
        "",
        "| 指标 | 数值 |",
        "|---|---|",
    ]
    for key in sorted(overall):
        if key in ("cases", "latencyMeanMs", "latencyP95Ms"):
            continue
        lines.append(f"| {key} | {overall[key]:.3f} |")
    lines += ["", f"单查询检索耗时：均值 {overall['latencyMeanMs']:.0f} ms / P95 {overall['latencyP95Ms']:.0f} ms", "",
              "## 按用例类型", "",
              "| 类型 | 用例数 | docHit@5 | passageRecall@5 | ndcg@10 | refusal |", "|---|---|---|---|---|---|"]
    for kind, scores in sorted(by_kind.items()):
        cell = lambda key: f"{scores[key]:.3f}" if key in scores else "-"
        lines.append(f"| {kind} | {int(scores['cases'])} | {cell('docHit@5')} | {cell('passageRecall@5')} | "
                     f"{cell('ndcg@10')} | {cell('refusal')} |")
    misses = [result.id for result in results
              if result.gold_docs and not doc_hit(result, 5)]
    leaked = [result.id for result in results if refusal(result) == 0.0]
    lines += ["", "## 需要关注的用例", "",
              f"- 前 5 段未命中任何期望文档（{len(misses)}）：" + ("、".join(misses) if misses else "无"),
              f"- 应拒答但返回了引用（{len(leaked)}）：" + ("、".join(leaked) if leaked else "无"), ""]
    return "\n".join(lines)


def check_corpus(corpus_path: Path = SEED_CORPUS) -> list[str]:
    stored = json.loads(corpus_path.read_text(encoding="utf-8"))
    problems = []
    for item in stored:
        expected = len(chunk_pages(item["content"], SEED_CHUNK_SIZE))
        if expected != item["chunkCount"]:
            problems.append(f"{item['id']}: 记录 {item['chunkCount']} 片段，实际 {expected}")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--corpus", type=Path, default=SEED_CORPUS)
    parser.add_argument("--out", type=Path, help="write the markdown report to this file")
    parser.add_argument("--json", action="store_true", help="print metrics as JSON instead of markdown")
    parser.add_argument("--chunk-size", type=int, default=SEED_CHUNK_SIZE)
    parser.add_argument("--no-hybrid", action="store_true")
    parser.add_argument("--no-rerank", action="store_true")
    parser.add_argument("--only", help="only run cases whose id or kind contains this value")
    parser.add_argument("--check-corpus", action="store_true")
    parser.add_argument("--min", action="append", default=[], metavar="METRIC=VALUE",
                        help="fail if a metric drops below VALUE, e.g. --min docHit@5=0.6")
    parser.add_argument("--max", action="append", default=[], metavar="METRIC=VALUE",
                        help="fail if a metric rises above VALUE, e.g. --max falseRefusal=0.15")
    args = parser.parse_args(argv)

    if args.check_corpus:
        problems = check_corpus(args.corpus)
        for problem in problems:
            print(problem)
        print("语料片段数与分片器一致" if not problems else f"发现 {len(problems)} 处片段数漂移")
        return 1 if problems else 0

    corpus = load_corpus(args.corpus)
    cases = load_dataset(args.dataset)
    if args.only:
        cases = [case for case in cases if args.only in case["id"] or args.only == case["kind"]]
    if not cases:
        print("没有匹配的评测用例", file=sys.stderr)
        return 2
    options = {"chunkSize": args.chunk_size, "hybridSearch": not args.no_hybrid, "reranking": not args.no_rerank}
    top_k = max(EVAL_KS)
    results = [run_case(case, corpus, top_k, options) for case in cases]

    overall = aggregate(results, EVAL_KS)
    by_kind = {kind: aggregate([r for r in results if r.kind == kind], EVAL_KS)
               for kind in sorted({result.kind for result in results})}
    if args.json:
        payload = json.dumps({"options": options, "overall": overall, "byKind": by_kind}, ensure_ascii=False, indent=2)
        print(payload)
        output = args.out
    else:
        payload = report(results, overall, by_kind, options, corpus)
        print(payload)
        output = args.out
    if output:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(payload, encoding="utf-8")

    failures = []
    for direction, rules in (("--min", args.min), ("--max", args.max)):
        for rule in rules:
            key, _, value = rule.partition("=")
            threshold = float(value)
            if key not in overall:
                failures.append(f"未知指标 {key}")
            elif direction == "--min" and overall[key] < threshold:
                failures.append(f"{key}={overall[key]:.3f} 低于门槛 {threshold:.3f}")
            elif direction == "--max" and overall[key] > threshold:
                failures.append(f"{key}={overall[key]:.3f} 高于门槛 {threshold:.3f}")
    for failure in failures:
        print(f"评测回归：{failure}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
