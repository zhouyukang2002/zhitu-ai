// MCP 协议握手验证：SSE 连接 → initialize → tools/list → tools/call
import http from 'node:http'

const SERVER = 'http://localhost:8081'
let sessionId = null
const sseMessages = []
const waitForId = async (id, timeoutMs = 30000) => {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    const m = sseMessages.find(m => m.id === id)
    if (m) return m
    await new Promise(r => setTimeout(r, 100))
  }
  throw new Error('timeout waiting for id=' + id)
}

const req = http.get(`${SERVER}/sse`, res => {
  res.on('data', chunk => {
    const text = chunk.toString()
    for (const block of text.split('\n\n')) {
      const ev = /^event:\s*(.+)$/m.exec(block)?.[1]
      const data = /^data:\s*(.+)$/m.exec(block)?.[1]
      if (!data) continue
      if (ev === 'endpoint') { sessionId = data }
      else if (ev === 'message') { sseMessages.push(JSON.parse(data)) }
    }
  })
})
await new Promise(r => setTimeout(r, 500))
console.log('SSE 连接成功, message endpoint:', sessionId)
const post = body => fetch(`${SERVER}${sessionId}`, {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)})

await post({jsonrpc:'2.0', id:1, method:'initialize', params:{protocolVersion:'2024-11-05', capabilities:{}, clientInfo:{name:'verify', version:'1.0'}}})
await waitForId(1)
await post({jsonrpc:'2.0', method:'notifications/initialized'})

await post({jsonrpc:'2.0', id:2, method:'tools/list'})
const listMsg = await waitForId(2)
const tools = listMsg?.result?.tools || []
console.log(`tools/list: ${tools.length} 个工具`)
tools.forEach(t => console.log(`  - ${t.name}`))

await post({jsonrpc:'2.0', id:3, method:'tools/call', params:{name:'searchCourses', arguments:{keyword:'Java', maxPrice:500}}})
const callMsg = await waitForId(3)
const content = callMsg?.result?.content?.[0]?.text
console.log(`searchCourses 调用: ${(content||'').slice(0, 200)}`)

await post({jsonrpc:'2.0', id:4, method:'tools/call', params:{name:'getQuestions', arguments:{knowledgePoint:'集合框架', count:2}}})
const qMsg = await waitForId(4)
const qText = qMsg?.result?.content?.[0]?.text || ''
console.log(`getQuestions 泄题检查: ${qText.includes('"answer"') || qText.includes('"reference"') ? '❌ 答案泄漏' : '✅ 无答案字段'}`)

process.exit(0)
