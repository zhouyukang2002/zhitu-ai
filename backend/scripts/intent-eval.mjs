// 意图识别评测：优先读外置评测集 scripts/eval-data/intent-cases-200.json（200 题），
// 不存在时退回内置 54 题。输出：通过率 + 混淆矩阵 + 每类 P/R/F1 + TTFB 百分位 + 路由来源统计。
import fs from 'node:fs'

const BASE = process.env.BASE_URL || 'http://localhost:8080'
const TAG = 's_eval_' + Date.now().toString(36)

const CASES_FILE = new URL('./eval-data/intent-cases-200.json', import.meta.url)
const CASES = fs.existsSync(CASES_FILE)
  ? JSON.parse(fs.readFileSync(CASES_FILE, 'utf-8')).map(c => [c.m, c.i, c.s])
  : [ /* fallback：内置 54 题精简版 */
    ['帮我诊断一下学情', 'DIAGNOSE'], ['我想转行做 Java 开发，帮我规划一下', 'PLAN'],
    ['什么是 Java 泛型', 'TEACH', { knowledgePoint: '泛型' }], ['再出一组练习题', 'CLARIFY_NEEDED'],
    ['看看我的学情报告', 'REPORT'], ['我要买课', 'COURSE_BUY'],
    ['我想转行做数据分析师，推荐几门课程', 'COURSE_RECOMMEND'], ['你知道苏轼吗', 'CHITCHAT'],
  ]

async function streamRun(sessionId, message) {
  const t0 = Date.now()
  const resp = await fetch(`${BASE}/api/chat/stream`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, userId: 1, message }),
  })
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buf = '', ttfb = null, cards = [], hasError = false, hasDone = false, deltas = 0
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    if (ttfb === null) ttfb = Date.now() - t0
    buf += decoder.decode(value, { stream: true })
    const frames = buf.split('\n\n'); buf = frames.pop()
    for (const frame of frames) {
      const event = /^event: (.+)$/m.exec(frame)?.[1]
      const dataLine = /^data: (.+)$/m.exec(frame)?.[1]
      if (!dataLine) continue
      try {
        const data = JSON.parse(dataLine)
        if (event === 'card') cards.push(data.type)
        if (event === 'message') deltas++
        if (event === 'error') hasError = true
        if (event === 'done') hasDone = true
      } catch { /* skip */ }
    }
  }
  return { ttfb, total: Date.now() - t0, cards, hasError, hasDone, deltas }
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

let pass = 0, fail = 0
const failures = []
// 混淆矩阵计数：confusion[期望][预测] = 次数
const confusion = {}
const ttfbList = []
const routeStats = {} // source -> {total, pass}
const sessions = new Set(CASES.map((_, i) => `${TAG}_${i}`))

for (let i = 0; i < CASES.length; i++) {
  const [message, expectIntent, expectSlots] = CASES[i]
  const sessionId = `${TAG}_${i}`
  try {
    const r = await streamRun(sessionId, message)
    const logged = readIntentsFor(new Set([sessionId]))[sessionId]
    const intent = logged?.intent ?? '(无日志)'
    ttfbList.push(r.ttfb)
    const src = logged?.source ?? '(无日志)'
    routeStats[src] ??= { total: 0, pass: 0 }
    routeStats[src].total++
    routeStats[src].pass += intent === expectIntent ? 1 : 0
    // 记录混淆矩阵（预测缺失归入 '(无日志)'）
    confusion[expectIntent] ??= {}
    confusion[expectIntent][intent] = (confusion[expectIntent][intent] ?? 0) + 1

    const okIntent = intent === expectIntent
    const okResp = r.hasDone && !r.hasError && (r.cards.length > 0 || r.deltas > 0)
    const okTtfb = r.ttfb < 10_000
    let okSlots = true
    if (expectSlots && logged?.slots) {
      for (const [k, v] of Object.entries(expectSlots)) {
        if (String(logged.slots[k] ?? '') !== String(v)) okSlots = false
      }
    }
    const ok = okIntent && okResp && okTtfb && okSlots
    if (ok) { pass++ ; console.log(`  ✅ [${i}] ${message} → ${intent} (${r.ttfb}ms)`) }
    else {
      fail++
      const why = [!okIntent && `intent=${intent}≠${expectIntent}`, !okSlots && `slots不匹配`, !okResp && '响应异常', !okTtfb && `TTFB=${r.ttfb}ms`].filter(Boolean).join(' ')
      failures.push({ i, message, expectIntent, intent, why })
      console.log(`  ❌ [${i}] ${message} → ${why}`)
    }
  } catch (e) {
    fail++
    confusion[expectIntent] ??= {}
    confusion[expectIntent]['(异常)'] = (confusion[expectIntent]['(异常)'] ?? 0) + 1
    failures.push({ i, message, why: String(e).slice(0, 80) })
    console.log(`  ❌ [${i}] ${message} → 异常 ${String(e).slice(0, 60)}`)
  }
}

