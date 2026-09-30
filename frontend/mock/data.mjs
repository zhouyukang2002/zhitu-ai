// ============================================================
// Mock 场景数据：教学闭环（学情诊断→规划→讲解→练习→批改→报告）
// 所有卡片结构严格对齐《智能助教引擎前端开发文档》消息契约
// ============================================================

export const USER_ID = 1

/** 课程数据（Mock 题库/课程服务的静态面） */
export const courses = [
  {
    courseId: 'c_001',
    name: '一元二次方程专项突破',
    price: 99,
    tags: ['数学', '专项突破'],
    reason: '直击你当前最薄弱的「一元二次方程」，8 讲覆盖判别式与求根公式全部考点',
  },
  {
    courseId: 'c_002',
    name: '函数与代数基础巩固班',
    price: 129,
    tags: ['数学', '基础'],
    reason: '补齐函数概念与代数运算基础，为后续学习路径第 2-3 步铺路',
  },
  {
    courseId: 'c_003',
    name: '初中数学知识图谱精讲',
    price: 199,
    tags: ['数学', '体系化'],
    reason: '按知识图谱串联全部前置依赖，适合系统性重建基础',
  },
  {
    courseId: 'c_004',
    name: '期中冲刺：方程与函数真题演练',
    price: 159,
    tags: ['数学', '应试'],
    reason: '真题 + 错因分析，练完可再触发一轮学情诊断检验效果',
  },
]

/** 路由 + 流水线执行轨迹（agent_trace 卡片） */
export function traceCard(scene) {
  const base = [
    { agent: '路由智能体', action: '三级意图识别（场景→意图→槽位）', tool: '语义向量路由 + LLM', costMs: 86, status: 'success' },
  ]
  const pipeline = {
    diagnose: [
      { agent: '学情分析智能体', action: '读取答题记录，诊断薄弱点', tool: '认知诊断模型', costMs: 412, status: 'success' },
    ],
    plan: [
      { agent: '学习规划智能体', action: '知识图谱补齐学习路径', tool: '知识图谱 DAG + 图算法', costMs: 305, status: 'success' },
    ],
    teach: [
      { agent: '内容讲解智能体', action: '按知识点检索并讲解', tool: 'RAG 检索', costMs: 530, status: 'success' },
    ],
    exercise: [
      { agent: '练习生成智能体', action: '按薄弱点组卷', tool: '题库工具', costMs: 268, status: 'success' },
    ],
  }
  const steps = [...base]
  if (scene.diagnose) steps.push(...pipeline.diagnose)
  if (scene.plan) steps.push(...pipeline.plan)
  if (scene.teach) steps.push(...pipeline.teach)
  if (scene.exercise) steps.push(...pipeline.exercise)
  return { type: 'agent_trace', content: { steps } }
}

/** 薄弱点诊断卡片 */
export const diagnosisCard = {
  type: 'diagnosis',
  content: {
    weakPoints: [
      { knowledgePoint: '一元二次方程', score: 40, confidence: '高' },
      { knowledgePoint: '判别式与根的关系', score: 52, confidence: '高' },
      { knowledgePoint: '因式分解', score: 66, confidence: '中' },
    ],
    summary: '近 20 次答题记录显示：你在「一元二次方程」上正确率仅 40%，其中「判别式与根的关系」失分最多（耗时加权后仍为高频错误），建议优先补齐。',
  },
}

/** 学习计划卡片 */
export const planCard = {
  type: 'plan',
  content: {
    path: [
      { step: 1, title: '复习：方程的解与判别式', status: 'current' },
      { step: 2, title: '巩固：求根公式与化简', status: 'pending' },
      { step: 3, title: '进阶：因式分解法解方程', status: 'pending' },
      { step: 4, title: '应用：一元二次方程实际应用题', status: 'pending' },
    ],
    progress: 0.25,
  },
}

/** 内容讲解文本（流式输出，Markdown） */
export const teachingText = `## 一元二次方程：从判别式说起

一元二次方程的标准形式是 **ax² + bx + c = 0（a ≠ 0）**。判断它"有没有解、有几个解"，不需要真的去解，只需要看**判别式**：

> **Δ = b² - 4ac**

判别式像一盏信号灯：

- **Δ > 0**：方程有 **两个不相等** 的实数根
- **Δ = 0**：方程有 **两个相等** 的实数根（即一个重根）
- **Δ < 0**：方程 **没有实数根**

### 常见失分点

你之前的错题里，最典型的错误是**符号处理**：把 Δ = b² - 4ac 代入时，遇到 b 为负数忘记加括号，导致 Δ 算错。记住一个口诀：**"代入先加括号，负号不出错"**。

### 下一步

判断 Δ 只是第一步，接下来我们会练习"根据 Δ 的范围反推参数"的题型——这正是你薄弱点清单的下一项。先来做一组小练习吧。`

