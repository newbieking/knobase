# 知序 Knobase 迭代路线图

> 周期：4 周短期冲刺 · 方向：RAG 能力深化
> 制定日期：2026-09-17

---

## 现状判断

三层架构（React 19 + Spring Boot 3.4 + FastAPI）可跑通完整链路，文件解析端的安全边界扎实
（`backend/ai-service/app.py:149-212`），Java 集成测试与 Python 单测已就位。

核心瓶颈集中在检索层：

- **纯词面召回**。BM25 + TF-IDF 只在共享词项上打分（`app.py:315-396`），同义改写
  （"休假制度" ↔ "年假怎么请"）完全召回不到，这是企业问答准确率的第一限制因素。
- **每次问答全量重分段**。`retrieve()` 在请求内对全部候选文档重新切分（`app.py:321`），
  延迟随语料线性劣化，也是 500 文档 / `MAX_TEXT_CHARS` 上限存在的原因。
- **topK 设置失效**。`app.py:394` 的 `min(request.topK, 4)` 让设置项中 topK > 4 静默无效。
- **分段口径两套实现**。Java `TextChunks.split` 与 Python `chunk_document` 各自计算，必然漂移。
- **无流式输出**。`/internal/query` 一次性返回，长答案首字延迟等于总耗时。
- **无评测能力**。改动好坏只能靠主观感受判断。

**排期原则**：第 1 周先建评测基线，后续三周每个改动都用同一把尺子量化，避免"感觉变好了"。

---

## S1（第 1 周）评测基线 + 检索结构整固

| 任务 | 落点 |
|---|---|
| Golden set 100~150 条：`{问题, 期望文档/页, 期望要点, 应拒答}`，取材 `SeedData.java` 的 6 库 24+ 文档 | `backend/ai-service/eval/dataset.jsonl` |
| 指标脚本：检索侧 recall@k / MRR / nDCG；端到端侧引用正确率、越界引用率、无据拒答率 | `eval/run_eval.py` + markdown 报告 |
| 修复 topK 上限，让设置真正生效 | `app.py:394` |
| 分段口径统一：索引侧以 Python 为准，Java 只持久化片段数 | `TextChunks.java` / `app.py` |
| CI 三条 job（`tsc -b && vite build`、`mvn verify`、`python -m unittest discover` + 评测回归门；仓库原无 `.github/`） | `.github/workflows/ci.yml` |

**DoD**：基线数字入库（`docs/eval-baseline.md`），CI 绿。

### S1 结论（2026-09-17 实测）

golden set 实际铺到 **192 条**（8 类：exact 97 / paraphrase 43 / no_answer 20 / numeric 15 /
cross_doc 7 / distractor 4 / scope 4 / latin 2），比原定 100~150 多，因为拒答与改写两类
各自要够样本量才能分开看。全量基线：

| 指标 | 数值 | 指标 | 数值 |
|---|---|---|---|
| docHit@1 | 0.738 | docHit@5 | 0.857 |
| ndcg@10 | 0.806 | passageRecall@5 | 0.848 |
| refusal | 0.500 | falseRefusal | 0.119 |
| answerCoverage | 0.348 | 检索耗时 | 均值 12 ms / P95 37 ms |

topK 与分段口径均已收敛到单一实现：`app.py:394` 的隐藏上限去除后设置真正生效；Java 侧
`TextChunks.java` 删除，片段数改由 `POST /internal/chunk` 用同一个分词器算，seed 文档正文
移到 `business-service/src/main/resources/seed/documents.json`，评测器与业务共用这一份语料
（`--check-corpus` 守住片段数不漂移）。

四条改判 S2 的发现：

1. **改写类是唯一的大坑**：exact 0.959 vs paraphrase **0.605** docHit@5，latin 0.500。
   差距就是 S2 语义召回的预算依据，也给出可量化目标——把 paraphrase 拉到 0.75 以上。
2. **片段级指标已经退化**：LlamaIndex `SentenceSplitter` 按 **token** 而非字符计数，
   "512" 下 24 篇文档只切出 26 个片段，于是 docHit@5 == docHit@10、passageRecall@5 == @10。
   S2 建持久索引前必须先把 `chunkSize` 口径改成字符（或明显更小的默认值），否则任何
   片段级调参都没有分辨率。
3. **拒答基本没在工作**：no_answer / scope 两类 refusal 都只有 **0.500**，24 条应拒答问题里
   12 条仍返回引用。当前判据是"最高分为 0 才拒"，词面检索对无关问题几乎总能给出非零分。
   需要相对阈值（与 top 结果脱钩）或语义相似度下限，这是 S2 融合 dense 分数时一并解决的。
