const BASE = 'http://localhost:8080'
await fetch(`${BASE}/api/chat/stream`, {method:'POST',headers:{'Content-Type':'application/json'},
  body:JSON.stringify({sessionId:'s_obs_1',userId:1,message:'帮我诊断一下学情'})}).then(r=>r.text())
const traces = await (await fetch(`${BASE}/api/admin/traces?sessionId=s_obs_1`)).json()
const t = traces.data.items[0]
console.log('Trace 落库:', t.id, '| intent:', t.intent, '| status:', t.status,
  '| tokens:', t.promptTokens + '+' + t.completionTokens, '| cost:', t.cost, '| latency:', t.latencyMs + 'ms')
const detail = await (await fetch(`${BASE}/api/admin/trace/${t.id}`)).json()
console.log('观测节点:', detail.data.observations.map(o => `${o.type}:${o.name}(${o.latencyMs}ms,${o.promptTokens||0}+${o.completionTokens||0}tok)`).join(' → '))
const m = await (await fetch(`${BASE}/api/admin/metrics`)).json()
console.log('指标总览:', JSON.stringify(m.data))
await fetch(`${BASE}/api/admin/feedback`, {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({traceId:t.id,rating:1,comment:'不错'})})
await fetch(`${BASE}/api/admin/trace/${t.id}/label`, {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({expectedIntent:'DIAGNOSE'})})
const end = new Date().toISOString(), start = new Date(Date.now()-86400000).toISOString()
const report = await (await fetch(`${BASE}/api/admin/eval`, {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({startAt:start,endAt:end,includeJudge:true})})).json()
const r0 = report.data.results[0]
console.log(`评估报告: avgScore=${report.data.avgScore} | rule=${r0.ruleScore} judge=${r0.judgeScore} feedback=${r0.feedbackScore} | judgeReason=${r0.detail.judgeReason}`)
