// 轻量响应式全局状态（组合式 API 单例，不引入 Pinia）
import { reactive, computed } from 'vue'
import { streamChat } from '../api/chat'
import {
  fetchSessions, createSession, fetchMessages, fetchSessionState, deleteSession, submitExercise, payOrder,
} from '../api/session'

const getUserId = () => {
  try {
    const raw = localStorage.getItem('tutor_current_user')
    if (raw) return JSON.parse(raw).userId || 1
  } catch {}
  return 1
}

let localSeq = 0
const nextId = () => `local_${Date.now()}_${++localSeq}`

const state = reactive({
  sessions: [],            // [{id, title, updatedAt}]
  currentId: null,
  messagesBySession: {},   // { [sessionId]: [msg] }
  loaded: {},              // 已加载过历史消息的会话
  streaming: false,        // 本轮对话进行中（SSE 未 done）
  generating: false,       // 等待/正在生成（显示输入中的指示）
  error: null,             // 顶部错误条 {message}
  sessionState: null,      // 右侧面板：状态机 + 学情摘要
  submitted: {},           // { [exerciseId]: true } 已提交练习（含历史恢复）
})

const currentSession = computed(() => state.sessions.find((s) => s.id === state.currentId) || null)
const currentMessages = computed(() => state.messagesBySession[state.currentId] || [])

// ---------- 会话 ----------
async function resetAndReload() {
  if (state.streaming) stop()
  state.sessions = []
  state.currentId = null
  state.messagesBySession = {}
  state.loaded = {}
  state.sessionState = null
  state.error = null
  await loadSessions()
}

async function loadSessions() {
  state.sessions = await fetchSessions()
  if (!state.currentId && state.sessions.length) await openSession(state.sessions[0].id)
}

// 首条消息后后端会生成会话标题，仅同步列表（标题/排序），不动已加载的消息
async function refreshSessionTitles() {
  try { state.sessions = await fetchSessions() } catch { /* 列表刷新失败可忽略 */ }
}

async function openSession(id) {
  if (state.streaming) stop()
  state.currentId = id
  state.error = null
  if (!state.loaded[id]) {
    const msgs = await fetchMessages(id)
    state.messagesBySession[id] = msgs.map((m) => ({
      ...m,
      traceId: m.traceId || m.content?.traceId || null,
      streaming: false,
    }))
    // 历史恢复：练习若已有对应 grade 卡片，标记为已提交
    const graded = new Set(
      msgs.filter((m) => m.type === 'grade').map((m) => m.content?.exerciseId).filter(Boolean),
    )
    for (const eid of graded) state.submitted[eid] = true
    state.loaded[id] = true
  }
  refreshState()
}

async function newSession() {
  if (state.streaming) stop()
  const s = await createSession()
  state.sessions.unshift(s)
  state.messagesBySession[s.id] = []
  state.loaded[s.id] = true
  state.currentId = s.id
  state.sessionState = null
}

async function removeSession(id) {
  await deleteSession(id)
  const idx = state.sessions.findIndex((s) => s.id === id)
  if (idx >= 0) state.sessions.splice(idx, 1)
  delete state.messagesBySession[id]
  delete state.loaded[id]
  if (state.currentId === id) {
    state.currentId = null
    state.sessionState = null
    if (state.sessions.length) await openSession(state.sessions[Math.max(0, idx - 1)].id)
  }
}

// ---------- 状态面板 ----------
async function refreshState() {
  if (!state.currentId) { state.sessionState = null; return }
  try { state.sessionState = await fetchSessionState(state.currentId) } catch { state.sessionState = null }
}

// ---------- 对话 ----------
let controller = null
let pendingTextMsg = null // 当前流式文本消息
let lastUserText = ''

function finalizeText() {
  if (pendingTextMsg) {
    pendingTextMsg.streaming = false
    pendingTextMsg = null
  }
}

function handleDelta(delta) {
  if (!pendingTextMsg) {
    // 必须 reactive 包装：直接改普通对象不会触发视图更新
    pendingTextMsg = reactive({
      id: nextId(), type: 'text', role: 'assistant',
      content: { text: '' }, ts: Date.now(), streaming: true,
    })
    currentMessages.value.push(pendingTextMsg)
  }
  pendingTextMsg.content.text += delta
}

