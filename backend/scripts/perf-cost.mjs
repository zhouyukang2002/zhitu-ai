// 性能与成本复测：TTFB 分场景（快路径/LLM路由/完整闭环/DAG并行）+ 并发压测（6/10/20）
// + 单轮成本统计（从 ai_trace 聚合本轮评测 session 的 token 与 cost）。
// 用法: node scripts/perf-cost.mjs
import { execFileSync } from 'node:child_process'

const BASE = 'http://localhost:8080'
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
    '--batch',
    '-e',
    q
  ]
  return execFileSync('mysql', args, { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim()
}
function parseTsv(tsv) {
  const lines = tsv.split('\n')
  const cols = lines[0].split('\t')
  return lines.slice(1).filter(l => l.trim()).map(l => {
    const cells = l.split('\t')
    return Object.fromEntries(cols.map((c, i) => [c, cells[i] ?? '']))
  })
}

async function streamRun(sessionId, message, userId = 4200) {
  const t0 = Date.now()
  const resp = await fetch(`${BASE}/api/chat/stream`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, userId, message }),
  })
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buf = '', ttfb = null, hasError = false
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    if (ttfb === null) ttfb = Date.now() - t0
    buf += decoder.decode(value, { stream: true })
    if (buf.includes('event: error')) hasError = true
  }
  return { ttfb, total: Date.now() - t0, hasError }
}

const pct = (arr, p) => [...arr].sort((a, b) => a - b)[Math.min(arr.length - 1, Math.floor(arr.length * p))] ?? 0

// ---------- 1) TTFB 分场景 ----------
const scenarios = [
  ['快路径', ['出几道 MySQL 索引的题', '看看我的学情报告', '再出一组练习题']],
  ['LLM路由', ['讲讲 JVM 内存结构', '我想转行做数据分析帮我规划下', '推荐几门 AI 方向的课']],
  ['完整闭环', ['帮我诊断一下学情，然后出几道题检验一下']],
]
const report = {}
for (const [name, msgs] of scenarios) {
  const ttfbs = [], totals = []
  for (let i = 0; i < msgs.length; i++) {
    for (let round = 0; round < 2; round++) {
      const r = await streamRun(`${TAG}_${name}_${i}_${round}`, msgs[i] + (round ? '，换一种说法' + i : ''))
      ttfbs.push(r.ttfb); totals.push(r.total)
    }
  }
  report[name] = { ttfbP50: pct(ttfbs, 0.5), ttfbP95: pct(ttfbs, 0.95), totalP50: pct(totals, 0.5), totalP95: pct(totals, 0.95) }
  console.log(`[${name}] TTFB P50=${report[name].ttfbP50}ms P95=${report[name].ttfbP95}ms | 总耗时 P50=${report[name].totalP50}ms P95=${report[name].totalP95}ms`)
}

// ---------- 2) 并发压测 ----------
for (const n of [6, 10, 20]) {
  const t0 = Date.now()
  const results = await Promise.all(Array.from({ length: n }, (_, i) =>
    streamRun(`${TAG}_load${n}_${i}`, i % 2 ? '出几道集合框架的题' : '讲讲分布式锁')))
  const okN = results.filter(r => !r.hasError).length
  const dur = Date.now() - t0
  console.log(`[并发${n}] 完成=${okN}/${n} 总时长=${dur}ms 单请求TTFB P50=${pct(results.map(r => r.ttfb), 0.5)}ms P95=${pct(results.map(r => r.ttfb), 0.95)}ms`)
}

// ---------- 3) 单轮成本（本轮压测 session 的 trace 聚合） ----------
const rows = parseTsv(sql(
  `SELECT COUNT(*) n, ROUND(SUM(prompt_tokens+completion_tokens)) tokens, ROUND(SUM(cost),4) cost, ROUND(AVG(latency_ms)) avg_latency ` +
  `FROM tutor_engine.ai_trace WHERE session_id LIKE '${TAG}%'`)).pop()
if (rows?.n > 0) {
  console.log(`\n[成本] 轮数=${rows.n} 总token=${rows.tokens} 总成本=${rows.cost}元 单轮成本=${(rows.cost / rows.n).toFixed(4)}元/轮 平均延迟=${rows.avg_latency}ms`)
} else {
  console.log('\n[成本] 未取到 trace 数据（异步落库可能延迟，可稍后重查）')
}
process.exit(0)