console.log(`\n===== 意图评测: ${pass}/${CASES.length} 通过, ${fail} 失败 =====`)

// ---------- TTFB 百分位 ----------
function pct(arr, p) {
  const s = [...arr].sort((a, b) => a - b)
  return s[Math.min(s.length - 1, Math.floor(s.length * p))] ?? 0
}
if (ttfbList.length) {
  console.log(`\n----- TTFB 分布（${ttfbList.length} 例） -----`)
  console.log(`P50=${pct(ttfbList, 0.5)}ms  P90=${pct(ttfbList, 0.9)}ms  P95=${pct(ttfbList, 0.95)}ms  max=${Math.max(...ttfbList)}ms  avg=${Math.round(ttfbList.reduce((a, b) => a + b, 0) / ttfbList.length)}ms`)
}

// ---------- 路由来源统计（L0 快路径命中率 / 各路准确率） ----------
console.log('\n----- 路由来源统计 -----')
for (const [src, st] of Object.entries(routeStats).sort((a, b) => b[1].total - a[1].total)) {
  console.log(`${src.padEnd(24)} total=${String(st.total).padStart(4)}  准确率=${(st.pass / st.total * 100).toFixed(1)}%`)
}

// ---------- 混淆矩阵（行=期望，列=预测，对角线为正确） ----------
const expectLabels = Object.keys(confusion)
const predictedSet = new Set()
expectLabels.forEach(r => Object.keys(confusion[r]).forEach(c => predictedSet.add(c)))
// 列顺序：期望标签优先，再补出现过的其他预测
const predLabels = [...expectLabels.filter(l => predictedSet.has(l)),
  ...[...predictedSet].filter(l => !expectLabels.includes(l))]

console.log('\n----- 混淆矩阵（行=期望，列=预测，对角线为正确） -----')
const labelW = 18, cellW = 9
const header = '期望\\预测'.padEnd(labelW) + predLabels.map(l => l.slice(0, 8).padStart(cellW)).join('')
console.log(header)
for (const row of expectLabels) {
  const total = Object.values(confusion[row]).reduce((a, b) => a + b, 0)
  let line = `${row}(${total})`.padEnd(labelW)
  for (const col of predLabels) {
    const n = confusion[row][col] ?? 0
    const mark = n > 0 && row === col ? String(n) : (n > 0 ? `${n}✗` : '·')
    line += mark.padStart(cellW)
  }
  console.log(line)
}

// ---------- 每类 Precision / Recall / F1 ----------
console.log('\n----- 每类 Precision / Recall / F1 -----')
console.log('意图'.padEnd(labelW) + 'TP'.padStart(5) + 'FP'.padStart(5) + 'FN'.padStart(5) +
  'P'.padStart(8) + 'R'.padStart(8) + 'F1'.padStart(8))
let macroF1 = 0, macroCount = 0
for (const label of expectLabels) {
  let tp = 0, fp = 0, fn = 0
  for (const r of expectLabels) {
    for (const [c, n] of Object.entries(confusion[r])) {
      if (r === label && c === label) tp += n
      else if (r !== label && c === label) fp += n
      else if (r === label && c !== label) fn += n
    }
  }
  const p = tp + fp > 0 ? tp / (tp + fp) : 1
  const rec = tp + fn > 0 ? tp / (tp + fn) : 1
  const f1 = p + rec > 0 ? 2 * p * rec / (p + rec) : 0
  macroF1 += f1; macroCount++
  console.log(label.padEnd(labelW) + String(tp).padStart(5) + String(fp).padStart(5) + String(fn).padStart(5) +
    `${(p * 100).toFixed(1)}%`.padStart(8) + `${(rec * 100).toFixed(1)}%`.padStart(8) + `${(f1 * 100).toFixed(1)}%`.padStart(8))
}
console.log(`\n宏平均 F1（Macro-F1）: ${(macroF1 / macroCount * 100).toFixed(1)}%`)

if (failures.length) {
  console.log('\n失败清单:')
  failures.forEach(f => console.log(`  [${f.i}] ${f.message} — ${f.why}`))
}
process.exit(fail > 0 ? 1 : 0)
