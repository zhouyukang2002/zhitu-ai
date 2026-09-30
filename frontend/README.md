# 智途 AI · 前端（Vue 3）

智途 AI 的用户端界面：单页三栏布局，覆盖「学情诊断 → 学习规划 → 讲解 → 练习 → 批改 → 评估」教学闭环的全部核心交互，视觉风格参考 Apple（浅灰底 #f5f5f7、主色 #0071e3、发丝线描边、毛玻璃材质、系统字体栈）。

## 快速开始

```bash
npm install
npm run dev        # 启动 Vite 开发服务器 (:5173)，自动代理到后端 :8080
```

打开 http://localhost:5173 ，试试这些指令：

| 输入 | 触发场景 |
|---|---|
| `帮我诊断一下学情` | 完整闭环：轨迹 → 诊断 → 计划 → 流式讲解 → 出题 |
| `再出一组练习题` | 只出题（进入练习环节） |
| `看看我的学情报告` | 生成学情报告卡片 |
| `推荐几门课程` / 诊断卡上的「看看配套课程」 | 课程推荐（旁路交易域） |
| 点课程卡片「购买」/ `我要买《课程名》` | 预下单 → 订单卡片 → 确认支付（幂等） |
| 模糊购买（如 `我想买个Java课`） | 返回候选列表让你选，**不擅自下单** |
| 其他任意消息 | 普通对话回复 |

答题后点击「提交答案」体验批改（选择题规则判分、简答题模拟 LLM 评分 + 错误归因；同 `exerciseId` 重复提交幂等返回）。

## 切换真实后端

```bash
# .env.local
VITE_USE_MOCK=false   # /api 代理到 localhost:8080
```

前端只依赖以下契约（详见开发文档第 6 节与《后端开发注意事项》），Mock 与真实后端可无缝互换：

- `POST /api/chat/stream`（SSE：`message` / `card` / `done` / `error` 四种事件）
- `GET /api/session/list`、`POST /api/session`、`GET /api/session/{id}/messages`、`GET /api/session/{id}/state`、`DELETE /api/session/{id}`
- `POST /api/exercise/submit`（幂等键 `exerciseId`，返回 `{ grade }`，前端本地渲染为 grade 卡片）
- `POST /api/order/pay`（幂等键 `orderId`，返回 `{ order, duplicated }`，前端原地翻转订单卡片状态）

统一错误体 `{code, message}`；消息 10 种 type（`text/agent_trace/diagnosis/plan/exercise/grade/report/system/course_list/course_order`），未知 type 兜底按 text 渲染。交易域是旁路：`course_list/course_order/system(购买)` 不推进教学状态机。

## 目录结构

```
frontend/
├─ src/
│  ├─ api/                # request / chat(SSE) / session(REST)
│  ├─ stores/chat.js      # 组合式 API 轻量全局状态（不引入 Pinia）
│  ├─ styles/             # tokens.css(设计令牌) + base.css(主题覆写)
│  └─ components/
│     ├─ Sidebar.vue      # 会话列表（macOS 风格选中态）
│     ├─ ChatArea.vue     # 消息流 + SSE 跟随滚动 + 输入区
│     ├─ StatusPanel.vue  # 状态机步骤条 + 学情摘要
│     ├─ common/MarkdownView.vue   # marked + highlight.js + DOMPurify
│     └─ messages/        # 10 种消息卡片 + type 分发器（含交易域 course_list/course_order）
```

## 实现要点（面试讲点）

- **SSE**：`fetch + ReadableStream` 手工分帧（按 `\n\n`），支持 POST 与 AbortController 中断；流式期间纯文本累积渲染、结束后一次性渲染 Markdown，避免闪烁
- **契约先行**：`type + content` 分离，卡片按类型分发（开放-封闭）；`card` 事件的 `id/role/ts` 由前端补齐
- **贴底跟随**：滚轮上滑暂停跟随、滑回底部恢复；程序化滚动不去 scroll 事件里判断"是否贴底"（会被自身滚动的中间态误判）
- **幂等与历史恢复**：本地 `submitted` 集合 + 历史 grade 卡片反推已提交状态
- **安全**：LLM 输出经 DOMPurify 消毒后再渲染
- **状态机**：`DIAGNOSED→PLANNED→LEARNING→PRACTICING→EVALUATED`（REPLANNED 回落到"规划"步骤），收到 grade/report/system 卡片后刷新右侧面板

## 致谢与借鉴出处

- [Chanzhaoyu/chatgpt-web](https://github.com/Chanzhaoyu/chatgpt-web)（MIT）：借鉴 AbortController 停止/重试模式与会话管理交互；SSE 解析为按本项目契约自行实现（参考项目为 axios 流式，未采用）
- 视觉规范参考 [Apple Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines/) 与 apple.com 公开视觉（系统字体栈、材质与配色），代码全部自行实现
- Element Plus 主题仅做 CSS 变量级苹果化覆写，未修改组件源码