/** 练习题目（exercise 卡片） */
export function exerciseCard(exerciseId) {
  return {
    type: 'exercise',
    content: {
      exerciseId,
      tip: '共 3 题：2 道选择题 + 1 道简答题，提交后自动批改。',
      questions: [
        {
          id: 'q_1',
          type: 'choice',
          stem: '方程 x² - 4x + 4 = 0 的根的情况是（ ）',
          options: ['A. 有两个不相等的实数根', 'B. 有两个相等的实数根', 'C. 没有实数根', 'D. 无法确定'],
        },
        {
          id: 'q_2',
          type: 'choice',
          stem: '若关于 x 的方程 x² - 2x + m = 0 有两个不相等的实数根，则 m 的取值范围是（ ）',
          options: ['A. m < 1', 'B. m > 1', 'C. m ≤ 1', 'D. m ≥ 1'],
        },
        {
          id: 'q_3',
          type: 'short',
          stem: '不解方程，判断 2x² - 3x - 1 = 0 的根的情况，并说明理由。',
        },
      ],
    },
  }
}

/** 标准答案与评分点（Mock 批改引擎使用） */
export const answerKey = {
  q_1: { answer: 'B', score: 30 },
  q_2: { answer: 'A', score: 30 },
  q_3: {
    keywords: ['判别式', 'Δ', 'b²-4ac', '9+8', '17', '大于0', '两个不相等'],
    reference: 'Δ = (-3)² - 4×2×(-1) = 9 + 8 = 17 > 0，所以方程有两个不相等的实数根。',
    score: 40,
  },
}

/** 学情报告（report 卡片） */
export function reportCard(score) {
  return {
    type: 'report',
    content: {
      metrics: [
        { label: '知识点掌握度', value: Math.round(40 + score * 0.3), max: 100 },
        { label: '路径完成率', value: 50, max: 100 },
        { label: '本周练习正确率', value: score, max: 100 },
      ],
      summary: '本轮练习后，「判别式与根的关系」掌握度明显回升；「因式分解」仍是当前最弱项，建议按学习路径继续第 3 步。',
      trend: '掌握度较上周 +12，保持这个节奏。',
    },
  }
}

/** 历史会话种子数据（演示用） */
export function seedSessions() {
  const now = Date.now()
  const e1 = exerciseCard('e_seed_001')
  return [
    {
      id: 's_seed_001',
      title: '一元二次方程补弱',
      updatedAt: now - 3600_000,
      state: 'PRACTICING',
      exercises: { e_seed_001: e1.content },
      messages: [
        { id: 'm_101', type: 'text', role: 'user', content: { text: '我方程总是算错，帮我看看哪里有问题' }, ts: now - 3700_000 },
        { id: 'm_102', type: 'agent_trace', role: 'assistant', content: traceCard({ diagnose: true, plan: true, teach: true }).content, ts: now - 3690_000 },
        { id: 'm_103', type: 'diagnosis', role: 'assistant', content: diagnosisCard.content, ts: now - 3680_000 },
        { id: 'm_104', type: 'plan', role: 'assistant', content: planCard.content, ts: now - 3670_000 },
        { id: 'm_105', type: 'text', role: 'assistant', content: { text: teachingText }, ts: now - 3660_000 },
        { id: 'm_106', type: 'exercise', role: 'assistant', content: e1.content, ts: now - 3650_000 },
      ],
      grades: {}, // exerciseId → grade（幂等结果）
    },
    {
      id: 's_seed_002',
      title: '函数概念答疑',
      updatedAt: now - 86_400_000,
      state: 'LEARNING',
      exercises: {},
      messages: [
        { id: 'm_201', type: 'text', role: 'user', content: { text: '什么是函数的单调性？' }, ts: now - 87_000_000 },
        {
          id: 'm_202',
          type: 'text',
          role: 'assistant',
          content: {
            text: `## 函数的单调性

**定义**：设函数 f(x) 的定义域为 D，如果对于 D 上任意两点 x₁ < x₂，都有：

- f(x₁) **<** f(x₂)，则称 f(x) 在该区间上**单调递增**
- f(x₁) **>** f(x₂)，则称 f(x) 在该区间上**单调递减**

**直观理解**：递增就是"越走越高"，图象从左往右上升；递减则相反。

**常用工具**：定义法（作差 f(x₁) - f(x₂) 与 0 比较）、导数法。`,
          },
          ts: now - 86_900_000,
        },
      ],
      grades: {},
    },
  ]
}
