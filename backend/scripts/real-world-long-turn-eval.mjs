// 真实学员多轮长对话智能度深度压力测试（TC-INTEL-01 ~ TC-INTEL-07）
// 考察维度：远距离记忆锚定、跨轮隐式指代消解、深水区权衡、话题横切与并发关联、动态出题、苏格拉底式纠偏、克制交互与全局闭环复盘。
// 用法: node scripts/real-world-long-turn-eval.mjs
import fs from 'node:fs'

const BASE = process.env.BASE_URL || 'http://localhost:8080'
const sessionId = 'real_user_student_' + Date.now().toString(36)
const userId = 1001 // 张同学 (非计算机转Java后端)

const turns = [
  {
    turn: 1,
    title: '背景感知与通俗化拆解（考察意图路由、学员背景感知、教学启发性）',
    prompt: '老师好，我是土木转行学Java后端的，现在刚学到集合这块，但是HashMap总搞不明白，感觉面试经常被问到底层的红黑树和扩容，我底子比较薄，该怎么理解？'
  },
  {
    turn: 2,
    title: '跨轮隐式指代与深水区权衡（考察指代消解、上下文继承、RAG专业深度）',
    prompt: '你刚才提到的哈希冲突和链表转红黑树，为什么JDK8一定要选红黑树，而不是更简单的二叉平衡树AVL？红黑树到底好在哪里？'
  },
  {
    turn: 3,
    title: '话题横切与并发概念穿插（考察话题漂移与回归、多线程并发概念穿插）',
    prompt: '明白了！那我在做力扣第一题两数之和时，大家都推荐用HashMap做O(1)查找，但我总担心多线程并发安全。如果两个线程同时往HashMap里put，会发生什么灾难？'
  },
  {
    turn: 4,
    title: '主动求考与动态试题生成（考察状态机流转到 PRACTICING、动态出题与 Critic 质检）',
    prompt: '原来会死循环或者数据丢失啊。那老师你能不能出一道经典的Java并发或集合面试真题考考我？带代码片段或者场景题的那种，别太难也别太水。'
  },
  {
    turn: 5,
    title: '思维盲区识别与苏格拉底纠偏（考察苏格拉底式判分、错因归因与耐挫引导）',
    prompt: '这道题我觉得应该选 B。因为 ConcurrentHashMap 在 JDK8 里面是靠 Segment 分段锁来保证并发安全的，每个段独立加锁，这样对吗？'
  },
  {
    turn: 6,
    title: '话题漂移、课程推荐与克制交互（考察需求提取、推荐智能体、杜绝强推订单与保持温度）',
    prompt: '天呐我把JDK7和8记混了！非常感谢老师点拨。那我接下来如果想系统攻克高并发和分布式系统设计，为明年春招做准备，系统里有适合我这种转码基础的实战课推荐吗？'
  },
  {
    turn: 7,
    title: '情绪退缩接纳与全局闭环复盘（考察全局历史总结、长程记忆聚合与情绪价值交付）',
    prompt: '太好了，这门课的大纲很符合我。今天学了这么多脑子有点涨，不想再做题了，老师能帮我简单复盘一下今天整堂课我们探讨的核心要点和我的薄弱盲区吗？'
  }
]

async function streamTurn(turnObj) {
  console.log(`\n================================================================================`)
  console.log(`【Turn ${turnObj.turn}】${turnObj.title}`)
  console.log(`👤 学员输入: "${turnObj.prompt}"`)
  console.log(`--------------------------------------------------------------------------------`)

  const t0 = Date.now()
  let resp
  try {
    resp = await fetch(`${BASE}/api/chat/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-User-Id': String(userId),
        'X-User-Role': 'ROLE_USER',
        'X-User-Name': encodeURIComponent('student')
      },
      body: JSON.stringify({ sessionId, userId, message: turnObj.prompt })
    })
  } catch (err) {
    console.error(`❌ 请求失败:`, err.message)
    return { success: false, error: err.message }
  }

  if (!resp.ok) {
    console.error(`❌ HTTP 异常: ${resp.status}`)
    return { success: false, status: resp.status }
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  let replyText = ''
  let cards = []
  let traceSteps = []
  let ttft = null

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    const chunk = decoder.decode(value, { stream: true })
    buf += chunk
    const frames = buf.split('\n\n')
    buf = frames.pop()

    for (const frame of frames) {
      const eventMatch = /^event:\s*(.+)$/m.exec(frame)
      const dataMatch = /^data:\s*(.+)$/m.exec(frame)
      if (!dataMatch) continue
      const event = eventMatch ? eventMatch[1].trim() : 'message'
      try {
        const payload = JSON.parse(dataMatch[1].trim())
        if (event === 'message') {
          if (ttft === null) ttft = Date.now() - t0
          const delta = payload.delta ?? payload.text ?? ''
          replyText += delta
        } else if (event === 'card') {
          cards.push(payload)
          if (payload.type === 'AGENT_TRACE' && payload.content?.steps) {
            traceSteps = payload.content.steps
          }
        }
      } catch (e) {
        // ignore parse error
      }
    }
  }

  const totalCost = Date.now() - t0
  console.log(`🤖 助教回答:\n${replyText.trim()}`)
  console.log(`\n📊 运行时指标:`)
  console.log(`   - 首字延迟 (TTFT): ${ttft || totalCost}ms | 总响应耗时: ${totalCost}ms`)
  console.log(`   - 下发业务卡片数: ${cards.length} 张 [${cards.map(c => c.type).join(', ')}]`)
  if (traceSteps.length > 0) {
    console.log(`   - 内部智能体执行轨迹 (Trace):`)
    traceSteps.forEach(s => {
      console.log(`     • [${s.agent}] ${s.tool} -> ${s.action} (${s.costMs}ms, ${s.status})`)
    })
  }

  return {
    turn: turnObj.turn,
    title: turnObj.title,
    prompt: turnObj.prompt,
    reply: replyText.trim(),
    cards: cards.map(c => ({ type: c.type, content: c.content })),
    traceSteps,
    ttft,
    totalCost
  }
}

async function run() {
  console.log(`🚀 开始执行【智途AI 真实长对话真实多轮智能实测】...`)
  console.log(`会话ID: ${sessionId} | 模拟用户: 张同学(土木转Java, ID: 1001)`)
  const results = []
  for (const t of turns) {
    const res = await streamTurn(t)
    results.push(res)
    // 模拟真实人类思考与阅读间隔
    await new Promise(r => setTimeout(r, 1200))
  }

  const outPath = 'scripts/eval-data/real-world-long-turn-result.json'
  try {
    fs.writeFileSync(outPath, JSON.stringify(results, null, 2), 'utf-8')
    console.log(`\n\n✅ 7轮完整测试执行完毕，测试数据已保存至 ${outPath}！`)
  } catch {
    fs.writeFileSync('real-world-long-turn-result.json', JSON.stringify(results, null, 2), 'utf-8')
  }
}

run()
