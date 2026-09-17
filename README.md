# 知序 Knobase · 企业知识工作台

知序是一个面向企业团队的本地知识管理与 RAG（检索增强生成）问答平台。采用 **前端 + Java 业务服务 + Python AI 服务** 三层架构，支持文档上传解析、知识库管理、混合检索与 AI 问答，并提供完整的引用追溯能力。

> 当前为单用户本地演示版本，不包含生产级身份认证与多租户隔离。

---

## 目录

- [系统架构](#系统架构)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [功能模块](#功能模块)
- [快速启动](#快速启动)
- [测试与评测](#测试与评测)
- [API 概览](#api-概览)
- [配置说明](#配置说明)
- [数据模型](#数据模型)

---

## 系统架构

```
┌──────────────────────────────────────────────────────────┐
│                    浏览器 (Port 5173)                     │
│              React 19 + TypeScript + Vite                │
│                     /api 代理 ↓                          │
├──────────────────────────────────────────────────────────┤
│              Java 业务服务 (Port 9090)                    │
│         Spring Boot 3.4 · Spring JDBC · H2              │
│                                                          │
│  · 知识库 / 文档 / 会话 / 设置 CRUD                       │
│  · 文件校验与 Base64 转发                                 │
│  · 问答编排：组装上下文 → 调用 AI → 保存结果               │
│                     ↓ HTTP (127.0.0.1)                   │
├──────────────────────────────────────────────────────────┤
│              Python AI 服务 (Port 9001)                   │
│          FastAPI · LangChain · LlamaIndex                │
│                                                          │
│  · 文件解析 (PDF / DOCX / TXT / MD / CSV)                │
│  · BM25 + TF-IDF 混合检索 + 倒数秩融合                    │
│  · LLM 生成式问答 (LangChain ChatOpenAI)                  │
│  · 无 API Key 时降级为原文摘录检索                        │
└──────────────────────────────────────────────────────────┘
```

**请求链路**：浏览器 → Vite 代理 `/api` → Java 业务服务 → Python AI 服务

- **文件上传**：Java 校验扩展名与大小 → Base64 编码发送到 Python `/internal/parse` 获取提取文本 → 调用 `/internal/chunk` 用同一个分片器取片段数 → 入库
- **知识问答**：Java 根据数据库中的可用文档构建上下文 → 发送到 Python `/internal/query`（携带分段与检索设置）→ 获取答案与引用 → 保存会话

---

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 前端 | React, TypeScript, Vite | React 19, TS 5.7, Vite 6 |
| 前端 UI | Lucide React, React Markdown | - |
| 业务服务 | Java, Spring Boot, Spring JDBC | Java 17, Spring Boot 3.4.9 |
| 业务数据库 | H2 文件数据库 | 嵌入式 |
| AI 服务 | Python, FastAPI, Uvicorn | FastAPI ≥0.118 |
| LLM 编排 | LangChain (ChatOpenAI) | langchain-openai ≥0.3 |
| 文档处理 | LlamaIndex (SentenceSplitter) | llama-index ≥0.12 |
| 文件解析 | pypdf, python-docx | - |
| 配置管理 | python-dotenv | ≥1.0 |

---

## 项目结构

```
my-rag/
├── index.html                          # 前端入口 HTML
├── package.json                        # 前端依赖与脚本
├── vite.config.ts                      # Vite 配置（代理 /api → Java 9090）
├── tsconfig.json                       # TypeScript 配置
├── src/                                # 前端源码
│   ├── main.tsx                        # React 挂载入口
│   ├── App.tsx                         # 根组件：导航、路由、全局状态
│   ├── types.ts                        # TypeScript 类型定义
│   ├── api.ts                          # API 请求封装与工具函数
│   ├── ui.tsx                          # 通用 UI 组件（Badge, Brand, Modal 等）
│   ├── styles.css                      # 全局样式与设计系统
│   ├── Dashboard.tsx                   # 概览页：统计卡片、趋势图、快捷入口
│   ├── Library.tsx                     # 知识库管理 + 文档列表
│   ├── Chat.tsx                        # AI 问答：会话管理、消息流、引用展示
│   └── Workspace.tsx                   # AI 助手、数据分析、工作空间设置
│
├── backend/
│   ├── business-service/               # Java 业务服务
│   │   ├── pom.xml                     # Maven 依赖（Spring Boot 3.4.9）
│   │   └── src/main/
│   │       ├── java/com/knobase/business/
│   │       │   ├── BusinessApplication.java    # Spring Boot 启动类
│   │       │   ├── ApiController.java          # REST 控制器（/api/*）
│   │       │   ├── ApiModels.java              # 请求/响应 Record 定义
│   │       │   ├── ApiErrorHandler.java        # 全局异常处理
│   │       │   ├── ApiException.java           # 业务异常
│   │       │   ├── WorkspaceService.java       # 核心业务逻辑
│   │       │   ├── WorkspaceRepository.java    # 数据访问层（Spring JDBC）
│   │       │   ├── AiClient.java               # Python AI 服务 HTTP 客户端
│   │       │   └── SeedData.java               # 演示数据初始化
│   │       ├── resources/
│   │       │   ├── application.properties      # 服务配置
│   │       │   ├── schema.sql                  # 数据库建表语句
│   │       │   └── seed/documents.json         # 演示文档正文（业务与评测共用的唯一语料）
│   │       └── src/test/java/com/knobase/business/
│   │           └── BusinessApiIntegrationTest.java   # 集成测试（MockMvc + 桩 AI 服务）
│   │
│   └── ai-service/                     # Python AI 服务
│       ├── app.py                      # FastAPI 主应用（解析、分段、检索、问答）
│       ├── requirements.txt            # Python 依赖
│       ├── .env.example                # LLM 网关配置模板（复制为 .env）
│       ├── eval/
│       │   ├── dataset.jsonl           # Golden set：192 条问答用例
│       │   └── run_eval.py             # 指标脚本 + markdown 报告 + 回归门
│       ├── test_app.py                 # 服务端单元测试
│       └── test_eval.py                # 评测集与指标守护测试
│
├── docs/
│   ├── roadmap.md                      # 迭代路线图与 S1 结论
│   └── eval-baseline.md                # 检索评测基线（由 eval.run_eval 生成）
│
└── dist/                               # 前端构建产物
```

---

## 功能模块

### 1. 概览仪表盘 (Dashboard)

- 知识库总数、文档总数、知识片段数、问答次数统计卡片
- 近 30 天查询量与 Token 用量趋势图
- 最近活动流（上传、问答、创建等操作记录）
- 系统健康状态（业务服务 + AI 服务实时监测）
- 快捷入口：创建知识库、上传文档、开始问答

### 2. 知识库管理 (Library)

- **创建/编辑知识库**：名称、描述、颜色标识、标签、可见范围（团队/私有）
- **文档浏览**：按知识库筛选，展示文档列表与知识片段数
- **文档导入**：
  - 文件上传：支持 PDF、DOCX、MD、TXT、CSV，单文件 ≤10MB，批量 ≤5 个
  - 文本粘贴：直接输入 Markdown 或纯文本内容
- **文档预览**：查看已解析的文本内容，支持一键下载提取结果
- **重新索引**：按当前分段设置重建知识片段
- **删除操作**：支持单文档或批量删除，级联清理关联数据

### 3. 文档中心 (Documents)

- 全局文档列表视图，跨知识库展示所有文档
- 文件类型图标、大小、片段数、更新时间
- 上传、预览、删除、重新索引操作

### 4. AI 问答 (Chat)

- **知识范围选择**：可选全部知识库或限定单个知识库
- **对话管理**：自动创建会话，携带最近 50 条历史消息作为上下文（模型端再看最近 6 条）
- **引用追溯**：每条回答标注来源编号 [1] [2]…，点击可查看文档名、页码、原文摘录与相关度评分
- **双模式运行**：
  - **Connected 模式**（配置了 API Key）：通过 LangChain ChatOpenAI 调用 LLM 生成式回答
  - **Local 模式**（无 API Key）：降级为 BM25 + TF-IDF 原文检索摘录
- **会话历史**：侧边栏展示历史对话，支持切换与删除
- **快捷操作**：从文档预览直接发起提问，自动填充问题与知识库范围

### 5. AI 助手 (Agents) — BETA

- 预置助手角色模板，快速启动特定场景的问答
- 一键跳转到问答页并带入预设问题与知识库

### 6. 数据与评估 (Analytics)

- 查询统计：总次数、周环比变化、平均延迟、成功率
- 趋势图表：近 30 天查询量与 Token 用量
- 活动日志：所有操作的审计记录

### 7. 工作空间设置 (Settings)

- 工作空间名称
- 检索参数配置：
  - 分段大小 (chunkSize)：128 ~ 8192（LlamaIndex 按 token 计数，实际片段比数字短，S2 统一口径）
  - 返回条数 (topK)：1 ~ 20
  - 生成温度 (temperature)：0.0 ~ 2.0
  - 混合检索开关 (hybridSearch)
  - 重排序开关 (reranking)

---

## 快速启动

### 环境要求

- **Node.js** ≥ 18
- **Java** 17+
- **Maven** 3.8+
- **Python** 3.11+

### 1. 启动 Python AI 服务

```bash
cd backend/ai-service

# 创建虚拟环境（首次）
python -m venv .venv
.venv/Scripts/activate        # Windows
# source .venv/bin/activate   # macOS/Linux

# 安装依赖
pip install -r requirements.txt

# 配置 LLM（可选，不配置则使用本地检索模式）
cp .env.example .env
# 编辑 .env 填入 API_KEY、BASE_URL、MODEL_ID

# 启动服务 → http://127.0.0.1:9001
python app.py
```

### 2. 启动 Java 业务服务

```bash
cd backend/business-service

# 启动服务 → http://127.0.0.1:9090
mvn spring-boot:run
```

首次启动会自动创建 H2 数据库文件并注入演示数据（6 个知识库、24+ 份文档）。

### 3. 启动前端开发服务器

```bash
# 安装前端依赖（首次）
npm install

# 启动开发服务器 → http://localhost:5173
npm run dev
```

Vite 会自动将 `/api` 请求代理到 Java 业务服务 (9090)。

---

## 测试与评测

```bash
# 前端类型检查与构建
npm run build

# Java 业务服务集成测试（MockMvc + 桩 AI 服务，不需要 Python 在跑）
cd backend/business-service && mvn verify

# Python AI 服务单元测试（app + 评测集守护，共 41 个）
cd backend/ai-service && python -m unittest discover
```

Windows 控制台输出中文报告前先设置 `PYTHONIOENCODING=utf-8`。

### 检索评测

评测直接跑在业务同一份代码与同一份语料上（`seed/documents.json`），无需启动任何服务：

```bash
cd backend/ai-service

python -m eval.run_eval --check-corpus                    # 片段数与分片器是否一致
python -m eval.run_eval                                   # markdown 报告
python -m eval.run_eval --out ../../docs/eval-baseline.md # 重新生成基线文件
python -m eval.run_eval --only paraphrase                 # 按用例类型或 id 过滤
python -m eval.run_eval --no-hybrid --json                # 对比检索开关
python -m eval.run_eval --min docHit@5=0.80 --max falseRefusal=0.15   # 回归门
```

指标口径：`docRecall` / `docHit` / `passageRecall` / `rr`(MRR) / `ndcg` 取 @1/@3/@5/@10；
`refusal`（应拒答且确实无引用）、`falseRefusal`（可答却被拒）、`answerCoverage`
（本地摘录答案覆盖期望要点的比例）。当前基线与解读见 `docs/eval-baseline.md` 和
`docs/roadmap.md` 的 S1 结论。

CI（`.github/workflows/ci.yml`）跑上面三条 job，并在 AI 服务 job 里以
`docHit@5 ≥ 0.80`、`ndcg@10 ≥ 0.75`、`refusal ≥ 0.45`、`falseRefusal ≤ 0.15` 作为回归门。

---

## API 概览

### 前端 → Java 业务服务 (`/api`)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/bootstrap` | 获取全量初始化数据（知识库、文档、会话、设置、统计） |
| GET | `/health` | 系统健康检查（业务服务 + AI 服务状态） |
| POST | `/knowledge-bases` | 创建知识库 |
| PATCH | `/knowledge-bases/{id}` | 更新知识库 |
| DELETE | `/knowledge-bases/{id}` | 删除知识库（级联删除文档） |
| POST | `/documents` | 上传文件（multipart/form-data） |
| POST | `/documents/text` | 导入文本内容 |
| DELETE | `/documents/{id}` | 删除文档 |
| POST | `/documents/{id}/reindex` | 重新索引文档 |
| GET | `/documents/{id}/download` | 下载提取的文本 |
| POST | `/chat` | 发起知识问答 |
| GET | `/sessions` | 获取会话列表 |
| DELETE | `/sessions/{id}` | 删除会话 |
| PUT | `/settings` | 保存工作空间设置 |

### Java → Python AI 服务 (`/internal`)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | AI 服务健康检查，返回状态与模式 |
| POST | `/internal/parse` | 解析文件（Base64），返回提取文本 |
| POST | `/internal/chunk` | 按检索侧同一分片器统计片段数，返回 `chunkCount` |
| POST | `/internal/query` | 执行检索与问答，返回答案、引用、耗时 |

---

## 配置说明

### Python AI 服务 (.env)

```env
# LLM 模型网关配置（整个文件可省略，留空即本地摘录检索模式）
LLM_API_KEY=              # 留空则使用本地检索模式
LLM_BASE_URL=https://api.openai.com/v1   # OpenAI 兼容 API 地址
LLM_MODEL_ID=gpt-4o-mini                 # 模型标识
LLM_MODEL_NAME=GPT-4o mini              # 模型显示名称
LLM_TIMEOUT_SECONDS=25                   # 网关超时，取值被夹到 1~60 秒
```

支持任何 OpenAI 兼容的 API 端点（OpenAI、Azure OpenAI、本地 Ollama、vLLM 等）。

### Java 业务服务 (application.properties)

```properties
server.port=9090                                    # 业务服务端口
knobase.ai.base-url=http://127.0.0.1:9001           # AI 服务地址
knobase.ai.timeout-seconds=90                       # AI 请求超时
spring.datasource.url=jdbc:h2:file:./data/knobase   # H2 数据库路径
knobase.seed.enabled=true                           # 是否注入演示数据
```

### 前端 (vite.config.ts)

```typescript
server: {
  port: 5173,
  proxy: { '/api': { target: 'http://127.0.0.1:9090' } }
}
```

---

## 数据模型

### 数据库表结构 (H2)

| 表名 | 说明 |
|------|------|
| `knowledge_bases` | 知识库：名称、描述、颜色、标签、可见范围 |
| `documents` | 文档：所属知识库、类型、大小、片段数、提取文本 |
| `chat_sessions` | 对话会话：标题、消息历史 (JSON) |
| `workspace_settings` | 工作空间设置（单行 JSON） |
| `activities` | 活动日志：类型、标题、描述、时间 |
| `daily_stats` | 每日统计：查询数、成功数、Token 数、延迟 |
| `app_meta` | 元数据（种子数据版本标记） |

### 检索流程

```
用户提问
  ↓
中文分词（单字 + Bigram + 拉丁词）
  ↓
┌─────────────────┬──────────────────┐
│  BM25 关键词检索  │  TF-IDF 余弦相似度  │
└────────┬────────┴────────┬─────────┘
         ↓   倒数秩融合 (RRF)   ↓
         └────────┬───────────┘
                  ↓
          重排序（覆盖率 + 短语匹配）
                  ↓
          去重 + 上下文预算裁剪
                  ↓
          组装 Prompt → LLM 生成回答
                  ↓
          解析引用编号 → 返回答案 + 引用
```

- **混合检索**：BM25 捕捉关键词精确匹配，TF-IDF 余弦相似度捕捉语义相关性，通过 Reciprocal Rank Fusion 融合排序
- **中文分词**：针对中文文本特性，采用单字 + 双字组合 (Bigram) 的分词策略，配合自定义停用词表
- **重排序**：在融合排序基础上，加入查询术语覆盖率与短语精确匹配评分
- **上下文预算**：控制送入 LLM 的总文本量（默认 5000 字符），避免超出 Token 限制
