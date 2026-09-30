// ============================================================
// 零依赖 Mock 服务：SSE 对话 + 会话/状态/批改 REST
// 与前端契约对齐《智能助教引擎前端开发文档》第 4/5/6 节
//   - POST /api/chat/stream        SSE: message/card/done/error
//   - GET  /api/session/list
//   - POST /api/session
//   - GET  /api/session/:id/messages
//   - GET  /api/session/:id/state
//   - POST /api/exercise/submit    （幂等：同 exerciseId 返回同一结果）
//   - DELETE /api/session/:id
// ============================================================
import http from 'node:http'
import {
  courses, traceCard, diagnosisCard, planCard, teachingText,
  exerciseCard, answerKey, reportCard, seedSessions,
} from './data.mjs'

const PORT = 3001

// ---------- 内存状态 ----------
const sessions = new Map()
for (const s of seedSessions()) sessions.set(s.id, s)
const orders = new Map() // orderId → { order, sessionId }
let seq = Date.now()

function newSession(title = '新的对话') {
  const id = `s_${++seq}`
  const s = { id, title, updatedAt: Date.now(), state: null, messages: [], grades: {}, exercises: {} }
  sessions.set(id, s)
  return s
}
const getSession = (id) => sessions.get(id) || null
const touch = (s) => { s.updatedAt = Date.now() }

function pushMsg(s, type, role, content) {
  if (content && content.state) s.state = content.state // system 卡片可推进状态机
  const msg = { id: `m_${++seq}`, type, role, content, ts: Date.now() }
  s.messages.push(msg)
  touch(s)
  return msg
}

/** 出题：登记到会话（落库由 emit 统一负责，避免重复持久化） */
function registerExercise(s) {
  s.exercises ??= {}
  const card = exerciseCard(`e_${++seq}`)
  s.exercises[card.content.exerciseId] = card.content
  return card
}

/** 根据已推送内容推导状态机进度（纯交易/闲聊会话无教学信号，返回 null 显示占位符） */
function deriveState(s) {
  if (s.state) return s.state
  const types = s.messages.map((m) => m.type)
  if (types.includes('grade') || types.includes('report')) return 'EVALUATED'
  if (types.includes('exercise')) return 'PRACTICING'
  if (types.includes('plan')) return 'PLANNED'
  if (types.includes('diagnosis')) return 'DIAGNOSED'
  return null
}

// ---------- Mock 批改引擎：客观题规则判分 + 主观题"LLM"关键词判分 ----------
function grade(exerciseId, exercise, answers) {
  const perQuestion = []
  let score = 0
  for (const q of exercise.questions) {
    const given = String((answers.find((a) => a.id === q.id) || {}).answer ?? '')
    const key = answerKey[q.id]
    if (q.type === 'choice') {
      const correct = given.trim().charAt(0).toUpperCase() === key.answer
      if (correct) score += key.score
      perQuestion.push({
        id: q.id, correct, answer: key.answer,
        feedback: correct ? '回答正确，判别式运用熟练。' : '判别式计算有误，先算 b²-4ac 再与 0 比较。',
        errorType: correct ? null : '概念混淆',
      })
    } else {
      // 命中评分关键词数量决定档位（模拟 LLM 按评分标准输出分数 + 错误归因）
      const hit = key.keywords.filter((k) => given.includes(k)).length
      const ratio = Math.min(1, hit / 4)
      const qScore = Math.round(key.score * ratio)
      const correct = ratio >= 0.75
      score += qScore
      perQuestion.push({
        id: q.id, correct, answer: key.reference,
        feedback: correct
          ? '理由完整，判别式计算正确，表述清晰。'
          : ratio > 0
            ? '提到了判别式，但缺少具体计算过程或结论不完整，建议对照参考答案补全步骤。'
            : '未给出有效理由，请先写出 Δ = b² - 4ac 的代入过程。',
        errorType: correct ? null : (ratio > 0 ? '计算失误' : '概念混淆'),
      })
    }
  }
  return { exerciseId, totalScore: 100, score, perQuestion, knowledgePoints: ['一元二次方程', '判别式与根的关系'] }
}

