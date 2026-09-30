# 智能助教引擎 · 后端（tutor-engine）

面向**成人职业教育的多智能体 AI 助教**：用户说"我想转行做 Java 开发"，引擎自动完成学情诊断 → 学习路径规划 → 知识讲解 → 练习批改 → 课程推荐/购买的教学闭环。

- 技术栈：Java 17 · Spring Boot 3.5.8 · Spring AI 1.1.2（OpenAI 兼容端点，DeepSeek/DashScope 可切换）· MyBatis-Plus · MySQL · Redis · **Elasticsearch 8.x 混合检索**
- 前端：`frontend/`（Vue 3 + Vite，`VITE_USE_MOCK=false` 切换到本后端 8080）
- 配套工程：**`tutor-mcp-server/`**——业务系统模拟器（课程/题库/订单/工单 4 域），MCP 协议接入，工具级数据权限（未购课拿不到私有题库）

## 测评数字（2026-09-05 全面测评，详见《智能助教引擎测评报告》）

| 维度 | 数字 |
|---|---|
| 意图识别 | 200 题评测 **93%**（宏 F1 94.7%）；L0 规则快路径命中 33.5%、准确率 97% |
| 学情诊断 | 金标准 40 组 **100%**（含效率修正/边界/多薄弱点排序） |
| RAG | Recall@1 **82.2%** / Recall@5 **96.0%**；Cross-Encoder 重排 **+5.9pt**（A/B 实证） |
| 安全 | 40 条对抗样本（注入/越权/学术诚信/越狱）**100% 抵抗** |
| 多轮会话 | 20 剧本 48 轮 **48/48**（槽位合并/指代消解/跨会话记忆/会话隔离） |
| 性能与成本 | 快路径首字 P50 **292ms**；完整闭环 P50 8.3s；单轮 **0.0124 元**；20 并发零死锁 |
| 容错 | 断 LLM 三连败**自动转人工**；断 ES 看门狗自愈；全链路降级闭环照跑 |
| 可观测 | Trace 三表落库回放、Token/成本核算、三路加权评估（基线 avgScore 90.53） |

## 快速开始

```bash
# 0) 前置：JDK 17 + Maven + Node 18；本机 MySQL(3306) / Redis(6379)
#    ES 8.x（可选，未启动时 RAG 降级为本地关键词检索）
docker-compose up -d        # 一键起 mysql/redis/elasticsearch（见 docker-compose.yml）

# 1) 建库
mysql -uroot --default-character-set=utf8mb4 < src/main/resources/db/schema.sql
mysql -uroot --default-character-set=utf8mb4 < src/main/resources/db/seed.sql

# 2) 密钥：application-local.yml（gitignored，模板 application-local.example.yml）
#    未配置 Key 时走全链路降级模式（规则路由/知识库直出/规则判分），闭环可完整演示

# 3) 语料摄入：D:/Workspace/知识库（或 tutor.corpus-dir 配置）→ ES + MySQL + 本地数据面
#    引擎侧（8080）：POST /api/admin/corpus/sync；MCP 侧（8081）：POST /api/admin/corpus/sync

# 4) 启动
mvn spring-boot:run                          # 引擎 8080
java -jar ../tutor-mcp-server/target/*.jar   # MCP 业务模拟器 8081

# 5) 验证
node scripts/e2e-test.mjs        # 59 项契约回归
node scripts/intent-eval.mjs     # 意图评测 200 题
node scripts/rag-eval.mjs        # RAG 检索评测 + 重排 A/B
```

评测脚本与数据集（意图 200 题/课程召回 150/安全 40/RAG 101/多轮剧本 20/诊断金标准 40）见 `scripts/` 与 `scripts/eval-data/`，全部可复跑。

## 架构（包结构即架构图）

