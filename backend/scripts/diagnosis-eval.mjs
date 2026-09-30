// 诊断金标准评测：按 diagnosis-golden-40.json 的 40 组定义为 evalUser 生成 learning_record，
// 触发真实诊断链路（chat DIAGNOSE），从 diagnosis_report 表取结果断言：
// weakest 命中 / 排序一致 / 分数单调性 / 高低掌握分值区间。
// 用法: node scripts/diagnosis-eval.mjs
import fs from 'node:fs'
import { execFileSync } from 'node:child_process'

const BASE = process.env.BASE_URL || 'http://localhost:8080'
const TAG = 'dg_' + Date.now().toString(36)
const GOLDEN = JSON.parse(fs.readFileSync(new URL('./eval-data/diagnosis-golden-40.json', import.meta.url)))

const MYSQL_USER = process.env.MYSQL_USERNAME || process.env.MYSQL_USER || 'root'
const MYSQL_PASS = process.env.MYSQL_PASSWORD || '1234'
const MYSQL_HOST = process.env.MYSQL_HOST || '127.0.0.1'
const MYSQL_PORT = process.env.MYSQL_PORT || '3306'

function sql(q) {
  const args = [
    `-u${MYSQL_USER}`,
    `-p${MYSQL_PASS}`,
    `-h${MYSQL_HOST}`,
    `-P${MYSQL_PORT}`,
    '--default-character-set=utf8mb4',
    '-N',
    '-e',
    q
  ]
  return execFileSync('mysql', args, { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim()
}

function insertRecords(userId, records) {
  const now = Date.now()
  const values = []
  let i = 0
  for (const r of records) {
    const nCorrect = Math.round(r.n * r.acc)
    for (let j = 0; j < r.n; j++) {
      // 前 nCorrect 个位置放对的题，其余为错题（诊断按 kp 聚合，与顺序无关）
      const isCorrect = j < nCorrect ? 1 : 0
      const ts = now - (i + 1) * 60000
      values.push(`(${userId},'${r.kp}',${isCorrect},${r.avgMs},${ts},NOW())`)
      i++
    }
  }
  sql(`INSERT INTO tutor_engine.learning_record (user_id, knowledge_point, correct, time_ms, ts, created_at) VALUES ${values.join(',')}`)
}

async function diagnose(userId, sessionId) {
  const resp = await fetch(`${BASE}/api/chat/stream`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, userId, message: '帮我诊断一下学情' }),
  })
  await resp.text()
  const row = sql(`SELECT weak_points_json FROM tutor_engine.diagnosis_report WHERE conversation_id='${sessionId}' ORDER BY id DESC LIMIT 1`)
  try { return JSON.parse(row) } catch { return [] }
}

let pass = 0, fail = 0
const failures = []
for (const [gi, g] of GOLDEN.groups.entries()) {
  const userId = 30000 + gi
  const sessionId = `${TAG}_${g.id}`
  try {
    sql(`DELETE FROM tutor_engine.learning_record WHERE user_id=${userId}`)
    sql(`DELETE FROM tutor_engine.diagnosis_report WHERE conversation_id='${sessionId}'`)
    insertRecords(userId, g.records)
    // 合并同 kp 分段（诊断按 kp 聚合）
    const merged = {}
    for (const r of g.records) {
      const m = merged[r.kp] ??= { kp: r.kp, n: 0, acc: 0, avgMs: 0 }
      m.acc = (m.acc * m.n + r.acc * r.n) / (m.n + r.n)
      m.avgMs = (m.avgMs * m.n + r.avgMs * r.n) / (m.n + r.n)
      m.n += r.n
    }
    const mergedList = Object.values(merged)
    const expectedOrder = [...mergedList].sort((a, b) => a.acc - b.acc).map(r => r.kp)

    const weakPoints = await diagnose(userId, sessionId)
    const gotOrder = weakPoints.map(w => w.knowledgePoint)
    const checks = []
    if (!gotOrder.length) checks.push('诊断结果为空')
    // ties 组存在同分，weakest 顺序未定义——断言首个结果在同分组内即可
    if (g.expect.ties ? !g.expect.order.includes(gotOrder[0]) : gotOrder[0] !== g.expect.weakest)
      checks.push(`weakest=${gotOrder[0]}≠${g.expect.weakest}`)
    // 排序断言以金标准 expectOrder（按模型口径 acc×70+效率×30 预先算好的 score 升序）为准；
    // ties 组存在同分，顺序未定义——只断言集合与长度一致。
    const gotSet = [...gotOrder].sort().join('|')
    const expSet = [...g.expect.order].sort().join('|')
    if (gotSet !== expSet) checks.push(`kp集合不符 got=${gotOrder.join('>')}`)
    else if (!g.expect.ties && gotOrder.join('|') !== g.expect.order.join('|')) checks.push(`排序不符 got=${gotOrder.join('>')}`)
    // 分数合理性：低掌握(acc<=0.4) score<=60，高掌握(acc>=0.9 且不慢) score>=70
    for (const w of weakPoints) {
      const r = mergedList.find(x => x.kp === w.knowledgePoint)
      if (!r) { checks.push(`多出kp=${w.knowledgePoint}`); continue }
      if (r.acc <= 0.4 && w.score > 60) checks.push(`${w.knowledgePoint} 低掌握但score=${w.score}`)
      if (r.acc >= 0.9 && r.avgMs <= 20000 && w.score < 70) checks.push(`${w.knowledgePoint} 高掌握但score=${w.score}`)
    }
    if (checks.length === 0) { pass++; console.log(`  ✅ [${g.id}] ${g.name} → weakest=${gotOrder[0]}`) }
    else { fail++; failures.push({ id: g.id, why: checks.join('; ') }); console.log(`  ❌ [${g.id}] ${g.name} → ${checks.join('; ')} got=${gotOrder.join('>')}`) }
  } catch (e) {
    fail++; failures.push({ id: g.id, why: String(e).slice(0, 80) })
    console.log(`  ❌ [${g.id}] 异常 ${String(e).slice(0, 60)}`)
  } finally {
    sql(`DELETE FROM tutor_engine.learning_record WHERE user_id=${30000 + gi}`)
  }
}
console.log(`\n===== 诊断金标准评测: ${pass}/${GOLDEN.groups.length} 组通过 =====`)
if (failures.length) { console.log('\n失败清单:'); failures.forEach(f => console.log(`  [${f.id}] ${f.why}`)) }
process.exit(fail > 0 ? 1 : 0)