// ---------- SSE 场景脚本 ----------
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** emit = 流式下发 + 落库（刷新后历史完整） */
function makeEmit(write, s) {
  return (card) => {
    write('card', card)
    pushMsg(s, card.type, 'assistant', card.content)
  }
}

async function runFullLoop(write, aborted, s) {
  const emit = makeEmit(write, s)
  emit(traceCard({ diagnose: true, plan: true, teach: true, exercise: true }))
  if (aborted()) return
  await sleep(400)
  emit(diagnosisCard)
  if (aborted()) return
  await sleep(500)
  emit(planCard)
  if (aborted()) return
  await sleep(400)
  let streamed = ''
  try {
    for (let i = 0; i < teachingText.length; i += 7) {
      const chunk = teachingText.slice(i, i + 7)
      streamed += chunk
      write('message', { delta: chunk })
      await sleep(26)
      if (aborted()) return
    }
  } finally {
    // 中断也保留已生成的部分讲解
    if (streamed) pushMsg(s, 'text', 'assistant', { text: streamed })
  }
  await sleep(300)
  emit(registerExercise(s))
  write('done', {})
}

async function runExerciseOnly(write, aborted, s) {
  const emit = makeEmit(write, s)
  emit(traceCard({ exercise: true }))
  if (aborted()) return
  await sleep(350)
  emit({ type: 'system', content: { text: '已进入练习环节', state: 'PRACTICING' } })
  emit(registerExercise(s))
  write('done', {})
}

async function runReport(write, aborted, s) {
  const emit = makeEmit(write, s)
  emit(traceCard({ diagnose: true }))
  if (aborted()) return
  await sleep(400)
  emit(reportCard(s.lastGrade ?? 72))
  emit({ type: 'system', content: { text: '学情报告已生成', state: 'EVALUATED' } })
  write('done', {})
}

async function runChat(write, aborted, s, text) {
  const emit = makeEmit(write, s)
  const reply = `收到～你说的是："${text}"。\n\n当前是 **Mock 演示模式**，试试这些指令体验完整教学闭环：\n\n- **"帮我诊断一下学情"** → 诊断 + 规划 + 讲解 + 练习全流程\n- **"再出一组练习题"** → 只出题\n- **"看看我的学情报告"** → 生成学情报告\n- **"推荐几门课程"** → 课程推荐（旁路交易域）`
  emit(traceCard({}))
  if (aborted()) return
  await sleep(250)
  let streamed = ''
  try {
    for (let i = 0; i < reply.length; i += 7) {
      const chunk = reply.slice(i, i + 7)
      streamed += chunk
      write('message', { delta: chunk })
      await sleep(24)
      if (aborted()) return
    }
  } finally {
    if (streamed) pushMsg(s, 'text', 'assistant', { text: streamed })
  }
  write('done', {})
}

// ---------- 交易域（旁路星型：课程推荐 / 课程购买） ----------
async function runRecommend(write, aborted, s) {
  const emit = makeEmit(write, s)
  emit(traceCard({}))
  if (aborted()) return
  await sleep(250)
  const reply = `结合你的薄弱点诊断结果，我从课程库里挑了 3 门匹配度最高的课。**推荐理由都标在卡片上了**，点击「购买」可直接进入下单确认。`
  let streamed = ''
  try {
    for (let i = 0; i < reply.length; i += 7) {
      const chunk = reply.slice(i, i + 7)
      streamed += chunk
      write('message', { delta: chunk })
      await sleep(24)
      if (aborted()) return
    }
  } finally {
    if (streamed) pushMsg(s, 'text', 'assistant', { text: streamed })
  }
  emit({
    type: 'course_list',
    content: {
      source: 'recommend',
      courses: courses.slice(0, 3),
    },
  })
  write('done', {})
}