```
com.tutor
├── api/            # 契约层：Controller + dto（前端契约唯一入口）
├── sse/            # SSE 传输：SseSession（字节级帧封装/心跳/中断检测）
├── router/         # 路由层：L0 规则快路径 → LLM 意图+槽位+stages 计划（一次调用）→ 三层兜底
│                     意图状态矫正 / 置信度校准 / 低置信迟滞 / 词典强约束
├── pipeline/       # 编排层：ChatPipelineRunner（stages 动态编排 + DAG 并行调度 + 会话锁）
├── agent/          # 执行层：诊断/规划/讲解/练习/批改/推荐/购买/澄清/闲聊 + Critic 质检 + 技能矩阵
├── knowledge/      # 知识层：ES 混合检索（BM25+向量 RRF）+ Cross-Encoder 重排 + 语义缓存
│                     语料同步管线（MD→ES/MySQL）+ ES 看门狗自愈 + 知识管理中枢
├── conversation/   # 领域-会话：会话/消息持久化（卡片即历史）
├── learning/       # 领域-学习：认知诊断（正确率×70+效率×30）+ 状态机 + 转人工
├── exercise/       # 领域-练习：双轨出题（LLM 现场生成 + 私有题库）+ 批改幂等 + 举一反三
├── trade/          # 领域-交易：预下单/支付状态机（幂等）
├── security/       # AI 安全网关：Prompt 注入检测 + 学术诚信守卫（建设性转化）
├── memory/         # 分层记忆：滑动窗口 + 超限摘要 + 跨会话长期画像
├── llm/            # 基础设施-LLM：LlmGateway（舱壁隔离/超时/重试限幅）+ Redis 记忆仓储
├── client/         # 防腐层：CourseClient/TradeClient/QuestionBankClient（本地 Mock 可切 MCP）
├── prompt/         # 提示词热更新（WatchService + AtomicReference）
├── common/         # Result / UserContext / SseCardType / TutorKeys / 全局异常
└── config/         # HTTP 超时 / 线程模型 / MybatisPlus / 属性
```

## 模型提供方（防腐层解耦，一处切换）

| 能力 | 当前 | 说明 |
|---|---|---|
| 对话/意图/批改 | DeepSeek `deepseek-chat` | `tutor.ai.chat-model` 配置化，换提供方零改码 |
| 向量化 | DashScope `text-embedding-v3`（1024 维） | OpenAI 兼容端点，可切 Ollama 本地模型 |
| 重排 | DashScope `gte-rerank-v2` | 无 Key 时自动降级 RRF 直通（dual-mode） |

## 踩坑记录（高价值）

- **P0·陈旧连接复用**：JDK17 HttpClient keepalive 1200s vs 服务端几十秒静默掐断——空闲后首请求 183s（60s 超时 ×3 重试）。迁移 Apache HttpClient 5（validateAfterInactivity + evictIdleConnections）+ httpcore5-reactive，空闲后首字 1.3s。
- **P0·无超时调用锁死线程池**：JDK HttpClient 超时默认无限，半开连接占死工作线程，队列过深阻断扩容——"心跳正常但业务假死"。修复：显式超时 + 重试限幅 + Semaphore 舱壁 + 探活。
- **重排静默失效**：DashScope rerank 无 OpenAI 兼容 /rerank 端点（404），dual-mode 降级把配置错误掩盖——重排上线即直通。修复：原生 `text-rerank` 端点（input/parameters 报文）。**教训：降级链必须配启动自检或指标暴露，否则掩盖配置错误。**
- **拦截器短路兜底链**：UserContextInterceptor 无登录头预填默认用户，导致"请求体 userId 兜底"永不生效，多用户隔离被架空。教训：默认值兜底只留一个出口。
- **ES 双写翻倍**：两条摄取路径 _id 策略不一致（自动 vs chunkId），ES 崩溃恢复后同内容两份。教训：同一索引的多写入方必须约定同一幂等键。
- **DAG 并行回退状态机**：并行组执行完剩余串行 stages 再写前序状态，终态被回退。教训：状态跃迁由编排层在并行 join 后统一裁决。
- **SseEmitter 两个坑**：`event:xxx` 无空格 vs 前端正则要求空格；`send(Object, MediaType)` 会再包 `data:` 前缀。方案：父类 ResponseBodyEmitter + 自组字节帧。
- **Windows 日志排查**：控制台 GBK 中文会让 grep 误判二进制——用 `grep -a`。

## 目录外置资源（热更新/数据面）

```
config/prompts/   router/teaching/chitchat/grading/recommend/plan/question_gen .md（改完即时生效）
data/             knowledge-chunks.json / courses.json / question-bank.json / kp-dictionary.json
logs/             intent-YYYYMMDD.jsonl（意图路由日志，含路由来源与耗时，评测直接回放）
```
