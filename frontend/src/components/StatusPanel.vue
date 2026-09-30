<template>
  <aside class="status-panel">
    <!-- 1. 顶部 Header：智途伴航 -->
    <header class="panel-header">
      <div class="header-brand">
        <div class="brand-compass">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" class="compass-icon">
            <circle cx="12" cy="12" r="9.5" stroke="currentColor" stroke-width="1.6"/>
            <polygon points="12,5 14.5,12 12,19 9.5,12" fill="currentColor" opacity="0.9"/>
          </svg>
        </div>
        <div class="brand-text">
          <span class="brand-title">智途伴航</span>
          <span class="brand-badge">STUDIO</span>
        </div>
      </div>

      <div class="header-status">
        <div class="status-dot" :class="{ active: state.streaming }"></div>
        <span class="status-label">{{ state.streaming ? '思考生成中' : (state.sessionState ? badgeLabel : '伴学就绪') }}</span>
      </div>
    </header>

    <!-- 2. 主滚动工作区（平滑无痕隐形滚动） -->
    <div class="panel-scroll-body">
      <!-- 知识脉络导航面包屑（随会话主题动态生成） -->
      <div class="breadcrumb-bar">
        <template v-for="(item, idx) in breadcrumbItems" :key="idx">
          <span v-if="idx > 0" class="bc-sep">/</span>
          <span class="bc-item" :class="{ active: idx === breadcrumbItems.length - 1 }">{{ item }}</span>
        </template>
      </div>

      <!-- 核心板块一：动态随堂考点闪卡 (Live Knowledge Flashcard) -->
      <section class="studio-card flashcard-card">
        <div class="card-head">
          <div class="card-caption">
            <span class="badge-accent">考点速记卡</span>
            <span class="flashcard-level">{{ activeFlashcard.level }}</span>
          </div>
          <button class="flashcard-ask-btn" :disabled="state.streaming" @click="queryTag(activeFlashcard.title)">
            深入研读
          </button>
        </div>

        <div class="flashcard-title">{{ activeFlashcard.title }}</div>

        <ul class="flashcard-points">
          <li v-for="(p, idx) in activeFlashcard.points" :key="idx" class="point-item">
            <span class="point-bullet"></span>
            <span class="point-text">
              <strong class="point-label">{{ p.label }}：</strong>{{ p.value }}
            </span>
          </li>
        </ul>

        <div class="flashcard-footer">
          <span class="footer-tip">💡 随堂考点根据当前会话自适应萃取</span>
        </div>
      </section>

      <!-- 核心板块二：当前聚焦技术标签云 -->
      <section class="studio-card">
        <div class="card-caption flex-between">
          <span class="caption-title">
            <svg viewBox="0 0 14 14" width="12" height="12" fill="none" class="caption-icon text-blue">
              <path d="M1.5 7.5l5-5h5v5l-5 5-5-5Z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/>
              <circle cx="9.5" cy="4.5" r="1" fill="currentColor"/>
            </svg>
            会话聚焦主题
          </span>
          <span class="caption-sub">点击探究</span>
        </div>

        <div v-if="currentTags.length" class="tag-cloud">
          <button
            v-for="tag in currentTags"
            :key="tag"
            class="tag-capsule"
            :disabled="state.streaming"
            @click="queryTag(tag)"
            :title="'深入探讨：' + tag"
          >
            <span class="hash-sym">#</span>
            <span class="tag-name">{{ tag.replace('#', '') }}</span>
          </button>
        </div>
        <div v-else class="empty-tag-tip">
          <span>💡 提出具体学科或技术问题即可在此点亮考点</span>
        </div>
      </section>

      <!-- 核心板块三：智能伴学智囊工具矩阵 (Copilot Action Matrix) -->
      <section class="studio-card">
        <div class="card-caption">
          <span class="caption-title">
            <svg viewBox="0 0 14 14" width="12" height="12" fill="none" class="caption-icon text-purple">
              <path d="M7 1v12M1 7h12M3 3l8 8M11 3l-8 8" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
            </svg>
            智能伴学指令舱
          </span>
        </div>

        <div class="matrix-grid">
          <button
            class="matrix-btn"
            :disabled="state.streaming"
            @click="triggerShortcut('exercise')"
          >
            <div class="btn-top">
              <span class="btn-icon bg-orange">🎯</span>
              <span class="btn-title">现场出题</span>
            </div>
            <span class="btn-desc">基于当前考点检验</span>
          </button>

          <button
            class="matrix-btn"
            :disabled="state.streaming"
            @click="triggerShortcut('mindmap')"
          >
            <div class="btn-top">
              <span class="btn-icon bg-purple">🗺️</span>
              <span class="btn-title">思维导图</span>
            </div>
            <span class="btn-desc">梳理系统技术脉络</span>
          </button>

          <button
            class="matrix-btn"
            :disabled="state.streaming"
            @click="triggerShortcut('summary')"
          >
            <div class="btn-top">
              <span class="btn-icon bg-green">📋</span>
              <span class="btn-title">复盘笔记</span>
            </div>
            <span class="btn-desc">一键提炼避坑指南</span>
          </button>

          <button
            class="matrix-btn"
            :disabled="state.streaming"
            @click="triggerShortcut('advance')"
          >
            <div class="btn-top">
              <span class="btn-icon bg-blue">🚀</span>
              <span class="btn-title">进阶路线</span>
            </div>
            <span class="btn-desc">探索下一阶段架构</span>
          </button>
        </div>
      </section>

      <!-- 核心板块四：薄弱盲区与实战战报（按需展开，富有数据感） -->
      <section v-if="state.sessionState?.weakPoints?.length" class="studio-card">
        <div class="card-caption flex-between">
          <span class="caption-title">
            <svg viewBox="0 0 12 12" width="11" height="11" fill="none" class="caption-icon text-orange">
              <path d="M6 1L7.5 4.5H11L8.2 6.8 9.3 10.5 6 8.3 2.7 10.5l1.1-3.7L1 4.5h3.5L6 1Z" stroke="currentColor" stroke-width="1.1" stroke-linejoin="round"/>
            </svg>
            薄弱盲区攻坚
          </span>
          <span class="warn-pill">{{ state.sessionState.weakPoints.length }}项待提升</span>
        </div>

        <div class="weak-list">
          <div v-for="w in state.sessionState.weakPoints" :key="w.knowledgePoint" class="weak-item">
            <div class="weak-meta">
              <span class="weak-name">{{ w.knowledgePoint }}</span>
              <span class="weak-score" :class="w.score < 60 ? 'c-warn' : 'c-ok'">{{ w.score }}分</span>
            </div>
            <div class="weak-bar">
              <div
                class="weak-fill"
                :class="w.score < 60 ? 'fill-warn' : 'fill-ok'"
                :style="{ width: w.score + '%' }"
              />
            </div>
            <button
              class="weak-action-btn"
              :disabled="state.streaming"
              @click="attackWeakPoint(w)"
            >
              <span>⚡ 发起针对突破</span>
            </button>
          </div>
        </div>
      </section>

      <!-- 实战测验表现（如有成绩） -->
      <section v-if="state.sessionState?.lastGrade != null" class="studio-card">
        <div class="card-caption">
          <span class="caption-title">
            <svg viewBox="0 0 12 12" width="11" height="11" fill="none" class="caption-icon">
              <rect x="1" y="3" width="10" height="8" rx="1.5" stroke="currentColor" stroke-width="1.2"/>
              <path d="M4 3V2a2 2 0 0 1 4 0v1" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
              <path d="M4 7l1.5 1.5L8.5 5.5" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            实战测验评分
          </span>
        </div>
        <div class="grade-row">
          <div class="grade-ring" :style="{ background: gradeRingBg }">
            <div class="grade-hole">
              <span class="grade-val" :style="{ color: gradeColor }">{{ state.sessionState.lastGrade }}</span>
              <span class="grade-max">/100</span>
            </div>
          </div>
          <div class="grade-details">
            <div class="grade-badge" :class="gradeTextClass">{{ gradeLabel }}</div>
            <div class="grade-tip">{{ gradeHint }}</div>
          </div>
        </div>
      </section>

      <!-- 新会话探索推荐（会话为空时） -->
      <section v-if="!currentMessages.length" class="studio-card starter-card">
        <div class="card-caption">💡 探索灵感</div>
        <div class="starter-list">
          <button
            v-for="q in starterQuestions"
            :key="q"
            class="starter-btn"
            :disabled="state.streaming"
            @click="send(q)"
          >
            {{ q }}
          </button>
        </div>
      </section>
    </div>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { useChat } from '../stores/chat'

