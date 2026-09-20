# 检索评测基线

> 由 `python -m eval.run_eval --out docs/eval-baseline.md` 生成，请勿手工编辑；指标解读见 `docs/roadmap.md` 的 S1/S2 结论。

- 语料：24 篇文档（`business-service/src/main/resources/seed/documents.json`），512 字符分段预算下共 79 个片段
- 用例：192 条；混合检索 True，重排 True
- 覆盖范围：检索层与本地摘录层指标；生成式答案质量需配置模型网关后另行评测

## 总体指标

| 指标 | 数值 |
|---|---|
| answerCoverage | 0.341 |
| docHit@1 | 0.744 |
| docHit@10 | 0.881 |
| docHit@3 | 0.845 |
| docHit@5 | 0.881 |
| docRecall@1 | 0.723 |
| docRecall@10 | 0.875 |
| docRecall@3 | 0.836 |
| docRecall@5 | 0.872 |
| falseRefusal | 0.119 |
| ndcg@1 | 0.708 |
| ndcg@10 | 0.797 |
| ndcg@3 | 0.781 |
| ndcg@5 | 0.794 |
| passageRecall@1 | 0.690 |
| passageRecall@10 | 0.875 |
| passageRecall@3 | 0.836 |
| passageRecall@5 | 0.866 |
| refusal | 0.500 |
| rr@1 | 0.708 |
| rr@10 | 0.775 |
| rr@3 | 0.767 |
| rr@5 | 0.774 |

单查询检索耗时：均值 6 ms / P95 32 ms

## 按用例类型

| 类型 | 用例数 | docHit@5 | passageRecall@5 | ndcg@10 | refusal |
|---|---|---|---|---|---|
| cross_doc | 7 | 1.000 | 0.929 | 0.805 | - |
| distractor | 4 | 1.000 | 0.750 | 0.592 | - |
| exact | 97 | 0.969 | 0.959 | 0.908 | - |
| latin | 2 | 0.500 | 0.500 | 0.250 | - |
| no_answer | 20 | - | - | - | 0.500 |
| numeric | 15 | 0.867 | 0.867 | 0.867 | - |
| paraphrase | 43 | 0.674 | 0.674 | 0.566 | - |
| scope | 4 | - | - | - | 0.500 |

## 需要关注的用例

- 前 5 段未命中任何期望文档（20）：prod-entry-02、prod-formats-02、eng-ports-01、eng-auth-02、eng-table-01、eng-latin-02、pol-leave-02、pol-apply-02、pol-apply-03、pol-meal-01、pol-traffic-01、pol-split-01、sec-classify-02、sec-private-01、sup-refund-02、sup-refund-06、sup-talk-03、mkt-a11y-02、mkt-pain-02、team-week4-01
- 应拒答但返回了引用（12）：glob-contact-01、glob-option-01、glob-parking-01、glob-wifi-01、glob-overtime-taxi-01、glob-supplement-insurance-01、glob-offboarding-01、glob-rust-01、glob-ceo-01、glob-headcount-01、glob-scope-kb-01、glob-scope-kb-04
