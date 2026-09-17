# 检索评测基线

> 由 `python -m eval.run_eval` 生成，请勿手工编辑；指标解读见 `docs/roadmap.md` 的 S1 结论。

- 语料：24 篇文档（`business-service/src/main/resources/seed/documents.json`），512 分段设置下共 26 个片段（LlamaIndex 按 token 计数）
- 用例：192 条；混合检索 True，重排 True
- 覆盖范围：检索层与本地摘录层指标；生成式答案质量需配置模型网关后另行评测

## 总体指标

| 指标 | 数值 |
|---|---|
| answerCoverage | 0.348 |
| docHit@1 | 0.738 |
| docHit@10 | 0.857 |
| docHit@3 | 0.851 |
| docHit@5 | 0.857 |
| docRecall@1 | 0.717 |
| docRecall@10 | 0.848 |
| docRecall@3 | 0.836 |
| docRecall@5 | 0.848 |
| falseRefusal | 0.119 |
| ndcg@1 | 0.732 |
| ndcg@10 | 0.806 |
| ndcg@3 | 0.801 |
| ndcg@5 | 0.806 |
| passageRecall@1 | 0.711 |
| passageRecall@10 | 0.848 |
| passageRecall@3 | 0.836 |
| passageRecall@5 | 0.848 |
| refusal | 0.500 |
| rr@1 | 0.732 |
| rr@10 | 0.787 |
| rr@3 | 0.786 |
| rr@5 | 0.787 |

单查询检索耗时：均值 11 ms / P95 33 ms

## 按用例类型

| 类型 | 用例数 | docHit@5 | passageRecall@5 | ndcg@10 | refusal |
|---|---|---|---|---|---|
| cross_doc | 7 | 1.000 | 0.857 | 0.792 | - |
| distractor | 4 | 1.000 | 0.875 | 0.671 | - |
| exact | 97 | 0.959 | 0.959 | 0.926 | - |
| latin | 2 | 0.500 | 0.500 | 0.500 | - |
| no_answer | 20 | - | - | - | 0.500 |
| numeric | 15 | 0.867 | 0.867 | 0.867 | - |
| paraphrase | 43 | 0.605 | 0.605 | 0.545 | - |
| scope | 4 | - | - | - | 0.500 |

## 需要关注的用例

- 前 5 段未命中任何期望文档（24）：prod-entry-02、prod-upload-limit-02、prod-formats-02、prod-hallucination-01、prod-specificity-01、eng-ports-01、eng-auth-02、eng-table-01、eng-latin-02、pol-leave-02、pol-apply-02、pol-apply-03、pol-expense-02、pol-meal-01、pol-traffic-01、pol-split-01、sec-classify-02、sec-private-01、sup-refund-02、sup-refund-06、sup-talk-03、mkt-a11y-02、mkt-pain-02、team-week4-01
- 应拒答但返回了引用（12）：glob-contact-01、glob-option-01、glob-parking-01、glob-wifi-01、glob-overtime-taxi-01、glob-supplement-insurance-01、glob-offboarding-01、glob-rust-01、glob-ceo-01、glob-headcount-01、glob-scope-kb-01、glob-scope-kb-04
