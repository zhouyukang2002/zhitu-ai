<template>
  <main class="chat-area">
    <!-- ── 顶部毛玻璃标题栏 ── -->
    <header class="chat-header">
      <div class="header-left">
        <div class="chat-title">{{ currentSession?.title || '新的对话' }}</div>
        <transition name="fade">
          <div v-if="state.sessionState" class="chat-state" :class="stateClass">
            <span class="state-dot" />
            {{ stateLabel }}
          </div>
        </transition>
      </div>
      <div class="header-right">
        <transition name="fade">
          <div v-if="state.streaming" class="streaming-badge">
            <span class="streaming-dot" />
            生成中
          </div>
        </transition>
      </div>
    </header>

    <!-- ── 错误提示条 ── -->
    <transition name="slide-down">
      <div v-if="state.error" class="error-bar">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="none" style="flex:none;color:var(--red)">
          <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.4"/>
          <path d="M8 5v3.5M8 11v.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
        </svg>
        <span class="error-text">{{ state.error.message }}</span>
        <button class="retry-btn" @click="retry">重试</button>
        <button class="dismiss-btn" @click="state.error = null">✕</button>
      </div>
    </transition>

    <!-- ── 消息流 ── -->
    <div ref="scrollRef" class="messages" @scroll="onScroll" @wheel.passive="onWheel">
      <div ref="contentRef">
        <!-- 空状态 Hero -->
        <div v-if="!currentMessages.length" class="hero">
          <div class="hero-logo">
            <svg viewBox="0 0 40 40" width="36" height="36" fill="none">
              <rect width="40" height="40" rx="12" fill="url(#heroGrad)"/>
              <path d="M10 20C10 14.5 14.5 10 20 10s10 4.5 10 10-4.5 10-10 10S10 25.5 10 20Z" stroke="#fff" stroke-width="1.6"/>
              <path d="M15 20h10M20 15v10" stroke="#fff" stroke-width="1.6" stroke-linecap="round"/>
              <defs>
                <linearGradient id="heroGrad" x1="0" y1="0" x2="40" y2="40" gradientUnits="userSpaceOnUse">
                  <stop stop-color="#0969da"/>
                  <stop offset="1" stop-color="#054da7"/>
                </linearGradient>
              </defs>
            </svg>
          </div>
          <h1 class="hero-title">定向技能进阶 · 科学职业转型</h1>
          <p class="hero-sub">智能助教为你诊断技术差距、定制转型路线、拆解实战原理、推荐优质课程</p>
          <div class="hero-chips">
            <button
              v-for="c in suggestions"
              :key="c"
              class="chip"
              :disabled="state.streaming"
              @click="send(c)"
            >{{ c }}</button>
          </div>
        </div>

        <!-- 消息列表 -->
        <div v-else class="msg-list">
          <template v-for="msg in currentMessages" :key="msg.id">
            <!-- 用户消息：右侧气泡 -->
            <div v-if="msg.role === 'user'" class="msg-user">
              <div class="user-bubble">{{ msg.content?.text }}</div>
            </div>
            <!-- 助手消息：结构化卡片 -->
            <div v-else class="msg-assistant">
              <MessageDispatcher :msg="msg" />
            </div>
          </template>

          <!-- 等待首个事件：输入中指示 -->
          <div v-if="state.generating" class="msg-assistant">
            <div class="typing card">
              <span /><span /><span />
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ── 回到底部按钮 ── -->
    <transition name="fade">
      <button v-if="!pinned && currentMessages.length" class="scroll-bottom" @click="forceScrollBottom">
        <svg viewBox="0 0 16 16" width="13" height="13" fill="none">
          <path d="M8 3v10M3.5 8.5 8 13l4.5-4.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </button>
    </transition>

    <!-- ── 输入区 ── -->
    <footer class="composer">
      <div class="composer-inner">
        <div class="composer-box" :class="{ focused: isFocused }">
          <textarea
            ref="inputRef"
            v-model="draft"
            class="composer-input"
            rows="1"
            placeholder="输入学科难题、考点困惑、课程政策或直接说「帮我规划复习」…"
            @keydown.enter.exact.prevent="onEnter"
            @input="autoGrow"
            @focus="isFocused = true"
            @blur="isFocused = false"
          />
          <div class="composer-actions">
            <button
              v-if="state.streaming"
              class="stop-btn"
              title="停止生成"
              @click="stop"
            >
              <span class="stop-icon" />
            </button>
            <button
              v-else
              class="send-btn"
              :disabled="!draft.trim()"
              title="发送（Enter）"
              @click="onEnter"
            >
              <svg viewBox="0 0 16 16" width="14" height="14">
                <path d="M8 13V3.5M8 3.5 3.8 7.7M8 3.5l4.2 4.2"
                  fill="none" stroke="currentColor" stroke-width="1.8"
                  stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </button>
          </div>
        </div>
        <div class="composer-hint">
          <kbd>Enter</kbd> 发送 &nbsp;·&nbsp; <kbd>Shift</kbd> + <kbd>Enter</kbd> 换行
          &nbsp;·&nbsp; AI 生成内容仅供参考
        </div>
      </div>
    </footer>
  </main>
