// 课程语义召回评测：复刻 KnowledgeBase.searchCourses 的检索层查询
//（course_intro 索引 knn+match 双路 RRF 融合，rankConstant=60），测 Recall@1/@3/@5 与 MRR。
// 用法: node scripts/course-recall-eval.mjs
// 前置：ES :9200 已启动且 course_intro 索引已摄入（POST :8080/api/admin/corpus/sync）
import fs from 'node:fs'

const ES = 'http://localhost:9200'
const CASES = JSON.parse(fs.readFileSync(new URL('./eval-data/course-recall-150.json', import.meta.url)))

async function searchCourses(goal, size) {
  const body = {
    knn: { field: 'embedding', query_vector: null, k: 10, num_candidates: 50 },
    query: { match: { text: goal } },
    rank: { rrf: { rank_constant: 60, rank_window_size: 20 } },
    size, _source: ['docId', 'title'],
  }
  // 先取查询向量（与生产 EmbeddingService 同源）
  const embBody = JSON.stringify({ model: 'text-embedding-v3', input: [goal], dimensions: 1024 })
  const embResp = await fetch('https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + (process.env.DASHSCOPE_API_KEY ?? '') },
    body: embBody,
  })
  body.knn.query_vector = (await embResp.json()).data[0].embedding
  const resp = await fetch(`${ES}/course_intro/_search`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  const json = await resp.json()
  return (json.hits?.hits ?? []).map(h => String(h._source?.docId ?? h._id))
}

let pass1 = 0, pass3 = 0, pass5 = 0, mrrSum = 0
const fails = []
for (const c of CASES) {
  const ids = await searchCourses(c.goal, 10)
  const rank = ids.indexOf(c.expect) + 1
  if (rank === 1) pass1++
  if (rank >= 1 && rank <= 3) pass3++
  if (rank >= 1 && rank <= 5) pass5++
  mrrSum += rank > 0 ? 1 / rank : 0
  const topN = c.topN ?? 1
  const ok = rank >= 1 && rank <= topN
  if (!ok) {
    fails.push({ goal: c.goal, expect: c.expect, rank, top3: ids.slice(0, 3) })
    console.log(`  ❌ [${c.expect}] "${c.goal.slice(0, 30)}" → rank=${rank} top3=${ids.slice(0, 3)}`)
  } else console.log(`  ✅ [${c.expect}] rank=${rank}`)
}
const N = CASES.length
console.log(`\n===== 课程语义召回: ${N} 例 =====`)
console.log(`Recall@1=${(pass1 / N * 100).toFixed(1)}%  Recall@3=${(pass3 / N * 100).toFixed(1)}%  Recall@5=${(pass5 / N * 100).toFixed(1)}%  MRR=${(mrrSum / N).toFixed(4)}`)
process.exit(0)