function handleCard(payload) {
  finalizeText()
  const type = payload?.type || 'text'
  const content = payload?.content ?? {}
  currentMessages.value.push({ id: nextId(), type, role: 'assistant', content, ts: Date.now(), streaming: false })
  // 契约：收到 grade / report / system（带 state）后刷新右侧面板
  if (['grade', 'report', 'system'].includes(type)) refreshState()
}

async function send(text) {
  const message = (text || '').trim()
  if (!message || state.streaming) return

  // 无会话时自动创建
  if (!state.currentId) await newSession()

  lastUserText = message
  state.error = null
  state.streaming = true
  state.generating = true
  currentMessages.value.push({ id: nextId(), type: 'text', role: 'user', content: { text: message }, ts: Date.now() })

  controller = new AbortController()
  await streamChat({
    sessionId: state.currentId,
    userId: getUserId(),
    message,
    signal: controller.signal,
    onDelta: (delta) => { state.generating = false; handleDelta(delta) },
    onCard: (payload) => { state.generating = false; handleCard(payload) },
    onDone: (payload) => {
      const traceId = payload?.traceId
      if (pendingTextMsg && traceId) {
        pendingTextMsg.traceId = traceId
      }
      if (traceId) {
        const msgs = currentMessages.value
        for (let i = msgs.length - 1; i >= 0; i--) {
          if (msgs[i].role === 'assistant') {
            msgs[i].traceId = traceId
            if (msgs[i].content) msgs[i].content.traceId = traceId
            break
          }
        }
      }
      finalizeText()
      state.streaming = false
      state.generating = false
      refreshState()
      refreshSessionTitles()
    },
    onError: (msg) => {
      finalizeText()
      state.streaming = false
      state.generating = false
      state.error = { message: msg }
    },
  })
  controller = null
}

function stop() {
  controller?.abort()
  controller = null
  finalizeText()
  state.streaming = false
  state.generating = false
}

// 简单版重试：移除上一轮失败的助手输出后整轮重发
function retry() {
  const msgs = currentMessages.value
  if (!msgs.length) return
  const lastUserIdx = msgs.map((m) => m.role).lastIndexOf('user')
  if (lastUserIdx < 0) return
  msgs.splice(lastUserIdx + 1) // 丢弃失败的助手消息
  state.error = null
  send(lastUserText)
}

// ---------- 答题批改 ----------
const submittingIds = reactive({}) // exerciseId → true（提交中）

async function doSubmit(exerciseId, answers) {
  if (state.submitted[exerciseId] || submittingIds[exerciseId]) return
  submittingIds[exerciseId] = true
  try {
    const { grade, duplicated } = await submitExercise(state.currentId, exerciseId, answers)
    state.submitted[exerciseId] = true
    if (!duplicated) {
      currentMessages.value.push({ id: nextId(), type: 'grade', role: 'assistant', content: grade, ts: Date.now() })
    }
    refreshState()
    return grade
  } finally {
    submittingIds[exerciseId] = false
  }
}

// ---------- 课程订单（旁路交易域，不影响教学状态机） ----------
const payingIds = reactive({}) // orderId → true（支付中）

async function doPay(orderId) {
  if (payingIds[orderId]) return
  payingIds[orderId] = true
  try {
    const { order, duplicated } = await payOrder(orderId)
    // 原地翻转订单卡片状态（消息数组是响应式代理，赋值会触发视图更新）
    const msg = currentMessages.value.find((m) => m.content?.orderId === orderId)
    if (msg) msg.content.status = order.status
    if (!duplicated) {
      currentMessages.value.push({
        id: nextId(), type: 'system', role: 'assistant',
        content: { text: `课程「${order.courseName}」购买成功` }, ts: Date.now(),
      })
    }
    return order
  } finally {
    payingIds[orderId] = false
  }
}

export function useChat() {
  return {
    state,
    currentSession,
    currentMessages,
    resetAndReload,
    loadSessions,
    openSession,
    newSession,
    removeSession,
    refreshState,
    send,
    stop,
    retry,
    doSubmit,
    submittingIds,
    doPay,
    payingIds,
  }
}