const { state, currentMessages, send } = useChat()

// ---------- 顶部状态 ----------
const BADGE_MAP = {
  DIAGNOSED: { label: '已诊断', cls: 'badge-blue' },
  PLANNED:   { label: '已规划', cls: 'badge-blue' },
  LEARNING:  { label: '学习中', cls: 'badge-green' },
  PRACTICING:{ label: '练习中', cls: 'badge-orange' },
  EVALUATED: { label: '已评估', cls: 'badge-green' },
  REPLANNED: { label: '已重规划', cls: 'badge-orange' },
  PLACEMENT: { label: '摸底测试', cls: 'badge-blue' },
  HUMAN_HANDOFF: { label: '已转人工', cls: 'badge-orange' },
}
const badgeLabel = computed(() => BADGE_MAP[state.sessionState?.state]?.label || '就绪')
const badgeClass  = computed(() => BADGE_MAP[state.sessionState?.state]?.cls || 'badge-blue')

// ---------- 知识面包屑（从会话真实主题标签生成，无硬编码领域词） ----------
const breadcrumbItems = computed(() => {
  const tags = (currentTags.value || []).map(t => t.replace('#', ''))
  if (tags.length >= 2) return [tags[1], tags[0]]
  if (tags.length === 1) return ['全学科伴学', tags[0]]
  return ['全学科伴学', '智能伴学']
})

