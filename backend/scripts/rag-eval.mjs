// RAG 检索评测：基于 scripts/eval-data/rag-cases-100.json 计算 Recall@1/@3/@5 与 MRR，
// 并对比「RRF 原序（无重排）」与「Cross-Encoder 重排后」两路结果。
// 用法: node scripts/rag-eval.mjs            （默认 topK=5，同时测两路）
//       node scripts/rag-eval.mjs 5 false    （第二参 false=只测当前链路，不做 A/B）
// 前置：引擎已启动（:8080）且已 POST /api/admin/corpus/sync 摄入语料
import fs from 'node:fs'

const BASE = process.env.BASE_URL || 'http://localhost:8080'
const TOP_K = Number(process.argv[2] || 5)
const AB = process.argv[3] !== 'false'
const CASES = JSON.parse(fs.readFileSync(new URL('./eval-data/rag-cases-100.json', import.meta.url)))

async function search(query, topK, rerank) {
  const url = `${BASE}/api/admin/retrieval/search?query=${encodeURIComponent(query)}&topK=${topK}&rerank=${rerank}`
  const resp = await fetch(url)
  const json = await resp.json()
  return json.data ?? []
}

/** 命中排名（从 1 起），未命中返回 0；expectTitle 优先（标题包含），否则比对 kp */
function hitRank(chunks, c) {
  for (let i = 0; i < chunks.length; i++) {
    const ch = chunks[i]
    if (c.expectTitle) {
      if ((ch.title || '').includes(c.expectTitle) || ch.kp === c.expectTitle || ch.kp === c.expectKp) return i + 1
    } else if (ch.kp === c.expectKp) {
      return i + 1
    }
  }
  return 0
}

function evalRun(ranks) {
  const n = ranks.length
  const recallAt = k => ranks.filter(r => r > 0 && r <= k).length / n
  const mrr = ranks.reduce((s, r) => s + (r > 0 ? 1 / r : 0), 0) / n
  return {
    recallAt1: recallAt(1),
    recallAt3: recallAt(3),
    recallAt5: recallAt(5),
    mrr,
  }
}

function fmt(x) { return `${(x * 100).toFixed(1)}%` }

async function runOne(rerank) {
  const ranks = []
  const misses = []
  for (let i = 0; i < CASES.length; i++) {
    const c = CASES[i]
    try {
      const chunks = await search(c.q, TOP_K, rerank)
      const rank = hitRank(chunks, c)
      ranks.push(rank)
      if (rank === 0) {
        misses.push({ i, query: c.q, expect: c.expectTitle || c.expectKp, got: chunks.map(x => x.kp).slice(0, 3) })
        console.log(`  ❌ [${i}] "${c.q}" 未命中 ${c.expectTitle || c.expectKp}；实际 top3: ${chunks.map(x => x.kp).join('/')}`)
      } else {
        console.log(`  ✅ [${i}] "${c.q}" 命中@${rank}`)
      }
    } catch (e) {
      ranks.push(0)
      misses.push({ i, query: c.q, error: String(e).slice(0, 60) })
      console.log(`  ❌ [${i}] "${c.q}" 异常 ${String(e).slice(0, 60)}`)
    }
  }
  return { metrics: evalRun(ranks), misses }
}

console.log(`===== RAG 检索评测（${CASES.length} 题，topK=${TOP_K}）=====\n`)

console.log('--- 链路 A：RRF 宽召回 + Cross-Encoder 重排 ---')
const a = await runOne(true)
console.log(`  Recall@1=${fmt(a.metrics.recallAt1)}  Recall@3=${fmt(a.metrics.recallAt3)}  Recall@5=${fmt(a.metrics.recallAt5)}  MRR=${fmt(a.metrics.mrr)}`)

let b = null
if (AB) {
  console.log('\n--- 链路 B：仅 RRF 原序（关闭重排，A/B 对照） ---')
  b = await runOne(false)
  console.log(`  Recall@1=${fmt(b.metrics.recallAt1)}  Recall@3=${fmt(b.metrics.recallAt3)}  Recall@5=${fmt(b.metrics.recallAt5)}  MRR=${fmt(b.metrics.mrr)}`)

  console.log('\n===== 重排增益（A - B） =====')
  const diff = (x, y) => `${x >= y ? '+' : ''}${((x - y) * 100).toFixed(1)}pt`
  console.log(`  Recall@1: ${diff(a.metrics.recallAt1, b.metrics.recallAt1)}   Recall@3: ${diff(a.metrics.recallAt3, b.metrics.recallAt3)}   Recall@5: ${diff(a.metrics.recallAt5, b.metrics.recallAt5)}   MRR: ${diff(a.metrics.mrr, b.metrics.mrr)}`)
}

if (a.misses.length) {
  console.log(`\n未命中清单（${a.misses.length} 条，用于针对性补语料/调分词/调权重）:`)
  a.misses.forEach(m => console.log(`  [${m.i}] ${m.query} — 期望 ${m.expect}${m.got ? `，实际 ${m.got.join('/')}` : ''}`))
}
process.exit(a.metrics.recallAt5 < 0.6 ? 1 : 0)