/** 课程购买：精确命中课程名 → 预下单；否则返回列表让用户确认（不擅自下单） */
async function runPurchase(write, aborted, s, text) {
  const emit = makeEmit(write, s)
  emit(traceCard({}))
  if (aborted()) return
  await sleep(300)

  // ① 精确匹配：《书名号》优先，其次课程名包含匹配
  const quoted = text.match(/《(.+?)》/)?.[1]
  const hit = quoted
    ? courses.find((c) => c.name === quoted || c.name.includes(quoted))
    : courses.find((c) => text.includes(c.name))

  if (hit) {
    // ② 预下单（幂等键 orderId；支付确认由用户手动操作）
    const order = {
      orderId: `o_${++seq}`,
      courseId: hit.courseId,
      courseName: hit.name,
      price: hit.price,
      status: 'CREATED',
    }
    orders.set(order.orderId, { order, sessionId: s.id })
    emit({
      type: 'course_order',
      content: order,
    })
    write('done', {})
    return
  }

  // ③ 未精确匹配：返回候选列表让用户确认（大模型不擅自选课下单）
  const reply = `没有精确匹配到你要买的那门课。下面是与「${text.replace(/我要买|下单|购买|买|订购/g, '').trim() || '数学'}」相关的课程，请**点选具体课程**后我再为你生成订单：`
  let streamed = ''
  try {
    for (let i = 0; i < reply.length; i += 7) {
      const chunk = reply.slice(i, i + 7)
      streamed += chunk
      write('message', { delta: chunk })
      await sleep(24)
      if (aborted()) return
    }
  } finally {
    if (streamed) pushMsg(s, 'text', 'assistant', { text: streamed })
  }
  emit({
    type: 'course_list',
    content: {
      source: 'search',
      courses: courses.slice(0, 3),
    },
  })
  write('done', {})
}

// ---------- HTTP 工具 ----------
function json(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify(obj))
}
function readBody(req) {
  return new Promise((resolve) => {
    let raw = ''
    req.on('data', (c) => (raw += c))
    req.on('end', () => { try { resolve(raw ? JSON.parse(raw) : {}) } catch { resolve({}) } })
  })
}