// ---------- 动态随堂考点闪卡 (Flashcard) ----------
const activeFlashcard = computed(() => {
  const msgs = currentMessages.value || []
  const recentText = msgs.slice(-5).map(m => {
    if (typeof m.content === 'string') return m.content
    return m.content?.text || ''
  }).join(' ')

  if (recentText.includes('HashMap') || recentText.includes('红黑树')) {
    return {
      title: 'HashMap 底层结构与树化 (JDK 8)',
      level: '高频核心考点 · P6+必考',
      points: [
        { label: '数据结构', value: '数组 + 单向链表 + 红黑树' },
        { label: '树化门槛', value: '链表长度 ≥ 8 且 数组容量 ≥ 64' },
        { label: '扩容阈值', value: '0.75 负载因子（泊松分布最佳权衡）' },
        { label: '并发安全', value: '非线程安全，多线程并发 put 易数据覆盖' }
      ]
    }
  }

  if (recentText.includes('ConcurrentHashMap') || recentText.includes('分段锁') || recentText.includes('CAS')) {
    return {
      title: 'ConcurrentHashMap 锁演进机制',
      level: '高并发深度考点 · 必问',
      points: [
        { label: 'JDK 7 实现', value: 'Segment 分段锁（继承自 ReentrantLock）' },
        { label: 'JDK 8 改造', value: 'Node + CAS + synchronized 细粒度桶锁' },
        { label: '计数优化', value: 'CounterCell 分散并发计数（LongAdder 思想）' },
        { label: '性能收益', value: '锁粒度更细、消除 Segment 额外内存开销' }
      ]
    }
  }

  if (recentText.includes('两数之和') || recentText.includes('力扣')) {
    return {
      title: '哈希查找与两数之和算法',
      level: '算法思维 · 时间换空间',
      points: [
        { label: '核心思路', value: '维护 HashMap 记录 target - num[i] 的下标' },
        { label: '时空复杂度', value: '时间 O(N) 一次遍历，空间 O(N)' },
        { label: '边界注意', value: '同一元素不可重复利用，注意哈希冲突' }
      ]
    }
  }

  if (recentText.includes('Go') || recentText.includes('GMP') || recentText.includes('Goroutine') || recentText.includes('Channel')) {
    return {
      title: 'Go 语言高并发实战研习档案',
      level: '云原生高并发考点 · 核心',
      points: [
        { label: '调度模型', value: 'GMP 协程调度（M系统线程/P逻辑处理器/G协程）' },
        { label: '通信哲学', value: 'Don\'t communicate by sharing memory, share memory by communicating' },
        { label: '安全防坑', value: '注意 Channel 读写关闭与死锁检测' }
      ]
    }
  }

  // 默认知识闪卡：全学科自适应通用伴学空间
  return {
    title: '智能伴学 · 综合研习空间',
    level: '全学科多智能体导学',
    points: [
      { label: '研思方向', value: '支持技术原理探究、代码实战、架构设计与学情摸底' },
      { label: '学习建议', value: '可随时提出具体学科问题，或点击指令舱进行定向测验' },
      { label: '助教就绪', value: '随堂考点卡片将根据会话深入实时自适应动态萃取' }
    ]
  }
})

