<div align="center">

# 🎓 智途 AI (Zhitu-AI)
### 面向成人职业教育的工业级多智能体协同导学系统
**Multi-Agent Collaborative AI Tutor System for Professional Education**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5+-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.2-blueviolet.svg)](https://spring.io/projects/spring-ai)
[![Vue](https://img.shields.io/badge/Vue.js-3.x-4FC08D.svg)](https://vuejs.org/)
[![Elasticsearch](https://img.shields.io/badge/Elasticsearch-8.15-005571.svg)](https://www.elastic.co/)
[![Tests](https://img.shields.io/badge/Tests-63%2F63%20Passing-success.svg)](docs/test-report.md)

[测试报告](./docs/test-report.md) · [快速上手与配置指南](#-5-分钟快速上手与配置指南-quick-start) · [系统架构](#-系统架构全景) · [核心特性](#-核心工程特性)

</div>

---

## 🌟 项目简介 (Overview)

**智途 AI (Zhitu-AI)** 是一套面向计算机与技术转码学员的**工业级多智能体 AI 助教与教学导学系统**。
与市面上常见的大模型“单 Agent 提示词套壳”不同，智途 AI 采用**编排中枢 + 11 个专业业务智能体**的协同拓扑，真正实现了**“测评摸底 $\rightarrow$ 定制规划 $\rightarrow$ 启发导学 $\rightarrow$ 密封组卷 $\rightarrow$ 智能批改 $\rightarrow$ 全局复盘”**的全业务闭环。

系统创新性地实现了**“语料源驱动的零代码学科扩展机制”**——添加一门新学科无需修改任何一行 Java 或前端代码，仅需放置纯 Markdown 语料，系统即可自动热入库并激活推荐、RAG 教学、双轨出题与预下单全功能。

---

## 🚀 核心工程特性 (Key Features)

### 1. 🤖 编排中枢与 11 智能体拓扑
- **三级意图路由网络**：
  - **L0 规则快路径**：强特征零歧义请求（如“出3道多线程选择题”）免 LLM 极速短路，时延 $\le 25\text{ms}$；
  - **L2 轻量模型决策**：综合研判开放意图，支持跨轮槽位继承与受控词典强约束（意图评测 200 题脚本与数据集见 `docs/test-report.md`）；
  - **歧义否决机制**：设置解题探讨否决词，防止答题探讨被误判为重新出题。
- **单轮单次 LLM 联合决策**：单次调用联合输出意图、七维槽位与编排计划，节省 60% 冗余网络耗时与 Token 消耗；
- **状态机与 DAG 并行调度**：严控教学状态流转，引入 Critic 质检机制防止大模型幻觉。

### 2. ⚡ 语料源驱动与零代码扩展 (Zero-Code Extensibility)
- **纯 Markdown 热入库**：课程体系与私有题库完全由 Markdown 语料定义；
- **动态别名受控词典**：Frontmatter 声明的别名自动同步入库，`SkillDictionary` 结合并发内存哈希实现**无感热加载**，同义词智能归一；
- **全链路自适应**：新语料放入后点击一键同步，新学科的“推荐、RAG授课、出题组卷、电商下单、专属复盘”5 大环节即刻跑通，0 代码侵入。

### 3. 💡 苏格拉底导学与教学防作弊
- **启发式交互答疑**：肯定学员工程直觉，采用反问启发学员自行发现单线程与并发、版本迭代差异（如经典的“七段八桶”口诀）；
- **答案密封下发机制**：下发答题卡时在报文级**绝对剥离 `answer` 与 `explanation`**，答案键仅存服务端随卷关联，作答后由批改智能体判分揭晓，杜绝前端抓包作弊；
- **多轮全局动态复盘**：会话收尾时提炼认知主线思维导图与三大盲区钉牢，输出针对性学习路线图，首字延迟（TTFT）压至亚秒级（$903\text{ms}$）。

### 4. 🔍 工业级混合 RAG 检索管线
- **四阶检索流水线**：`全文 BM25 + 1024 维 Dense 密集向量 + RRF 倒数排名融合 + Cross-Encoder (GTE-Rerank) 语义重排`，Recall@5 达到 **$96\%$**；
- **多格式文档解析**：支持 Web 端拖拽解析 Word (`.docx`)、PDF (`.pdf`) 与 Markdown 结构化章节切片并注入面包屑；
- **知识冲突与相似预警**：前置冲突检测算法，上传相左条款时弹出 `conflictAlert` 红色预警；
- **Redis 语义缓存**：高频相似问题命中缓存秒级返回（$\le 80\text{ms}$）。

### 5. 🛡️ 工业级安全防御与全链路容灾
- **对抗样本 100% 拦截**：40 组安全攻防样本（Prompt 越狱、DAN 模式、系统提示词嗅探、越权泄题、代写代考）经 LLM-as-a-Judge 审计 **100% 拦截防御**；
- **Resilience4j 舱壁与熔断**：模型服务超时或异常时自动下发降级道歉指引，前端零白屏；
- **连续降级转人工（`HUMAN_HANDOFF`）**：连续 3 次异常自动触发工单流转，保障兜底体验；
- **全链路 APM 可观测**：每次对话记录完整 Trace/Span 耗时与 Token 计费，闭环驱动模型评估。

---

## 🏗️ 系统架构全景 (Architecture)

```mermaid
graph TD
    User([前端学员 / 管理后台]) <-->|SSE 流式 / RESTful| Gateway[前端交互层 Vue 3 / Vite]
    
    subgraph 引擎编排与中枢 [tutor-engine]
        Gateway <--> EngineController[控制器 & 安全拦截网关]
        EngineController --> Router[三级意图路由 Router]
        Router -->|L0 快路径 <=25ms| FastPath[规则引擎 & 受控词典]
        Router -->|L2 互证决策| L2LLM[轻量意图判别]
        
        Router --> Orchestrator[智能体调度中枢 DAG]
        Orchestrator --> Clarify[澄清 ClarifyAgent]
        Orchestrator --> Diagnose[诊断 DiagnoseAgent]
        Orchestrator --> Plan[规划 PlanAgent]
        Orchestrator --> Teach[讲解 TeachAgent]
        Orchestrator --> Socratic[苏格拉底 SocraticAgent]
        Orchestrator --> Exercise[出题 ExerciseAgent]
        Orchestrator --> Grade[批改 GradeAgent]
        Orchestrator --> Recap[复盘 RecapAgent]
        Orchestrator --> Recommend[推荐 RecommendAgent]
        Orchestrator --> Order[交易 OrderAgent]
        Orchestrator --> Chitchat[闲聊 ChitchatAgent]
    end

    subgraph 知识与模型底座
        Teach & Exercise <--> RAG[混合检索 RAG]
        RAG <--> ES[(Elasticsearch 8.15 / IK)]
        RAG <--> Vector[(DashScope 向量化 / 重排)]
        RAG <--> SemanticCache[(Redis 语义缓存)]
        
        Recommend & Order <--> MCP[tutor-mcp-server]
        MCP <--> MySQL[(MySQL 业务库 tutor_biz)]
        EngineController <--> LocalMySQL[(MySQL 引擎库 tutor_engine)]
    end

    subgraph 语料源驱动热同步
        Corpus[纯 Markdown 语料库 /corpus] -->|一键热同步| SyncService[CorpusSyncService]
        SyncService -->|构建索引| ES
        SyncService -->|Upsert| MCP
        SyncService -->|动态词典热更新| FastPath
    end
```

---

## 📁 目录结构 (Monorepo Layout)

```
zhitu-ai/
├── backend/                  # 助教核心编排引擎 (Spring Boot 3 + Spring AI)
│   ├── src/main/java/        # 11 个智能体、路由、状态机、RAG、APM 核心实现
│   └── src/main/resources/   # application.yml 配置与 Prompt 模板
├── mcp-server/               # 商业与题库 MCP 服务端 (Spring Boot 3)
│   └── src/main/java/        # 课程信息、题库权限隔离、订单工单接口
├── frontend/                 # 现代化用户与管理界面 (Vue 3 + Vite)
│   ├── src/views/            # 助教对话大厅、学情看板、知识库管理端
│   └── src/components/       # 实时防抖流式 Markdown 渲染、业务交互卡片
├── corpus/                   # 开源示范语料库 (纯 Markdown 格式)
│   ├── c001_Java基础/        # 示范课程一
│   └── c099_Go语言高并发/    # 零代码热插拔示范课程
├── docker/                   # 一键容器化部署配置
│   ├── docker-compose.yml    # 一键拉起 ES 8.15、MySQL 8.0、Redis 7.0
│   └── mysql/init/           # 自动初始化数据库建表与种子数据脚本
├── .env.example              # 环境变量配置模板
├── LICENSE                   # Apache-2.0 许可证
└── README.md                 # 项目文档
```

---

## ⚡ 5 分钟快速上手与配置指南 (Quick Start)

### 1. 环境准备
- **JDK 17+** 与 **Maven 3.8+**
- **Node.js 18+** 与 **npm**
- **Docker** 与 **Docker Compose** (推荐 Docker Desktop 或 Docker Engine 24+)

---

### 2. 配置项说明与密钥准备 (要配置什么？)

系统的所有配置均支持通过环境变量或 `application.yml` 进行注入。在项目根目录下提供了一份完整的配置模板 `.env.example`。

#### 2.1 配置项分级速查表

| 配置梯队 | 环境变量 / 配置项 | 推荐值 / 默认值 | 作用与必填说明 |
| :--- | :--- | :--- | :--- |
| **第一梯队：核心必配**<br>*(不配无法调用大模型)* | `OPENAI_API_KEY` | *(无默认值，**必填**)* | **大语言模型 API Key**。默认推荐填入 **DeepSeek** API Key。 |
| | `OPENAI_BASE_URL` | `https://api.deepseek.com` | 大模型 API 端点。若使用通义千问或 OpenAI 需对应修改。 |
| | `OPENAI_CHAT_MODEL` | `deepseek-chat` | 主对话模型名称。 |
| **第二梯队：RAG 检索增强**<br>*(可选，影响向量与重排)* | `EMBEDDING_API_KEY` | *(留空自动降级为 BM25)* | 阿里 DashScope（通义千问）Key，用于 1024 维密集向量检索 (`text-embedding-v3`)。 |
| | `RERANK_API_KEY` | *(留空则按 RRF 原序)* | 阿里 DashScope Key，用于第二阶段 Cross-Encoder 重排 (`gte-rerank-v2`)。 |
| **第三梯队：基础设施与拓扑**<br>*(开箱即用，Docker 无需改动)* | `MYSQL_HOST` / `PORT` | `127.0.0.1` / `3306` | MySQL 8.0 连接地址（用户 `root`，密码默认 `1234`，自动初始化两个库：`tutor_engine` 和 `tutor_biz`）。 |
| | `REDIS_HOST` / `PORT` | `127.0.0.1` / `6379` | Redis 7.0 连接地址（用于分布式限流、会话槽位缓存与语义缓存）。 |
| | `ES_HOST` / `PORT` | `127.0.0.1` / `9200` | Elasticsearch 8.15.0 连接地址（Docker 自动在线安装 `analysis-ik` 中文分词插件）。 |
| | `MCP_SERVER_PORT` | `8081` | MCP 商业与题库微服务端口。 |
| | `VITE_BACKEND_URL` | `http://127.0.0.1:8080` | 前端反向代理的主引擎端点。 |

#### 2.2 生成并填写 `.env` 配置文件
在项目根目录下复制模板：
- **Linux / macOS**:
  ```bash
  cp .env.example .env
  ```
- **Windows (PowerShell)**:
  ```powershell
  Copy-Item .env.example .env
  ```

用编辑器打开 `.env` 文件，填入你的大模型 API 密钥（至少填入 `OPENAI_API_KEY`）：
```env
# 大语言模型配置 (必填：推荐填入 DeepSeek API Key)
OPENAI_BASE_URL=https://api.deepseek.com
OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
OPENAI_CHAT_MODEL=deepseek-chat

# 向量检索与精排重排配置 (选填：若要体验 96% 召回的四阶混检 RAG 请填入通义千问 Key)
EMBEDDING_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
RERANK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

---

### 3. 一键启动基础设施 (Docker)

在项目**根目录**下直接执行 Docker Compose 命令（统一管理 MySQL、Redis、Elasticsearch 容器）：
```bash
docker compose -f docker/docker-compose.yml up -d
```
> [!NOTE]
> - 该命令会自动拉起 **MySQL 8.0**（端口 3306，自动导入 `docker/mysql/init/` 下的两个库建表脚本）、**Redis 7.0**（端口 6379）和 **Elasticsearch 8.15.0**（端口 9200）。
> - **首次启动**时，Elasticsearch 容器会自动在线下载安装 `analysis-ik 8.15.0` 中文分词插件，请等待约 20~30 秒，确认容器就绪后即可继续下一步。

---

### 4. 启动后端核心微服务

由于 Spring Boot 原生不会自动加载本地 `.env` 文件，请按以下任一方式**注入环境变量**后按序启动两组后端服务：

> **微服务启动顺序说明**：请先启动 `mcp-server`（端口 8081），再启动 `backend`（端口 8080），因为主引擎启动与语料同步依赖 MCP 端点。

#### 4.1 终端一：启动 MCP 业务与题库服务 (端口 8081)
```bash
cd mcp-server
mvn spring-boot:run
```

#### 4.2 终端二：启动助教主编排引擎 (端口 8080)
* **方式 A（Linux / macOS 终端直接导出环境变量）**：
  ```bash
  cd backend
  export $(grep -v '^#' ../.env | xargs) && mvn spring-boot:run
  ```
* **方式 B（Windows PowerShell 注入环境变量）**：
  ```powershell
  cd backend
  Get-Content ../.env | Where-Object { $_ -notmatch '^#' -and $_.Trim() } | ForEach-Object { $k,$v = $_.Split('=',2); [System.Environment]::SetEnvironmentVariable($k.Trim(), $v.Trim(), 'Process') }
  mvn spring-boot:run
  ```
* **方式 C（IntelliJ IDEA / VS Code 开发推荐）**：
  在 IDE 中安装 **EnvFile** 插件，在运行配置中勾选加载项目根目录的 `.env` 文件直接点击运行；或直接在 `backend/src/main/resources/application.yml` 的 `spring.ai.openai.api-key` 处填入你的 Key。

---

### 5. 启动前端界面 (端口 5173)

另开终端，启动现代化 Vue 3 交互前端：
```bash
cd frontend
npm install
npm run dev
```

---

### 6. 核心激活动作：语料与知识库一键同步 (首次运行必做！)

> [!IMPORTANT]
> 刚完成数据库与 ES 初始化时，系统内尚未加载具体课程语料与私有题库。**首次启动后必须执行一次一键同步**，系统会自动扫描 `corpus/` 语料库、构建中文分词索引、计算向量切片、动态热加载受控词典并同步入库：

* **方式 1 (界面一键点击·推荐)**：
  打开浏览器访问研发与管理看板端：👉 `http://localhost:5173/admin.html`
  点击页面顶部的 **【一键语料同步】** 按钮，等待 2 秒提示“语料与知识库热同步成功”即可。
* **方式 2 (命令行极速触发)**：
  在终端中直接执行：
  ```bash
  curl -X POST http://localhost:8080/api/admin/corpus/sync
  ```

---

### 7. 双端访问入口

* 🎓 **学员导学大厅**：`http://localhost:5173/`
  体验苏格拉底启发式导学、防作弊答题卡、长对话认知复盘与智能选课。
* 🛠️ **研发与知识库管理看板**：`http://localhost:5173/admin.html`
  查看 APM 全链路 Trace 耗时与 Token 计费、知识库多格式切片解析（Word/PDF/MD）、RAG 质量三元组与语料热同步。

---

## 🧪 自动化测试与验证

本项目配套了工业级全维度测试套件，在发布前已通过 **63 项核心测试用例（通过率 100%）**：
```bash
# 1. 运行核心意图路由与受控词典单元测试
cd backend
mvn test "-Dtest=IntentRouterFixTest,SlotsTest,SkillDictionaryDynamicTest"

# 2. 运行零代码新增课程全链路端到端自动化验证
node scripts/test-new-course-zero-code.mjs

# 3. 运行 7 轮高难度真实长对话评测
node scripts/real-world-long-turn-eval.mjs

# 4. 运行 40 组越狱与安全对抗攻防回归
node scripts/safety-eval.mjs
```

---

## 🤝 贡献指南 (Contributing)

欢迎任何形式的贡献！无论是报告 Bug、提出新特性，还是贡献新的学科语料 Markdown：
1. Fork 本仓库并新建分支（`git checkout -b feat/amazing-feature`）；
2. 提交代码更改并确保所有单元测试通过；
3. 发起 Pull Request (PR)。

---

## 📄 开源许可证 (License)

本项目基于 [Apache License 2.0](LICENSE) 协议开源。可免费商用与二次开发。
