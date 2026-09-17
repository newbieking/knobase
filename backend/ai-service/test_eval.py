"""Guards for the evaluation fixtures: a rotten golden set must fail the build, not silently skew metrics."""
import json
import tempfile
import unittest
from pathlib import Path

from eval.run_eval import (
    DEFAULT_DATASET, aggregate, check_corpus, load_corpus, load_dataset, normalize, run_case,
)

OPTIONS = {"chunkSize": 512, "hybridSearch": True, "reranking": True}
KINDS = {"exact", "paraphrase", "numeric", "cross_doc", "distractor", "latin", "no_answer", "scope"}


class CorpusTests(unittest.TestCase):
    def setUp(self):
        self.corpus = load_corpus()
        self.cases = load_dataset(DEFAULT_DATASET)
        self.by_id = {document.id: document for document in self.corpus}

    def test_corpus_is_the_shared_seed(self):
        self.assertEqual(len(self.corpus), 24)
        self.assertEqual({document.kbId for document in self.corpus},
                         {"kb-product", "kb-engineering", "kb-policy", "kb-support", "kb-marketing", "kb-team"})

    def test_stored_chunk_counts_match_the_retrieval_chunker(self):
        self.assertEqual(check_corpus(), [])

    def test_dataset_ids_and_questions_are_unique(self):
        self.assertEqual(len({case["id"] for case in self.cases}), len(self.cases))
        self.assertEqual(len({case["question"] for case in self.cases}), len(self.cases))
        for case in self.cases:
            self.assertTrue(case["question"].strip())
            self.assertIn(case["kind"], KINDS)

    def test_every_gold_reference_resolves(self):
        for case in self.cases:
            with self.subTest(case=case["id"]):
                gold = case["gold"]
                self.assertEqual(bool(gold["docs"]), not case.get("shouldRefuse", False))
                for doc in gold["docs"]:
                    self.assertIn(doc, self.by_id)
                for passage in gold["passages"]:
                    holder = {doc.id for doc in self.corpus if normalize(passage) in normalize(doc.content)}
                    self.assertTrue(holder, f"片段在语料中不存在：{passage}")
                    if gold["docs"]:
                        self.assertTrue(holder & set(gold["docs"]), f"片段不属于任何期望文档：{passage}")

    def test_scope_cases_keep_gold_inside_the_knowledge_base(self):
        for case in self.cases:
            if case.get("kbId"):
                with self.subTest(case=case["id"]):
                    self.assertTrue(all(self.by_id[doc].kbId == case["kbId"] for doc in case["gold"]["docs"]))

    def test_dataset_covers_paraphrase_and_refusal_cases(self):
        self.assertTrue(KINDS <= {case["kind"] for case in self.cases})
        self.assertGreater(sum(1 for case in self.cases if case["kind"] == "paraphrase"), len(self.cases) // 10)
        self.assertGreater(sum(1 for case in self.cases if case.get("shouldRefuse")), 15)

    def test_unknown_field_in_case_is_rejected(self):
        path = Path(tempfile.mkstemp(suffix=".jsonl")[1])
        path.write_text(json.dumps({"id": "x", "question": "问题", "kind": "exact",
                                    "gold": {"docs": [], "passages": []}, "expected": "typo"}), encoding="utf-8")
        with self.assertRaises(ValueError):
            load_dataset(path)


class MetricTests(unittest.TestCase):
    def setUp(self):
        self.corpus = load_corpus()
        self.cases = {case["id"]: case for case in load_dataset(DEFAULT_DATASET)}

    def test_exact_question_retrieves_its_document_first(self):
        case = {"id": "t1", "kind": "exact", "question": "累计工作已满 1 年不满 10 年，年休假几天？",
                "kbId": "kb-policy", "gold": {"docs": ["doc-policy-leave"], "passages": ["年休假 5 天"]}}
        result = run_case(case, self.corpus, 10, OPTIONS)
        self.assertEqual(result.ranked_docs[0], "doc-policy-leave")
        self.assertEqual(result.passage_found, {normalize("年休假 5 天"): 1})
        scores = aggregate([result], (1, 3, 5))
        self.assertEqual((scores["docHit@1"], scores["ndcg@1"], scores["rr@1"]), (1.0, 1.0, 1.0))
        self.assertNotIn("refusal", scores, "只有应拒答用例才参与拒答指标")
        self.assertEqual(scores["falseRefusal"], 0.0)

    def test_ranking_is_stable_across_repeats(self):
        case = {"id": "t2", "kind": "exact", "question": "年假有多少天？", "kbId": "kb-policy",
                "gold": {"docs": ["doc-policy-leave"], "passages": ["年休假 5 天"]}}
        runs = [run_case(case, self.corpus, 10, OPTIONS) for _ in range(3)]
        self.assertEqual({tuple(run.ranked_docs) for run in runs}, {tuple(runs[0].ranked_docs)})
        self.assertEqual({json.dumps(run.passage_found, sort_keys=True) for run in runs}.__len__(), 1)

    def test_off_topic_question_returns_no_citation_slots(self):
        case = {"id": "t3", "kind": "no_answer", "question": "公司班车几点发车？", "kbId": None,
                "gold": {"docs": [], "passages": []}, "shouldRefuse": True}
        result = run_case(case, self.corpus, 10, OPTIONS)
        scores = aggregate([result], (1, 3, 5))
        self.assertNotIn("docHit@1", scores)
        self.assertIn(scores["refusal"], (0.0, 1.0))

    def test_hybrid_and_rerank_switches_change_ranking(self):
        case = self.cases["prod-reindex-02"]
        variants = {
            json.dumps(run_case(case, self.corpus, 10, OPTIONS).ranked_docs),
            json.dumps(run_case(case, self.corpus, 10, {**OPTIONS, "hybridSearch": False}).ranked_docs),
            json.dumps(run_case(case, self.corpus, 10, {**OPTIONS, "reranking": False}).ranked_docs),
        }
        self.assertGreater(len(variants), 1, "检索设置开关应当影响排序，否则说明设置未生效")


if __name__ == "__main__":
    unittest.main(verbosity=2)
