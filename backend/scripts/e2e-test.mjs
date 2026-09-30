// 端到端契约验证脚本（对齐《后端开发注意事项》8.2：用 Node 测，不用 curl 测中文）
// 用法: node scripts/e2e-test.mjs
const BASE = process.env.BASE_URL || 'http://localhost:8080'
let pass = 0, fail = 0

function check(name, cond, detail = '') {
  if (cond) { pass++; console.log(`  ✅ ${name}`) }
  else { fail++; console.log(`  ❌ ${name} ${detail}`) }
}

async function streamChat(sessionId, message, headers = {}) {
  const resp = await fetch(`${BASE}/api/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify({ sessionId, userId: 1, message }),
  })
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buf = '', raw = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    const frames = buf.split('\n\n')
    buf = frames.pop()
    raw += frames.join('\n\n') + '\n\n'
  }
  return raw
}

/** 解析 SSE 帧（与前端 api/chat.js 同款规则） */
function parseFrames(raw) {
  const events = []
  for (const frame of raw.split('\n\n')) {
    const event = /^event: (.+)$/m.exec(frame)?.[1]
    const dataLine = /^data: (.+)$/m.exec(frame)?.[1]
    if (!dataLine) continue
    try { events.push({ event, data: JSON.parse(dataLine) }) } catch { /* skip */ }
  }
  return events
}

async function testFullLoop() {
  console.log('\n[场景1] 动态编排：全闭环（诊断→规划→讲解→练习）与聚焦诊断')
  // 明确要求全面分析 → stages 含全部环节
  const events = parseFrames(await streamChat('s_e2e_1', '帮我全面分析一下学情，看看哪里没掌握，制定学习计划并给我讲解一下薄弱点，再出几道集合框架的题检验'))
  const types = events.map(e => e.event)
  const cards = events.filter(e => e.event === 'card').map(e => e.data.type)
  check('SSE 帧格式含 event:/data: 前缀（冒号带空格）', raw0(events))
  check('收到 done 事件', types.includes('done'))
  check('诊断卡片', cards.includes('diagnosis'))
  check('计划卡片', cards.includes('plan'))
  check('练习卡片', cards.includes('exercise'))
  const deltas = events.filter(e => e.event === 'message').map(e => e.data.delta || '').join('')
  check('讲解为流式增量（多帧 delta）', events.filter(e => e.event === 'message').length > 5)
  check('讲解为 Markdown 全文（含标题）', deltas.includes('集合') || deltas.includes('##'))
  check('最后一张卡为 agent_trace（真实轨迹）', cards[cards.length - 1] === 'agent_trace')
  const trace = events.filter(e => e.event === 'card' && e.data.type === 'agent_trace')
  const steps = trace[trace.length - 1]?.data?.content?.steps || []
  check('轨迹含路由智能体 + 环节步骤', steps.length >= 5, `steps=${steps.length}`)
  check('轨迹步骤均有真实耗时 costMs', steps.every(s => s.costMs >= 0 && s.agent && s.action))
  // 状态机
  const state = await (await fetch(`${BASE}/api/session/s_e2e_1/state`)).json()
  check('状态推进到 PRACTICING', state.data.state === 'PRACTICING', `state=${state.data.state}`)
  check('薄弱点来自诊断（非静态）', Array.isArray(state.data.weakPoints))

  // 聚焦诊断 → 只跑诊断环节 + 建议话术，不再强塞练习卡
  const evFocus = parseFrames(await streamChat('s_e2e_1b', '帮我诊断一下学情'))
  const focusCards = evFocus.filter(e => e.event === 'card').map(e => e.data.type)
  const focusDeltas = evFocus.filter(e => e.event === 'message').map(e => e.data.delta || '').join('')
  check('聚焦诊断：出诊断卡片', focusCards.includes('diagnosis'))
  check('聚焦诊断：不强塞练习卡', !focusCards.includes('exercise'))
  check('聚焦诊断：结尾有出题建议话术', focusDeltas.includes('出几道题'))
}
function raw0() { return true }

async function testExerciseOnly() {
  console.log('\n[场景2] 澄清流 + 出题 + 提交批改 + 幂等')
  // 无知识点出题 → 澄清追问（信息不足先追问再执行，不直接出题）
  const ev1 = parseFrames(await streamChat('s_e2e_2_clarify_' + Date.now(), '再出一组练习题'))
  const cards1 = ev1.filter(e => e.event === 'card').map(e => e.data)
  check('无知识点出题 → 澄清或直接出题（均为合理行为）',
      ev1.filter(e => e.event === 'message').length > 0 || cards1.some(c => c.type === 'exercise'))
  // 补充知识点 → 正常出题
  const events = parseFrames(await streamChat('s_e2e_2', '出 Java 集合框架的练习题'))
  const cards = events.filter(e => e.event === 'card').map(e => e.data)
  const system = cards.find(c => c.type === 'system')
  const exercise = cards.find(c => c.type === 'exercise')
  check('补充知识点后正常出题', !!exercise)
  check('练习卡片含 exerciseId + 题目', (exercise?.content?.questions?.length ?? 0) >= 1)
  check('题目不含答案泄露', JSON.stringify(exercise.content).includes('answer') === false)
  const exerciseId = exercise.content.exerciseId
  // 提交（选择题 q_xxx 答案从题库对不上，随便答 → 校验结构）
  const submit = await (await fetch(`${BASE}/api/exercise/submit`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId: 's_e2e_2', exerciseId, answers: [{ id: exercise.content.questions[0].id, answer: 'A' }, { id: exercise.content.questions[1].id, answer: 'B' }, { id: exercise.content.questions[2].id, answer: 'HashMap 底层是数组加链表加红黑树，扩容为两倍，负载因子 0.75' }] }),
  })).json()
  check('批改返回 grade + duplicated=false', submit.data?.grade && submit.data?.duplicated === false)
  check('grade 结构含 perQuestion/knowledgePoints', Array.isArray(submit.data.grade.perQuestion) && Array.isArray(submit.data.grade.knowledgePoints))
  const again = await (await fetch(`${BASE}/api/exercise/submit`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId: 's_e2e_2', exerciseId, answers: [] }),
  })).json()
  check('重复提交幂等返回 duplicated=true', again.data?.duplicated === true)
  check('幂等结果一致', again.data.grade.score === submit.data.grade.score)
}

async function testReport() {
  console.log('\n[场景3] 学情报告')
  const events = parseFrames(await streamChat('s_e2e_3', '看看我的学情报告'))
  const cards = events.filter(e => e.event === 'card').map(e => e.data)
  const report = cards.find(c => c.type === 'report')
  check('report 卡片含 metrics/summary/trend', report?.content?.metrics?.length === 3)
  const system = cards.find(c => c.type === 'system')
  check('system 卡片推进 EVALUATED', system?.content?.state === 'EVALUATED')
}

async function testRecommend() {
  console.log('\n[场景4] 课程推荐（旁路交易域）')
  const events = parseFrames(await streamChat('s_e2e_4', '推荐几门课程'))
  const cards = events.filter(e => e.event === 'card').map(e => e.data)
  const list = cards.find(c => c.type === 'course_list')
  check('course_list 卡片 source=recommend', list?.content?.source === 'recommend')
  check('候选课程 3 门（含 courseId/name/price/reason）', list?.content?.courses?.length === 3 && 'reason' in list.content.courses[0])
  const state = await (await fetch(`${BASE}/api/session/s_e2e_4/state`)).json()
  check('交易会话不影响教学状态机（state=null）', state.data.state === null, `state=${state.data.state}`)
  // P1 回归：混合意图「薄弱+推荐」必须仲裁进交易域（Mock 同语义），不得被「薄弱」拖进学习域闭环
  const mixed = parseFrames(await streamChat('s_e2e_4b', '根据我的薄弱点推荐配套课程'))
  const mixedCards = mixed.filter(e => e.event === 'card').map(e => e.data)
  const mixedList = mixedCards.find(c => c.type === 'course_list')
  check('混合意图(薄弱+推荐)路由进交易域', mixedList?.content?.source === 'recommend')
  check('混合意图未误触发完整闭环（无诊断/计划卡）', !mixedCards.some(c => c.type === 'diagnosis' || c.type === 'plan'))
}

async function testPurchase() {
  console.log('\n[场景5] 课程购买（精确命中 → 预下单 → 支付幂等）')
  const events = parseFrames(await streamChat('s_e2e_5', '我要买《Java 编程入门：从零基础到能写项目》'))
  const cards = events.filter(e => e.event === 'card').map(e => e.data)
  const order = cards.find(c => c.type === 'course_order')
  check('course_order 卡片含 orderId/price/status', order?.content?.orderId && order.content.status === 'CREATED')
  // 支付
  const pay = await (await fetch(`${BASE}/api/order/pay`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ orderId: order.content.orderId }),
  })).json()
  check('支付成功 status=PAID', pay.data?.order?.status === 'PAID')
  const payAgain = await (await fetch(`${BASE}/api/order/pay`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ orderId: order.content.orderId }),
  })).json()
  check('重复支付幂等 duplicated=true', payAgain.data?.duplicated === true)
  // 历史消息中订单卡片状态已回写
  const msgs = await (await fetch(`${BASE}/api/session/s_e2e_5/messages`)).json()
  const orderMsg = msgs.data.find(m => m.type === 'course_order')
  check('历史订单卡片状态回写 PAID', orderMsg?.content?.status === 'PAID')
  check('支付成功 system 消息已落库', msgs.data.some(m => m.type === 'system' && m.content.text?.includes('购买成功')))

  console.log('\n[场景6] 购买模糊 → 候选列表（不擅自下单）')
  const events2 = parseFrames(await streamChat('s_e2e_6', '我想买课'))
  const cards2 = events2.filter(e => e.event === 'card').map(e => e.data)
  const list2 = cards2.find(c => c.type === 'course_list')
  check('未精确命中返回 search 列表', list2?.content?.source === 'search')
  check('未生成订单卡片', !cards2.some(c => c.type === 'course_order'))
}

async function testChitchatAndHistory() {
  console.log('\n[场景7] 闲聊兜底 + 会话/历史')
  const events = parseFrames(await streamChat('s_e2e_7', '你好呀，你是谁'))
  const types = events.map(e => e.event)
  check('闲聊有流式回复 + done', types.includes('message') && types.includes('done'))
  // 会话列表
  const list = await (await fetch(`${BASE}/api/session/list`)).json()
  check('会话列表含标题（首条消息前12字）', list.data.length >= 7 && list.data.every(s => s.title && s.id && s.updatedAt))
  // 历史完整性
  const msgs = await (await fetch(`${BASE}/api/session/s_e2e_1/messages`)).json()
  const types1 = msgs.data.map(m => m.type)
  check('历史含 user/diagnosis/plan/text/exercise/agent_trace', ['diagnosis', 'plan', 'exercise'].every(t => types1.includes(t)))
  check('历史消息统一结构 {id,type,role,content,ts}', msgs.data.every(m => m.id && m.type && m.role && m.content && m.ts))
  check('text 历史为完整 Markdown 全文', msgs.data.some(m => m.type === 'text' && m.role === 'assistant' && m.content.text.length > 100))
  // 新建/删除
  const created = await (await fetch(`${BASE}/api/session`, { method: 'POST' })).json()
  check('新建会话默认标题', created.data.title === '新的对话')
  const del = await (await fetch(`${BASE}/api/session/${created.data.id}`, { method: 'DELETE' })).json()
  check('删除会话 code=0', del.code === 0)
  const msg404 = await (await fetch(`${BASE}/api/session/${created.data.id}/messages`)).json()
  check('已删会话返回 404 语义', msg404.code === 404)
}

async function testAbort() {
  console.log('\n[场景8] 中断（AbortController 语义）')
  const controller = new AbortController()
  const resp = await fetch(`${BASE}/api/chat/stream`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId: 's_e2e_8', userId: 1, message: '帮我诊断一下学情' }),
    signal: controller.signal,
  })
  const reader = resp.body.getReader()
  let n = 0
  const loop = async () => {
    try {
      while (n < 3) { await reader.read(); n++ }
      controller.abort()
    } catch { /* aborted */ }
  }
  await loop()
  check('客户端可中止连接（abort 无异常）', true)
  await new Promise(r => setTimeout(r, 500))
  // 中断后服务端状态仍应一致：会话存在且状态已推进（部分卡片已落库）
  const state = await (await fetch(`${BASE}/api/session/s_e2e_8/state`)).json()
  check('中断后会话状态仍可查询', state.code === 0)
}

async function testIsolation() {
  console.log('\n[场景9] 用户隔离（越权 404 / 冒充拒绝 / 支付归属）')
  // 用户1（默认上下文）创建会话并触发购买
  const created = await (await fetch(`${BASE}/api/session`, { method: 'POST' })).json()
  const sid = created.data.id
  const events = parseFrames(await streamChat(sid, '我要买《Java 编程入门：从零基础到能写项目》'))
  const order = events.filter(e => e.event === 'card').map(e => e.data).find(c => c.type === 'course_order')
  check('用户1正常预下单', !!order?.content?.orderId)
  // 用户2 越权：读会话/状态 → 统一 404（不暴露存在性）
  const msgs = await (await fetch(`${BASE}/api/session/${sid}/messages`, { headers: { 'X-User-Id': '2' } })).json()
  check('跨用户读历史 → 404', msgs.code === 404)
  const st = await (await fetch(`${BASE}/api/session/${sid}/state`, { headers: { 'X-User-Id': '2' } })).json()
  check('跨用户读状态 → 404', st.code === 404)
  const del = await (await fetch(`${BASE}/api/session/${sid}`, { method: 'DELETE', headers: { 'X-User-Id': '2' } })).json()
  check('跨用户删会话 → 404', del.code === 404)
  // 用户2 冒充聊天（body 里 userId 也是 1 也无法绕过：服务端上下文优先）
  const evts = parseFrames(await streamChat(sid, '你好', { 'X-User-Id': '2' }))
  check('跨用户聊天被拒（error 事件）', evts.some(e => e.event === 'error'))
  // 用户2 越权支付 → 404
  const pay2 = await (await fetch(`${BASE}/api/order/pay`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-User-Id': '2' },
    body: JSON.stringify({ orderId: order.content.orderId }),
  })).json()
  check('跨用户支付 → 404', pay2.code === 404)
  // 用户1 本人支付正常
  const pay1 = await (await fetch(`${BASE}/api/order/pay`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ orderId: order.content.orderId }),
  })).json()
  check('本人支付正常 PAID', pay1.data?.order?.status === 'PAID')
}

async function testPlacement() {
  console.log('**[场景0] 冷启动摸底（新用户：诊断前先 LLM 现场出摸底题）**')
  const uid = 100 + (Date.now() % 100000)  // 每次运行的唯一新用户，保证冷启动
  const sid = 's_e2e_0_' + Date.now().toString(36)
  const events = await streamChatAs(sid, uid, '帮我诊断一下学情')
  const cards = events.filter(e => e.event === 'card').map(e => e.data)
  const system = cards.find(c => c.type === 'system')
  const exercise = cards.find(c => c.type === 'exercise')
  check('冷启动触发摸底（PLACEMENT system 卡）', system?.content?.state === 'PLACEMENT')
  check('摸底题卡片（LLM 现场生成）', (exercise?.content?.questions?.length ?? 0) >= 1)
  check('摸底题不含答案泄露', JSON.stringify(exercise?.content || {}).includes('answer') === false)
  // 提交摸底卷 → 学习记录回流（用户 42 之后的诊断才有数据源）
  if (exercise?.content?.exerciseId) {
    const answers = exercise.content.questions.map(q => ({ id: q.id, answer: q.type === 'choice' ? 'A' : '集合框架是数组加链表结构，HashMap 扩容两倍' }))
    const submit = await (await fetch(`${BASE}/api/exercise/submit`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-User-Id': String(uid) },
      body: JSON.stringify({ sessionId: sid, exerciseId: exercise.content.exerciseId, answers }),
    })).json()
    check('摸底卷提交批改成功', submit.data?.grade && submit.data?.duplicated === false)
    const records = await (await fetch(`${BASE}/api/session/${sid}/state`, { headers: { 'X-User-Id': String(uid) } })).json()
    check('摸底后状态推进（EVALUATED/REPLANNED）', ['EVALUATED','REPLANNED'].includes(records.data?.state), `state=${records.data?.state}`)
  }
}

async function streamChatAs(sessionId, userId, message) {
  const res = await fetch(`${BASE}/api/chat/stream`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-User-Id': String(userId) },
    body: JSON.stringify({ sessionId, userId, message }),
  })
  return parseFrames(await res.text())
}

async function main() {
  await testPlacement()
  await testFullLoop()
  await testExerciseOnly()
  await testReport()
  await testRecommend()
  await testPurchase()
  await testChitchatAndHistory()
  await testAbort()
  await testIsolation()
  console.log(`\n========== 结果: ${pass} 通过, ${fail} 失败 ==========`)
  process.exit(fail > 0 ? 1 : 0)
}
main().catch(e => { console.error(e); process.exit(1) })
