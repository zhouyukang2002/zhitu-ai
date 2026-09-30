// RAGAS 四件套评测：Context Precision / Context Recall / Faithfulness / Answer Relevancy。
// 流程（RAGAS 标准口径）：检索 top5 → LLM 基于检索上下文生成回答 → gold chunk 生成参考答案 →
// DeepSeek 逐项判分。抽样 30 条（默认步长 3），减少 Judge 成本。
// 检索指标（Recall@K/MRR/重排A/B）另见 rag-eval.mjs。
// 用法: node scripts/ragas-eval.mjs [sampleStep=3]
import fs from 'node:fs'

const BASE = 'http://localhost:8080'
const DS_KEY = process.env.DEEPSEEK_API_KEY ?? process.env.DASHSCOPE_API_KEY ?? ''
const STEP = Number(process.argv[2] || 3)
const ALL = JSON.parse(fs.readFileSync(new URL('./eval-data/rag-cases-100.json', import.meta.url)))
const CASES = ALL.filter((_, i) => i % STEP === 0)

async function llm(messages, maxTokens = 600) {
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const resp = await fetch('https://api.deepseek.com/chat/completions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${DS_KEY}` },
        body: JSON.stringify({ model: 'deepseek-chat', messages, max_tokens: maxTokens, temperature: 0 }),
      })
      if (resp.status === 429) { await new Promise(r => setTimeout(r, 2000)); continue }
      const j = await resp.json()
      const txt = j.choices?.[0]?.message?.content
      if (txt) return txt
    } catch { /* 重试 */ }
  }
  return ''
}
const jsonOf = txt => txt.match(/\{[\s\S]*\}/)?.[0] ?? txt.match(/\[[\s\S]*\]/)?.[0] ?? '{}'

const ES = 'http://localhost:9200'
// admin /retrieval/search 只返回元数据不含全文，RAGAS 需要正文 → 直接查 ES
//（复刻 KnowledgeBase.search 的 knn+match 双路 RRF，此处不做重排——四件套对同一组 chunks 判分，A/B 无关）
async function retrieve(q) {
  const embBody = JSON.stringify({ model: 'text-embedding-v3', input: [q], dimensions: 1024 })
  const embResp = await fetch('https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + (process.env.DASHSCOPE_API_KEY ?? '') },
    body: embBody,
  })
  const vector = (await embResp.json()).data[0].embedding
  const body = {
    knn: { field: 'embedding', query_vector: vector, k: 10, num_candidates: 50 },
    query: { match: { text: q } },
    rank: { rrf: { rank_constant: 60, rank_window_size: 20 } },
    size: 5, _source: ['kp', 'title', 'text'],
  }
  const resp = await fetch(`${ES}/enterprise_knowledge/_search`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  const json = await resp.json()
  return (json.hits?.hits ?? []).map(h => ({ kp: h._source?.kp, title: h._source?.title, text: (h._source?.text ?? '').slice(0, 600) }))
}

async function runCase(c) {
  const hits = await retrieve(c.q)
  const goldIdx = hits.findIndex(h =>
    (c.expectTitle && (h.title ?? '').includes(c.expectTitle)) || (c.expectKp && h.kp === c.expectKp))
  const context = hits.map((h, i) => `[片段${i + 1}] (kp=${h.kp}) ${h.title}\n${h.text}`).join('\n\n')
  if (!context) return { q: c.q, error: '检索为空' }

  // 1) 生成回答（基于检索上下文，RAGAS 口径）
  const answer = await llm([
    { role: 'system', content: '你是职教助教，仅基于给定检索片段回答问题，片段不足以回答时明确说明。' },
    { role: 'user', content: `检索片段:\n${context}\n\n问题: ${c.q}` },
  ], 500)

  // 2) 参考答案（gold chunk 命中时基于 gold 生成；未命中则基于 top1 生成作近似基准）
  const goldText = goldIdx >= 0 ? hits[goldIdx].text : hits[0]?.text ?? ''
  const reference = goldText ? await llm([
    { role: 'system', content: '基于给定片段写一段简明的参考答案（150字内）。' },
    { role: 'user', content: `片段: ${goldText}\n\n问题: ${c.q}` },
  ], 300) : ''

  // 顺序执行 4 项判分（并发会触发 DeepSeek 限流导致静默失败归 0）
  const judgeBase = [
    { role: 'system', content: '你是严格的RAG质量评估员，只输出JSON。' },
  ]
  const precision = await llm([...judgeBase, { role: 'user', content: `问题: ${c.q}\n检索到的片段列表(带编号):\n${context.slice(0, 3500)}\n\n判断每个片段对回答该问题是否相关有用。输出JSON: {"relevant":[相关片段编号数组]}` }], 200)
    .then(t => { try { const j = JSON.parse(jsonOf(t)); return (j.relevant ?? []).length / Math.max(1, hits.length) } catch { return 0 } })
  const recall = reference ? await llm([...judgeBase, { role: 'user', content: `参考答案: ${reference}\n\n检索片段:\n${context.slice(0, 3500)}\n\n参考答案中的信息要点被检索片段覆盖的比例是多少？输出JSON: {"recall": 0到1的小数}` }], 100)
    .then(t => { try { return Number(JSON.parse(jsonOf(t)).recall) || 0 } catch { return 0 } }) : 0
  const faith = await llm([...judgeBase, { role: 'user', content: `检索片段:\n${context.slice(0, 3500)}\n\n回答: ${answer}\n\n回答中的论断有多少比例能被片段直接支持？输出JSON: {"faithfulness": 0到1的小数}` }], 100)
    .then(t => { try { return Number(JSON.parse(jsonOf(t)).faithfulness) || 0 } catch { return 0 } })
  const relevancy = await llm([...judgeBase, { role: 'user', content: `问题: ${c.q}\n回答: ${answer}\n\n回答直接切题解答该问题的程度打分1-5。输出JSON: {"score": 1到5的整数}` }], 80)
    .then(t => { try { return Number(JSON.parse(jsonOf(t)).score) || 0 } catch { return 0 } })
  return { q: c.q, goldHit: goldIdx >= 0, contextPrecision: precision, contextRecall: recall, faithfulness: faith, answerRelevancy: relevancy / 5 }
}

const results = []
for (const [i, c] of CASES.entries()) {
  const r = await runCase(c)
  results.push(r)
  if (r.error) console.log(`  ⚠️ [${i}] ${r.q.slice(0, 24)} → ${r.error}`)
  else console.log(`  ${r.goldHit ? '✅' : '⚠️'} [${i}] CP=${r.contextPrecision.toFixed(2)} CR=${r.contextRecall.toFixed(2)} F=${r.faithfulness.toFixed(2)} AR=${r.answerRelevancy.toFixed(2)}`)
}
const ok = results.filter(r => !r.error)
const avg = k => (ok.reduce((s, r) => s + r[k], 0) / Math.max(1, ok.length))
console.log(`\n===== RAGAS 四件套（${ok.length}/${CASES.length} 例有效） =====`)
console.log(`Context Precision = ${(avg('contextPrecision') * 100).toFixed(1)}%`)
console.log(`Context Recall    = ${(avg('contextRecall') * 100).toFixed(1)}%`)
console.log(`Faithfulness      = ${(avg('faithfulness') * 100).toFixed(1)}%`)
console.log(`Answer Relevancy  = ${(avg('answerRelevancy') * 100).toFixed(1)}%`)
fs.writeFileSync('logs/ragas-report.json', JSON.stringify(results, null, 2))
console.log('明细已写 logs/ragas-report.json')
process.exit(0)