4. **确定性是回归门的前提**：同一份代码两次跑出 docHit@5 0.768 vs 0.857——浮点求和顺序
   跟着 set 迭代顺序（`PYTHONHASHSEED`）走。修成排序迭代后跨 seed 稳定，CI 门才有意义：
   `docHit@5 ≥ 0.80`、`ndcg@10 ≥ 0.75`、`refusal ≥ 0.45`、`falseRefusal ≤ 0.15`。

遗留：seed 文档 `doc-tech-api` 写着"Top K 范围 1 至 50"，与 Python/前端的 1~20 不一致，
属内容修正，未在本迭代内改动。

---

## S2（第 2 周）语义召回 + 索引持久化

- `chunkSize` 改成按字符计（或把默认值降到能产生分辨率的量级）。当前 LlamaIndex 按 token 计数，
  24 篇文档在 512 下只有 26 个片段，片段级指标 @5 与 @10 完全相等，调参没有分辨率。
- 拒答判据从"最高分为 0"换成相对阈值（与 top1 分数或分数分布脱钩）。S1 实测 no_answer / scope
  两类 refusal 均为 0.500，24 条应拒答问题有 12 条返回了引用。
- Embedding 接入 OpenAI 兼容 `/v1/embeddings`，新增 `EMBEDDING_MODEL_ID` 配置项。
  **无 key 时保持当前词面链路，零回归。**
- 本地持久索引：SQLite + numpy 暴力检索即可，不引入独立向量服务，避免运维面扩大。
  缓存 `(doc_id, chunk_hash, vector)`，在 reindex / delete 时失效。
  顺带取消每问全量重分段，检索延迟不再随语料线性增长。
- 融合从双路扩到 BM25 + TF-IDF + dense 三路，RRF 泛化（`app.py:352-359`）。

**DoD**：同义改写类问题 recall@5 相对基线 +10pp 以上；`/health` 与前端健康 badge 反映索引就绪状态。

---

## S3（第 3 周）流式输出 + 多轮检索改写

- Python 新增 `POST /internal/query/stream`（SSE 事件序：`meta` → `citations` → `delta` → `done`），
  保留旧端点给本地摘录模式。
- Java `AiClient` + `ApiController` 走 `SseEmitter`；会话落库从 `WorkspaceService.java:164-183`
  移到流结束，断连或取消不得留下脏会话。
- 前端 `Chat.tsx:23-33` 改用 `fetch` + `ReadableStream` 增量渲染，引用条先于正文到达。
- 多轮改写：用 LLM 把追问（"那第二条呢"）改写为自洽检索式，**检索用改写串、生成用原问**，
  复用 `app.py:462-469` 已传递的 history。

**DoD**：首 token < 1.5s；多轮追问场景在评测集中通过。

---

## S4（第 4 周）真实重排 + 生成质量与用量闭环

- Cross-encoder rerank（本地 `bge-reranker` 或网关 rerank API），把 `settings.reranking`
  从 `0.70/0.22/0.08` 加权混合（`app.py:369-372`）换成真模型重排，旧逻辑降级为 fallback。
- 引用忠实度校验：扩展 `app.py:493-495` 的编号检查为 claim ↔ 引用一致性，
  不通过则重试一次，再降级为原文摘录模式。
- Token 用量透传：`/internal/query` 返回 usage，替换 `WorkspaceService.java:178-181`
  的字符估算，让 Dashboard / Analytics 的 token 趋势从演示数据变成真数据。
- 反馈持久化：赞/踩当前仅存 localStorage（`Chat.tsx:17`），落库后作为评测集增量来源。

**DoD**：引用正确率 ≥ 基线 +10pp；幻觉诱导问句正确拒答。

---

## 本月明确不做

鉴权与多租户、H2 → MySQL/PG 迁移、Docker Compose 部署、前后端分离部署、扫描件 OCR、
新文件格式支持、答案生成缓存。

这些属于生产化基座，建议排入下一季度——若与向量索引、流式改造同期推进，会在数据模型和
请求链路上互相踩踏。

---

## 主要风险

1. **外部网关依赖**（embedding / rerank / LLM）。所有新能力做成"未配置即静默降级"；
   CI 用 mock 跑本地链路，避免评测结果被配额与网络抖动污染。
2. **SSE 与现有事务边界冲突**。Java 端需要把"流转发"与"写会话"解耦，
   客户端中途断开需新增单测覆盖。
3. **评测集自种子数据生成，可能过拟合**。预留 20% 人工改写样本
   （换措辞、跨文档综合、加入干扰段落）作为留出集，不参与调参。