// ---------- 动态萃取会话聚焦主题标签云（100% 零硬编码） ----------
const STOP_WORDS = new Set([
  'true', 'false', 'null', 'undefined', 'string', 'number', 'boolean', 'text', 'return',
  'const', 'let', 'var', 'class', 'public', 'private', 'void', 'static', 'final',
  'import', 'from', 'package', 'module', 'exports', 'function', 'async', 'await'
])

const currentTags = computed(() => {
  const found = new Set()

  // 1. 优先从认知诊断薄弱点萃取（真实学情数据源）
  if (state.sessionState?.weakPoints) {
    for (const w of state.sessionState.weakPoints) {
      if (w.knowledgePoint && w.knowledgePoint.trim()) {
        found.add('#' + w.knowledgePoint.trim())
      }
    }
  }

  const msgs = currentMessages.value || []

  // 2. 从消息卡片（agent_trace、exercise、diagnosis）中动态提取明确考点
  for (let i = msgs.length - 1; i >= 0 && found.size < 4; i--) {
    const m = msgs[i]
    // 2.1 练习题卡片中的 kp
    if (m.type === 'exercise' && m.content) {
      if (m.content.questions && Array.isArray(m.content.questions)) {
        for (const q of m.content.questions) {
          if (q.kp && q.kp.trim()) found.add('#' + q.kp.trim())
        }
      }
      const tipMatch = m.content.tip?.match(/「([^」]+)」/)
      if (tipMatch) found.add('#' + tipMatch[1].trim())
    }
    // 2.2 agent_trace 中的步骤抽取
    if (m.type === 'agent_trace' && m.content?.steps) {
      for (const st of m.content.steps) {
        const actionMatch = st.action?.match(/(?:知识点|考点|考查|探讨)[「“"']?([^」”"'\s,，。]+)[」”"']?/)
        if (actionMatch) found.add('#' + actionMatch[1].trim())
      }
    }
  }

  // 3. 从最近对话文本中动态提取代码专有名词（反引号包裹的技术实体，如 `Goroutine`、`Span`、`HashMap`）
  if (found.size < 4) {
    const recentMsgs = msgs.slice(-4)
    for (let i = recentMsgs.length - 1; i >= 0 && found.size < 4; i--) {
      const m = recentMsgs[i]
      const text = typeof m.content === 'string' ? m.content : (m.content?.text || '')
      if (!text) continue

      // 正则提取反引号包裹的技术实体
      const codeMatches = text.match(/`([A-Za-z0-9_\u4e00-\u9fa5\.\-\+]{2,24})`/g) || []
      for (const raw of codeMatches) {
        const entity = raw.replace(/`/g, '').trim()
        // 过滤语法停用词、纯数字及过短符号
        if (!STOP_WORDS.has(entity.toLowerCase()) && !/^\d+$/.test(entity) && entity.length >= 2) {
          found.add('#' + entity)
          if (found.size >= 4) break
        }
      }
    }
  }

  return Array.from(found).slice(0, 4)
})

function queryTag(tag) {
  const clean = tag.replace('#', '')
  send(`请帮我重点深入剖析「${clean}」的核心底层原理与面试高频考点`)
}

// ---------- 快捷指令 ----------
function triggerShortcut(type) {
  if (type === 'exercise') {
    if (currentTags.value && currentTags.value.length > 0) {
      const topic = currentTags.value[0].replace('#', '')
      send(`请针对「${topic}」核心考点，出一道经典考题考考我，包含代码片段与选项`)
    } else {
      send('请帮我出一道考题检验一下技术掌握情况')
    }
  } else if (type === 'mindmap') {
    send('请帮我把刚才讨论的技术要点提炼成结构清晰的思维导图和核心对比总结')
  } else if (type === 'summary') {
    send('请帮我复盘提炼我们今天探讨的核心重点和关键技术盲区')
  } else if (type === 'advance') {
    send('学完当前知识点后，接下来建议我重点学习哪些关联的进阶核心知识？')
  }
}

// ---------- 薄弱点攻坚 ----------
function attackWeakPoint(w) {
  send(`请针对我的薄弱知识点「${w.knowledgePoint}」，帮我重点进行深度精讲，并出一道经典变式题巩固`)
}

// ---------- 破冰推荐问题 ----------
const starterQuestions = [
  '我是零基础转码，请帮我规划 Java 后端学习路线',
  'HashMap 底层原理和红黑树扩容怎么通俗理解？',
  '多线程并发下 HashMap 为什么会引发灾难？'
]

// ---------- 成绩圆环 ----------
const gradeColor = computed(() => {
  const g = state.sessionState?.lastGrade ?? 0
  return g >= 80 ? 'var(--green)' : g >= 60 ? 'var(--orange)' : 'var(--red)'
})
const gradeRingBg = computed(() => {
  const g = state.sessionState?.lastGrade ?? 0
  const c = g >= 80 ? 'var(--green)' : g >= 60 ? 'var(--orange)' : 'var(--red)'
  return `conic-gradient(${c} ${g * 3.6}deg, rgba(0,0,0,0.06) 0deg)`
})
const gradeTextClass = computed(() => {
  const g = state.sessionState?.lastGrade ?? 0
  return g >= 80 ? 'c-ok' : g >= 60 ? 'c-warn' : 'c-bad'
})
const gradeLabel = computed(() => {
  const g = state.sessionState?.lastGrade ?? 0
  return g >= 80 ? '掌握优秀' : g >= 60 ? '表现良好' : '仍需巩固'
})
const gradeHint = computed(() => {
  const g = state.sessionState?.lastGrade ?? 0
  return g < 60 ? '已自动为你生成强化复习计划' : '功底扎实，建议乘胜追击！'
})
</script>

<style scoped>
.status-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  box-sizing: border-box;
  background: var(--surface-glass);
  backdrop-filter: var(--material-blur);
  -webkit-backdrop-filter: var(--material-blur);
  border-left: 0.5px solid var(--hairline);
  padding: 16px 14px 18px;
  gap: 12px;
  overflow: hidden;
}

/* 1. 顶部 Header */
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 2px;
  flex: none;
}
.header-brand {
  display: flex;
  align-items: center;
  gap: 8px;
}
.brand-compass {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: var(--blue-soft);
  color: var(--blue);
  display: grid;
  place-items: center;
  flex: none;
}
.compass-icon {
  animation: compass-spin 26s linear infinite;
}
@keyframes compass-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.brand-text {
  display: flex;
  align-items: baseline;
  gap: 5px;
}
.brand-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-1);
  letter-spacing: -0.01em;
}
.brand-badge {
  font-size: 9px;
  font-weight: 700;
  color: var(--blue);
  background: var(--blue-soft);
  padding: 1px 5px;
  border-radius: 4px;
  letter-spacing: 0.05em;
}

.header-status {
  display: flex;
  align-items: center;
  gap: 5px;
}
.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--green);
  box-shadow: 0 0 5px rgba(26, 127, 55, 0.4);
  transition: all 0.3s ease;
}
.status-dot.active {
  background: var(--orange);
  box-shadow: 0 0 7px rgba(154, 103, 0, 0.7);
  animation: pulse 1.2s infinite;
}
@keyframes pulse {
  0% { transform: scale(0.95); opacity: 0.8; }
  50% { transform: scale(1.3); opacity: 1; }
  100% { transform: scale(0.95); opacity: 0.8; }
}
.status-label {
  font-size: 10px;
  font-weight: 600;
  color: var(--text-3);
}

/* 滚动工作区（隐形平滑滚动） */
.panel-scroll-body {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-right: 1px;
  scrollbar-width: none;
  -ms-overflow-style: none;
}
.panel-scroll-body::-webkit-scrollbar {
  display: none;
}

/* 面包屑导航条 */
.breadcrumb-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: var(--text-3);
  padding: 0 2px;
  flex: none;
}
.bc-item.active {
  color: var(--blue);
  font-weight: 600;
}
.bc-sep {
  opacity: 0.4;
}

/* 通用精致卡片 */
.studio-card {
  box-sizing: border-box;
  width: 100%;
  background: var(--surface);
  border: 0.5px solid var(--hairline);
  border-radius: var(--radius-md);
  padding: 12px 13px;
  box-shadow: var(--shadow-card);
  transition: all 0.2s ease;
}
.studio-card:hover {
  border-color: var(--hairline-strong);
}

.card-caption {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10.5px;
  font-weight: 700;
  color: var(--text-3);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 9px;
}
.caption-title {
  display: flex;
  align-items: center;
  gap: 5px;
}
.flex-between { justify-content: space-between; }
.caption-sub { font-size: 9.5px; color: var(--text-3); font-weight: 400; text-transform: none; }
.text-blue   { color: var(--blue); }
.text-purple { color: #7c3aed; }
.text-orange { color: var(--orange); }

/* 核心考点速记卡片 (Flashcard) */
.flashcard-card {
  background: linear-gradient(135deg, rgba(9, 105, 218, 0.03), rgba(124, 58, 237, 0.04));
  border: 0.5px solid rgba(9, 105, 218, 0.15);
  position: relative;
  overflow: hidden;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.badge-accent {
  font-size: 9.5px;
  font-weight: 700;
  color: var(--blue);
  background: var(--blue-soft);
  padding: 2px 6px;
  border-radius: 4px;
}
.flashcard-level {
  font-size: 9.5px;
  color: #7c3aed;
  font-weight: 600;
  margin-left: 5px;
}
.flashcard-ask-btn {
  font-size: 10px;
  font-weight: 600;
  color: var(--blue);
  background: #fff;
  border: 0.5px solid rgba(9, 105, 218, 0.2);
  padding: 2px 7px;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.flashcard-ask-btn:hover:not(:disabled) {
  background: var(--blue-soft);
}
.flashcard-title {
  font-size: 12.5px;
  font-weight: 700;
  color: var(--text-1);
  margin-bottom: 9px;
  line-height: 1.35;
}
.flashcard-points {
  list-style: none;
  padding: 0;
  margin: 0 0 9px;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.point-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 11px;
  line-height: 1.45;
  color: var(--text-2);
}
.point-bullet {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--blue);
  flex: none;
  transform: translateY(-1px);
}
.point-label {
  color: var(--text-1);
}
.flashcard-footer {
  border-top: 0.5px dashed rgba(0, 0, 0, 0.08);
  padding-top: 6px;
}
.footer-tip {
  font-size: 9.5px;
  color: var(--text-3);
}

/* 标签云 */
.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.empty-tag-tip {
  font-size: 11px;
  color: var(--text-3);
  padding: 6px 2px;
  line-height: 1.4;
}
.tag-capsule {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 10.5px;
  font-weight: 500;
  color: var(--text-2);
  background: var(--surface-2);
  border: 0.5px solid var(--hairline);
  padding: 3.5px 9px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.16s ease;
}
.hash-sym {
  color: var(--blue);
  font-weight: 600;
  opacity: 0.8;
}
.tag-capsule:hover:not(:disabled) {
  background: var(--blue-soft);
  color: var(--blue);
  border-color: rgba(9, 105, 218, 0.25);
  transform: translateY(-1px);
}
.tag-capsule:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 智能伴学指令矩阵 (2x2 网格，圆角精致卡片) */
.matrix-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 7px;
}
.matrix-btn {
  display: flex;
  flex-direction: column;
  padding: 8px 8px 7px;
  background: var(--surface-2);
  border: 0.5px solid var(--hairline);
  border-radius: 8px;
  text-align: left;
  cursor: pointer;
  transition: all 0.16s ease;
}
.matrix-btn:hover:not(:disabled) {
  background: var(--surface);
  border-color: var(--hairline-strong);
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.04);
  transform: translateY(-1px);
}
.matrix-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-top {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 3px;
}
.btn-icon {
  width: 20px;
  height: 20px;
  border-radius: 5px;
  display: grid;
  place-items: center;
  font-size: 11px;
  flex: none;
}
.bg-orange { background: var(--orange-soft); }
.bg-purple { background: var(--purple-soft); }
.bg-green  { background: var(--green-soft);  }
.bg-blue   { background: var(--blue-soft);   }

.btn-title {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-1);
}
.btn-desc {
  font-size: 9.5px;
  color: var(--text-3);
  line-height: 1.25;
}

/* 薄弱盲区列表 */
.warn-pill {
  font-size: 9.5px;
  color: #b45309;
  font-weight: 600;
  background: var(--orange-soft);
  padding: 1px 5px;
  border-radius: 4px;
}
.weak-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.weak-item {
  background: var(--surface-2);
  border: 0.5px solid var(--hairline);
  border-radius: 7px;
  padding: 8px 10px;
}
.weak-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 5px;
}
.weak-name {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-1);
}
.weak-score {
  font-size: 10.5px;
  font-weight: 700;
}
.c-warn { color: var(--orange); }
.c-ok   { color: var(--green);  }
.c-bad  { color: var(--red);    }

.weak-bar {
  height: 4px;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 2px;
  overflow: hidden;
  margin-bottom: 7px;
}
.weak-fill {
  height: 100%;
  border-radius: 2px;
  transition: width 0.35s ease;
}
.fill-warn { background: var(--orange); }
.fill-ok   { background: var(--green);  }

.weak-action-btn {
  width: 100%;
  padding: 3.5px 0;
  background: #fff;
  border: 0.5px solid rgba(154, 103, 0, 0.25);
  color: var(--orange);
  font-size: 10px;
  font-weight: 600;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.16s ease;
}
.weak-action-btn:hover:not(:disabled) {
  background: var(--orange-soft);
  border-color: var(--orange);
}
.weak-action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 测验表现 */
.grade-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.grade-ring {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  flex: none;
}
.grade-hole {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: var(--surface);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.grade-val { font-size: 12.5px; line-height: 1; font-weight: 800; }
.grade-max { font-size: 7px; color: var(--text-3); }
.grade-badge { font-size: 11px; font-weight: 700; margin-bottom: 2px; }
.grade-tip { font-size: 10px; color: var(--text-3); line-height: 1.3; }

/* 探索灵感 */
.starter-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.starter-btn {
  font-size: 11px;
  text-align: left;
  color: var(--text-2);
  background: var(--surface-2);
  border: 0.5px solid var(--hairline);
  padding: 6px 9px;
  border-radius: 5px;
  cursor: pointer;
  transition: all 0.16s ease;
}
.starter-btn:hover:not(:disabled) {
  background: var(--blue-soft);
  color: var(--blue);
  border-color: rgba(9, 105, 218, 0.25);
}
</style>
