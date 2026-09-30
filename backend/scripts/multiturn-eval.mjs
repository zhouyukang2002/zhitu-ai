// 多轮会话剧本评测：执行 eval-data/multiturn-scenarios-20.json 的 20 个剧本。
// 断言维度：intent/slots（意图日志）、card（SSE 事件）、contains/notContains/hasText（聚合文本）。
// 特殊指令：__newSession=开新会话；__repeat={m,n}=模板重复发送。
// 用法: node scripts/multiturn-eval.mjs
import fs from 'node:fs'

const BASE = 'http://localhost:8080'
const TAG = 'mt_' + Date.now().toString(36)
const SCENARIOS = JSON.parse(fs.readFileSync(new URL('./eval-data/multiturn-scenarios-20.json', import.meta.url)))
const BASE_UID = 5000

async function streamRun(sessionId, userId, message) {
  const t0 = Date.now()
  const resp = await fetch(`${BASE}/api/chat/stream`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, userId, message }),
  })
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buf = '', text = '', cards = []
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    const frames = buf.split('\n\n'); buf = frames.pop()
    for (const frame of frames) {
      const event = /^event: (.+)$/m.exec(frame)?.[1]
      const dataLine = /^data: (.+)$/m.exec(frame)?.[1]
      if (!dataLine) continue
      try {
        const data = JSON.parse(dataLine)
        if (event === 'card') { cards.push(data.type); text += JSON.stringify(data) }
        if (event === 'message') text += (data.text ?? data.delta ?? '')
        if (event === 'exam') text += JSON.stringify(data)
      } catch { /* skip */ }
    }
  }
  return { text, cards, total: Date.now() - t0 }
}

function readIntentsFor(sessions) {
  const out = {}
  if (!fs.existsSync('logs')) return out
  for (const f of fs.readdirSync('logs').filter(f => f.startsWith('intent-'))) {
    for (const line of fs.readFileSync(`logs/${f}`, 'utf-8').split('\n')) {
      if (!line.trim()) continue
      try {
        const r = JSON.parse(line)
        if (sessions.has(r.sessionId)) out[r.sessionId] = r
      } catch { /* skip */ }
    }
  }
  return out
}

const allSessions = new Set()
const plans = [] // [{sc, idx, sessionId, userId, message, expect}]
let userId = BASE_UID
for (const sc of SCENARIOS) {
  let sessionSeq = 0
  let sessionId = `${TAG}_${sc.id}_${sessionSeq}`
  let turnIdx = 0
  for (const turn of sc.turns) {
    if (turn.__newSession) { sessionSeq++; sessionId = `${TAG}_${sc.id}_${sessionSeq}`; continue }
    const messages = []
    if (turn.__repeat) {
      for (let i = 1; i <= turn.__repeat.n; i++) messages.push(turn.__repeat.m.replaceAll('{n}', String(i)))
    } else messages.push(turn.m)
    for (const m of messages) {
      plans.push({ sc: sc.name, idx: turnIdx, sessionId, userId, message: m, expect: turn.__repeat ? {} : (turn.expect ?? {}) })
      allSessions.add(sessionId)
      turnIdx++
    }
  }
  userId++
}

let pass = 0, fail = 0
const failures = []
const keyOf = new Map(plans.map(p => [p.sessionId + '|' + p.message, p]))
// 同 session 同 message 可能重复——按出现顺序消费
const consumed = new Map()

for (const p of plans) {
  const key = p.sessionId + '|' + p.message
  const nth = consumed.get(key) ?? 0
  consumed.set(key, nth + 1)
  // 同 session 同 message 可能重复发送，按出现顺序取第 nth 条路由日志
  let r
  try {
    r = await streamRun(p.sessionId, p.userId, p.message)
  } catch (e) {
    fail++; failures.push({ sc: p.sc, m: p.message, why: '异常:' + String(e).slice(0, 60) }); continue
  }
  // 请求完成【后】实时读意图日志（此前读拿不到本轮记录）
  const logged = readIntentsFor(allSessions)
  const log = Object.values(logged).filter(r => r.sessionId === p.sessionId && r.message === p.message)[nth]
    ?? Object.values(logged).filter(r => r.sessionId === p.sessionId && r.message === p.message).pop()
  const checks = []
  if (p.expect.intent !== undefined) {
    const got = log?.intent ?? '(无日志)'
    if (got !== p.expect.intent) checks.push(`intent=${got}≠${p.expect.intent}`)
  }
  for (const slot of ['kp', 'questionType', 'timeRange']) {
    if (p.expect[slot] !== undefined) {
      const field = { kp: 'knowledgePoint' }[slot] ?? slot
      const got = log?.slots?.[field] ?? null
      if (String(got ?? '') !== String(p.expect[slot] ?? '')) checks.push(`${field}=${got}≠${p.expect[slot]}`)
    }
  }
  if (p.expect.questionCount !== undefined) {
    const got = log?.slots?.questionCount
    if (String(got ?? '') !== String(p.expect.questionCount)) checks.push(`questionCount=${got}≠${p.expect.questionCount}`)
  }
  if (p.expect.kp === null && log?.slots?.knowledgePoint) checks.push(`kp应清空但=${log.slots.knowledgePoint}`)
  if (p.expect.card !== undefined && !r.cards.includes(p.expect.card)) checks.push(`无卡片${p.expect.card} got=${r.cards}`)
  if (p.expect.contains !== undefined && !r.text.includes(p.expect.contains)) checks.push(`不含"${p.expect.contains}"`)
  if (p.expect.notContains !== undefined && r.text.includes(p.expect.notContains)) checks.push(`泄露"${p.expect.notContains}"`)
  if (p.expect.hasText && r.text.trim().length < 5) checks.push('空回复')

  if (checks.length === 0) { pass++; console.log(`  ✅ [${p.sc}] ${p.message.slice(0, 26)}`) }
  else { fail++; failures.push({ sc: p.sc, m: p.message, why: checks.join('; ') }); console.log(`  ❌ [${p.sc}] ${p.message.slice(0, 26)} → ${checks.join('; ')}`) }
}

console.log(`\n===== 多轮剧本评测: ${pass}/${plans.length} 轮通过, ${fail} 失败（${SCENARIOS.length} 个剧本） =====`)
if (failures.length) {
  console.log('\n失败清单:')
  failures.forEach(f => console.log(`  [${f.sc}] ${f.m?.slice(0, 26)} — ${f.why}`))
}
process.exit(fail > 0 ? 1 : 0)