// ---------- 服务 ----------
const server = http.createServer(async (req, res) => {
  const { pathname: path } = new URL(req.url, `http://localhost:${PORT}`)
  const m = req.method

  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,DELETE,OPTIONS')
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type')
  if (m === 'OPTIONS') { res.writeHead(204); return res.end() }

  // ---- SSE 对话 ----
  if (m === 'POST' && path === '/api/chat/stream') {
    const body = await readBody(req)
    let s = getSession(body.sessionId)
    if (!s) s = newSession()
    const text = String(body.message || '').trim()

    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    })
    let closed = false
    // 注意：必须监听 res 的 close（连接断开）；req 的 close 在请求体读完就会触发，
    // 会把后续 SSE 写入全部误杀
    res.on('close', () => { closed = true })
    const write = (event, data) => { if (!closed) res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`) }

    try {
      if (!text) {
        write('error', { message: '消息不能为空' })
      } else {
        pushMsg(s, 'text', 'user', { text })
        if (s.title === '新的对话') s.title = text.slice(0, 12) + (text.length > 12 ? '…' : '')
        if (/报告/.test(text)) await runReport(write, () => closed, s)
        else if (/买|下单|订购|购买/.test(text)) await runPurchase(write, () => closed, s, text)
        else if (/推荐|配套课程|有什么课|报课|选课|课程/.test(text)) await runRecommend(write, () => closed, s)
        else if (/再出|练习|出题|题目/.test(text)) await runExerciseOnly(write, () => closed, s)
        else if (/诊断|薄弱|学情/.test(text)) await runFullLoop(write, () => closed, s)
        else await runChat(write, () => closed, s, text)
      }
    } catch (e) {
      write('error', { message: String((e && e.message) || e) })
    }
    if (!closed) write('done', {})
    return res.end()
  }

  // ---- 会话列表 ----
  if (m === 'GET' && path === '/api/session/list') {
    const list = [...sessions.values()]
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .map(({ id, title, updatedAt }) => ({ id, title, updatedAt }))
    return json(res, 200, { code: 0, message: 'ok', data: list })
  }

  // ---- 新建会话 ----
  if (m === 'POST' && path === '/api/session') {
    const s = newSession()
    return json(res, 200, { code: 0, message: 'ok', data: { id: s.id, title: s.title, updatedAt: s.updatedAt } })
  }

  // ---- 会话消息 ----
  const msgMatch = path.match(/^\/api\/session\/([^/]+)\/messages$/)
  if (m === 'GET' && msgMatch) {
    const s = getSession(msgMatch[1])
    if (!s) return json(res, 404, { code: 404, message: '会话不存在' })
    return json(res, 200, { code: 0, message: 'ok', data: s.messages })
  }

  // ---- 会话状态 ----
  const stateMatch = path.match(/^\/api\/session\/([^/]+)\/state$/)
  if (m === 'GET' && stateMatch) {
    const s = getSession(stateMatch[1])
    if (!s) return json(res, 404, { code: 404, message: '会话不存在' })
    return json(res, 200, {
      code: 0, message: 'ok',
      data: {
        state: deriveState(s),
        pipeline: ['DIAGNOSED', 'PLANNED', 'LEARNING', 'PRACTICING', 'EVALUATED'],
        weakPoints: [
          { knowledgePoint: '一元二次方程', score: 40 },
          { knowledgePoint: '判别式与根的关系', score: 52 },
          { knowledgePoint: '因式分解', score: 66 },
        ],
        pathProgress: 0.25,
        lastGrade: s.lastGrade ?? null,
      },
    })
  }

  // ---- 提交答案（幂等） ----
  if (m === 'POST' && path === '/api/exercise/submit') {
    const body = await readBody(req)
    const { sessionId, exerciseId, answers = [] } = body
    const s = getSession(sessionId)
    if (!s) return json(res, 404, { code: 404, message: '会话不存在' })
    // 幂等：同一 exerciseId 重复提交直接返回原结果
    if (s.grades[exerciseId]) {
      return json(res, 200, { code: 0, message: 'ok（幂等返回）', data: { grade: s.grades[exerciseId], duplicated: true } })
    }
    const exercise = s.exercises[exerciseId]
    if (!exercise) return json(res, 404, { code: 404, message: '练习不存在' })
    const g = grade(exerciseId, exercise, answers)
    s.grades[exerciseId] = g
    s.lastGrade = g.score
    pushMsg(s, 'grade', 'assistant', g)
    return json(res, 200, { code: 0, message: 'ok', data: { grade: g, duplicated: false } })
  }

  // ---- 课程支付确认（幂等：同 orderId 重复支付返回原结果，状态不重复翻转） ----
  if (m === 'POST' && path === '/api/order/pay') {
    const body = await readBody(req)
    const rec = orders.get(body.orderId)
    if (!rec) return json(res, 404, { code: 404, message: '订单不存在' })
    if (rec.order.status === 'PAID') {
      return json(res, 200, { code: 0, message: 'ok（幂等返回）', data: { order: rec.order, duplicated: true } })
    }
    rec.order.status = 'PAID'
    rec.order.paidAt = Date.now()
    // 同步订单卡片历史状态 + 落一条系统消息
    const s = getSession(rec.sessionId)
    if (s) {
      const msg = s.messages.find((x) => x.content?.orderId === rec.order.orderId)
      if (msg) msg.content.status = 'PAID'
      pushMsg(s, 'system', 'assistant', { text: `课程「${rec.order.courseName}」购买成功` })
    }
    return json(res, 200, { code: 0, message: 'ok', data: { order: rec.order, duplicated: false } })
  }

  // ---- 删除会话 ----
  const delMatch = path.match(/^\/api\/session\/([^/]+)$/)
  if (m === 'DELETE' && delMatch) {
    sessions.delete(delMatch[1])
    return json(res, 200, { code: 0, message: 'ok', data: null })
  }

  return json(res, 404, { code: 404, message: 'not found' })
})

server.listen(PORT, () => {
  console.log(`[mock] listening on http://localhost:${PORT}`)
})
