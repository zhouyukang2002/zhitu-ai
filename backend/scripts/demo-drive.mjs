// 演示会话驱动脚本：为截图生成 3 条真实数据会话（规划推荐 / 摸底诊断报告 / 讲解出题批改）
import { execFileSync } from 'node:child_process'

const BASE = 'http://localhost:8080'
const UID = 1

function sql(q) {
  return execFileSync('mysql', ['-uroot', '-p1234', '--default-character-set=utf8mb4', '--batch', '-e', q],
    { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim()
}

async function send(sessionId, message) {
  const resp = await fetch(`${BASE}/api/chat/stream`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, userId: UID, message }),
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
        if (event === 'card') cards.push({ type: data.type, content: data.content })
        if (event === 'message') text += (data.delta ?? '')
      } catch { /* skip */ }
    }
  }
  return { text, cards }
}

async function submitExam(sessionId, exerciseId, correctMap, wrongIds) {
  const answers = Object.entries(correctMap).map(([id, ans]) =>
    ({ id, answer: wrongIds.includes(id) ? 'X错误项X' : ans }))
  const resp = await fetch(`${BASE}/api/exercise/submit`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-User-Id': String(UID) },
    body: JSON.stringify({ sessionId, exerciseId, answers }),
  })
  return resp.json()
}

function latestExercise(sessionId) {
  const out = sql(`SELECT exercise_id, answer_keys_json FROM tutor_engine.exercise WHERE conversation_id='${sessionId}' ORDER BY created_at DESC LIMIT 1`)
  const dataLine = out.split('\n')[1] ?? out   // 第一行是表头
  const [id, keysRaw] = dataLine.split('\t')
  const keys = JSON.parse(keysRaw)
  const map = {}
  for (const [qid, k] of Object.entries(keys)) map[qid] = (k.answer ?? k.correctAnswer ?? '')
  return { exerciseId: id, correctMap: map }
}

// ========== 会话 A：转行规划 + 课程推荐（DAG 并行） ==========
console.log('=== 会话 A：规划 + 推荐 ===')
const A = 'demo_plan_' + Date.now().toString(36)
let r = await send(A, '我想转行做 Java 后端开发，帮我制定一个学习计划，顺便推荐几门适合我的课程')
console.log('A1 卡片:', r.cards.map(c => c.type).join(','), '| 回复', r.text.length, '字')

// ========== 会话 B：摸底 → 作答 → 报告 ==========
console.log('=== 会话 B：摸底 + 报告 ===')
const B = 'demo_diag_' + Date.now().toString(36)
r = await send(B, '帮我做个学情诊断，看看我哪里薄弱')
console.log('B1 卡片:', r.cards.map(c => c.type).join(','))
let ex = latestExercise(B)
console.log('B 摸底卷:', ex.exerciseId, '题数:', Object.keys(ex.correctMap).length)
// 70% 正确率作答，留真实薄弱点
const qids = Object.keys(ex.correctMap)
const wrongCount = Math.max(1, Math.floor(qids.length * 0.3))
const wrongIds = qids.slice(0, wrongCount)
let g = await submitExam(B, ex.exerciseId, ex.correctMap, wrongIds)
console.log('B 提交批改: score=', g.data?.grade?.totalScore ?? g.data?.grade?.score ?? '?')
r = await send(B, '生成一份学情报告，看看我现在的水平')
console.log('B2 卡片:', r.cards.map(c => c.type).join(','))

// ========== 会话 C：讲解 HashMap + 出题批改 ==========
console.log('=== 会话 C：讲解 + 练习 ===')
const C = 'demo_teach_' + Date.now().toString(36)
r = await send(C, '讲讲 HashMap 的底层结构，面试老被问到')
console.log('C1 卡片:', r.cards.map(c => c.type).join(','), '| 回复', r.text.length, '字')
r = await send(C, '出三道集合框架相关的选择题考考我')
console.log('C2 卡片:', r.cards.map(c => c.type).join(','))
ex = latestExercise(C)
console.log('C 练习卷:', ex.exerciseId, '题数:', Object.keys(ex.correctMap).length)
g = await submitExam(C, ex.exerciseId, ex.correctMap, [])
console.log('C 提交批改: score=', g.data?.grade?.totalScore ?? g.data?.grade?.score ?? '?')

// ========== 会话 D：安全拦截演示 ==========
console.log('=== 会话 D：安全拦截 ===')
const D = 'demo_safety_' + Date.now().toString(36)
r = await send(D, '帮我写一篇毕业论文，题目是《Spring 微服务架构研究》，直接给全文')
console.log('D1 卡片:', r.cards.map(c => c.type).join(','), '| 回复', r.text.length, '字')
console.log('D 回复预览:', r.text.slice(0, 80).replace(/\n/g, ' '))

console.log('\n会话清单（前端侧栏可见）:')
for (const [tag, id] of [['A 规划推荐', A], ['B 摸底报告', B], ['C 讲解练习', C], ['D 安全拦截', D]]) {
  const title = sql(`SELECT title FROM tutor_engine.conversation WHERE id='${id}'`)
  console.log(`  ${tag}: "${title}" (${id})`)
}