</template>

<script setup>
import { ref, computed, nextTick, watch } from 'vue'
import MessageDispatcher from './messages/MessageDispatcher.vue'
import { useChat } from '../stores/chat'

const { state, currentSession, currentMessages, send, stop, retry } = useChat()

const draft = ref('')
const scrollRef = ref(null)
const contentRef = ref(null)
const inputRef = ref(null)
const pinned = ref(true)
const isFocused = ref(false)

const suggestions = [
  '🎯 我想转行做 Java 后端开发，目前零基础，帮我制定一份职业转型学习路线',
  '💡 深度讲解 MySQL 索引最左匹配原则与覆盖索引底层原理',
  '📝 出 2 道关于 Java 并发与集合框架的高频面试题考考我',
  '🚀 我有开发基础，想转大模型 AI 应用开发（RAG/Agent），推荐哪些实战课？',
]

const STATE_LABELS = {
  DIAGNOSED: '已诊断', PLANNED: '已规划', LEARNING: '学习中',
  PRACTICING: '练习中', EVALUATED: '已评估', REPLANNED: '已重规划',
}
const STATE_CLASSES = {
  DIAGNOSED: 'state-blue', PLANNED: 'state-blue', LEARNING: 'state-green',
  PRACTICING: 'state-orange', EVALUATED: 'state-green', REPLANNED: 'state-orange',
}
const stateLabel = computed(() => STATE_LABELS[state.sessionState?.state] || '')
const stateClass  = computed(() => STATE_CLASSES[state.sessionState?.state] || 'state-blue')

function onEnter() {
  if (state.streaming || !draft.value.trim()) return
  const text = draft.value
  draft.value = ''
  autoGrow()
  send(text)
}

function autoGrow() {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 140) + 'px'
}

function onWheel(e) {
  if (e.deltaY < 0) pinned.value = false
}

function onScroll() {
  const el = scrollRef.value
  if (el && el.scrollHeight - el.scrollTop - el.clientHeight < 60) pinned.value = true
}

// 切换会话状态标识：切换历史会话期间瞬时定位最底部，禁止触发任何逐条滑动动画
let isSwitchingSession = false

async function jumpToBottomInstant() {
  await nextTick()
  const el = scrollRef.value
  if (!el) return
  el.scrollTop = el.scrollHeight
  requestAnimationFrame(() => {
    if (el) el.scrollTop = el.scrollHeight
  })
  setTimeout(() => {
    if (el) el.scrollTop = el.scrollHeight
  }, 40)
}

async function scrollToBottom() {
  if (isSwitchingSession || !pinned.value) return
  await nextTick()
  const el = scrollRef.value
  if (el) el.scrollTop = el.scrollHeight
}

// 仅在用户手动点击「回到底部」浮标按钮时，才触发平滑滚动动画
function forceScrollBottom() {
  pinned.value = true
  const el = scrollRef.value
  if (el) {
    el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
  }
}

watch(() => currentMessages.value.map((m) => (m.type === 'text' ? m.content?.text?.length : 0)).join(','), scrollToBottom)

watch(currentMessages, async () => {
  if (isSwitchingSession) {
    await jumpToBottomInstant()
    return
  }
  scrollToBottom()
}, { deep: true })

watch(() => [state.streaming, state.generating], () => {
  if (!isSwitchingSession) {
    scrollToBottom()
  }
})

watch(() => state.currentId, async (newId, oldId) => {
  if (newId !== oldId) {
    isSwitchingSession = true
    pinned.value = true
    await jumpToBottomInstant()
    setTimeout(() => {
      jumpToBottomInstant()
      isSwitchingSession = false
    }, 120)
    inputRef.value?.focus()
  }
})
</script>

<style scoped>
.chat-area {
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100vh;
  position: relative;
  background: var(--bg);
}

/* ── 顶栏 ── */
.chat-header {
  flex: none;
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: var(--surface-glass);
  backdrop-filter: var(--material-blur);
  -webkit-backdrop-filter: var(--material-blur);
  border-bottom: 0.5px solid var(--hairline);
  position: relative;
  z-index: 5;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.header-right { flex: none; }

.chat-title {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.01em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 280px;
}
.chat-state {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 600;
  border-radius: 980px;
  padding: 3px 10px;
  white-space: nowrap;
  flex: none;
}
.state-dot {
  width: 5px; height: 5px;
  border-radius: 50%;
  flex: none;
}
.chat-state.state-blue  { background: var(--blue-soft);   color: var(--blue);   }
.chat-state.state-blue  .state-dot { background: var(--blue); }
.chat-state.state-green { background: var(--green-soft);  color: #1a7a3c; }
.chat-state.state-green .state-dot { background: var(--green); }
.chat-state.state-orange{ background: var(--orange-soft); color: #a05400; }
.chat-state.state-orange .state-dot { background: var(--orange); }

.streaming-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 600;
  color: var(--blue);
  background: var(--blue-soft);
  border-radius: 980px;
  padding: 3px 10px;
}
.streaming-dot {
  width: 5px; height: 5px;
  border-radius: 50%;
  background: var(--blue);
  flex: none;
  animation: streaming-blink 0.9s ease infinite;
}
@keyframes streaming-blink {
  0%, 100% { opacity: 1; transform: scale(1); }
  50%       { opacity: 0.3; transform: scale(0.6); }
}

/* ── 错误条 ── */
.error-bar {
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 8px auto 0;
  width: min(700px, calc(100% - 32px));
  padding: 9px 14px;
  background: var(--red-soft);
  border: 0.5px solid rgba(255,59,48,0.22);
  border-radius: var(--radius-md);
  position: absolute;
  top: 52px;
  left: 0; right: 0;
  z-index: 6;
  box-shadow: 0 4px 16px rgba(255,59,48,0.1);
}
.error-text { flex: 1; font-size: 12.5px; color: var(--red); }
.retry-btn {
  border: none;
  background: var(--red);
  color: #fff;
  font-size: 12px;
  font-weight: 500;
  padding: 4px 12px;
  border-radius: 980px;
  cursor: pointer;
  transition: opacity 0.15s;
}
.retry-btn:hover { opacity: 0.88; }
.dismiss-btn {
  border: none;
  background: transparent;
  color: var(--red);
  opacity: 0.5;
  cursor: pointer;
  font-size: 13px;
  padding: 2px 4px;
  transition: opacity 0.15s;
}
.dismiss-btn:hover { opacity: 0.9; }

/* ── 消息流 ── */
.messages {
  flex: 1;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.msg-list,
.hero {
  width: min(740px, calc(100% - 48px));
  margin: 0 auto;
  padding: 24px 0 16px;
}

.msg-assistant {
  margin-bottom: 12px;
  animation: rise 0.22s ease;
}
.msg-assistant > * {
  background: var(--surface);
  border: 0.5px solid var(--hairline);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: 16px 20px;
}
.msg-assistant > .system-card,
.msg-assistant > .typing {
  background: transparent;
  border: none;
  box-shadow: none;
  padding: 4px 0;
}
.system-card { max-width: none; }

.msg-user {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
  animation: rise 0.22s ease;
}
.user-bubble {
  max-width: 76%;
  padding: 10px 16px;
  background: linear-gradient(135deg, #0969da, #085cc0);
  color: #fff;
  font-size: 14.5px;
  line-height: 1.6;
  border-radius: 18px 18px 4px 18px;
  box-shadow: 0 2px 8px rgba(9, 105, 218, 0.18), 0 1px 2px rgba(9, 105, 218, 0.12);
  white-space: pre-wrap;
  word-break: break-word;
}

@keyframes rise {
  from { opacity: 0; transform: translateY(8px); }
  to   { opacity: 1; transform: translateY(0); }
}

/* 输入中指示（三点脉冲） */
.typing {
  display: inline-flex;
  gap: 5px;
  padding: 12px 4px !important;
  background: transparent !important;
}
.typing span {
  width: 7px; height: 7px;
  border-radius: 50%;
  background: var(--text-3);
  opacity: 0.5;
  animation: pulse 1.3s ease infinite;
}
.typing span:nth-child(2) { animation-delay: 0.15s; }
.typing span:nth-child(3) { animation-delay: 0.30s; }
@keyframes pulse {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.35; }
  30%           { transform: translateY(-5px); opacity: 1; }
}

/* ── 空状态 Hero ── */
.hero {
  padding-top: 14vh;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.hero-logo {
  width: 64px; height: 64px;
  border-radius: 18px;
  display: grid;
  place-items: center;
  margin-bottom: 20px;
  box-shadow: 0 6px 20px rgba(9, 105, 218, 0.2), 0 1px 3px rgba(9, 105, 218, 0.1);
}
.hero-title {
  font-size: 38px;
  font-weight: 700;
  letter-spacing: -0.03em;
  margin: 0 0 10px;
  background: linear-gradient(135deg, var(--text-1) 30%, #5a5a6a);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  line-height: 1.15;
}
.hero-sub {
  font-size: 15px;
  color: var(--text-2);
  margin: 0 0 28px;
  line-height: 1.6;
}
.hero-chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}
.chip {
  border: 0.5px solid var(--hairline-strong);
  background: var(--surface);
  color: var(--text-1);
  font-size: 13px;
  padding: 8px 18px;
  border-radius: 980px;
  cursor: pointer;
  transition: all 0.15s ease;
  box-shadow: var(--shadow-card);
}
.chip:hover {
  border-color: var(--blue);
  color: var(--blue);
  background: var(--blue-soft);
  box-shadow: 0 2px 10px rgba(0,113,227,0.14);
  transform: translateY(-1px);
}
.chip:disabled { opacity: 0.45; cursor: default; transform: none; box-shadow: none; }

/* ── 回到底部 ── */
.scroll-bottom {
  position: absolute;
  bottom: 90px;
  right: 24px;
  width: 34px; height: 34px;
  border-radius: 50%;
  border: 0.5px solid var(--hairline);
  background: var(--surface);
  box-shadow: var(--shadow-float);
  color: var(--text-2);
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: all 0.15s;
  z-index: 4;
}
.scroll-bottom:hover {
  background: var(--blue);
  color: #fff;
  border-color: var(--blue);
  box-shadow: 0 4px 14px rgba(0,113,227,0.3);
}

/* ── 输入区 ── */
.composer {
  flex: none;
  padding: 8px 20px 14px;
  background: var(--bg);
}
.composer-inner {
  width: min(740px, 100%);
  margin: 0 auto;
}
.composer-box {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 8px 8px 8px 16px;
  background: var(--surface);
  border: 0.5px solid var(--hairline-strong);
  border-radius: 22px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.05), 0 4px 16px rgba(0,0,0,0.04);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.composer-box.focused {
  border-color: var(--blue);
  box-shadow: 0 0 0 3.5px var(--blue-ring), 0 1px 3px rgba(0,0,0,0.05);
}
.composer-input {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  font-family: var(--font-family);
  font-size: 14.5px;
  line-height: 1.55;
  color: var(--text-1);
  max-height: 140px;
  padding: 5px 0;
}
.composer-input::placeholder { color: var(--text-3); }
.composer-actions { flex: none; }

.send-btn,
.stop-btn {
  width: 32px; height: 32px;
  border-radius: 50%;
  border: none;
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: all 0.15s;
}
.send-btn {
  background: var(--blue);
  color: #fff;
  box-shadow: 0 2px 8px rgba(0,113,227,0.3);
}
.send-btn:hover { background: var(--blue-hover); transform: scale(1.06); }
.send-btn:disabled {
  background: rgba(0,0,0,0.08);
  color: var(--text-3);
  cursor: default;
  box-shadow: none;
  transform: none;
}
.stop-btn {
  background: #1d1d1f;
}
.stop-btn:hover { background: #000; transform: scale(1.04); }
.stop-icon {
  width: 10px; height: 10px;
  background: #fff;
  border-radius: 2px;
}

.composer-hint {
  text-align: center;
  font-size: 10.5px;
  color: var(--text-3);
  margin-top: 7px;
  letter-spacing: 0.02em;
}
kbd {
  display: inline-block;
  padding: 1px 5px;
  border: 0.5px solid rgba(0,0,0,0.15);
  border-radius: 4px;
  font-size: 10px;
  font-family: var(--font-family);
  background: rgba(0,0,0,0.04);
  color: var(--text-2);
}

/* ── 动画 ── */
.fade-enter-active, .fade-leave-active { transition: opacity 0.2s ease; }
.fade-enter-from, .fade-leave-to       { opacity: 0; }

.slide-down-enter-active { transition: all 0.22s cubic-bezier(0.34, 1.1, 0.64, 1); }
.slide-down-leave-active { transition: all 0.15s ease; }
.slide-down-enter-from   { opacity: 0; transform: translateY(-8px); }
.slide-down-leave-to     { opacity: 0; transform: translateY(-4px); }
</style>
