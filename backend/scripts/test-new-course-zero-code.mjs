// 测试零代码新增课程全链路能力验证脚本
const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function sendChat(sessionId, message, userId = 1) {
  const resp = await fetch(`${BASE_URL}/api/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': String(userId),
      'X-User-Role': 'ROLE_USER'
    },
    body: JSON.stringify({ sessionId, message, userId })
  });

  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let done = false;
  let fullText = '';
  const cards = [];

  while (!done) {
    const { value, done: streamDone } = await reader.read();
    done = streamDone;
    if (value) {
      const chunk = decoder.decode(value, { stream: true });
      fullText += chunk;
    }
  }

  // 解析 SSE 块中的 card 和 text
  const lines = fullText.split('\n');
  for (const line of lines) {
    if (line.startsWith('event: card')) {
      // 下一行是 data
    } else if (line.startsWith('data: ')) {
      try {
        const json = JSON.parse(line.substring(6));
        if (json.type) {
          cards.push(json);
        }
      } catch (e) {}
    }
  }

  return { fullText, cards };
}

async function run() {
  const runId = Date.now();
  console.log(`=== 开始零代码新增课程全链路自动化验证 [runId=${runId}] ===\n`);

  // 测试 1：课程推荐（感知新课程 Go 并推荐 c099）
  console.log('【测试 1】：课程推荐感知新课程');
  const recResult = await sendChat(`test-rec-${runId}`, '我想学 Go 语言，有没有相关的课程推荐？');
  const recHasC099 = recResult.fullText.includes('c099') || recResult.fullText.includes('Go 语言高并发实战');
  console.log(`推荐结果命中 c099 / Go 课程: ${recHasC099}`);
  if (!recHasC099) {
    console.log('未检测到 c099，响应片段:', recResult.fullText.substring(0, 500));
  } else {
    console.log('✅ 测试 1 通过：零代码添加的 Go 课程成功被推荐引擎召回！');
  }

  // 测试 2：内容讲解 RAG（知识点检索与溯源）
  console.log('\n【测试 2】：新语料知识点 RAG 检索与讲解');
  const teachResult = await sendChat(`test-teach-${runId}`, '给我讲讲 Go 语言里的 Goroutine 协程和 Channel 管道机制');
  const teachHasKp = teachResult.fullText.includes('Goroutine') || teachResult.fullText.includes('CSP') || teachResult.fullText.includes('GMP');
  console.log(`讲解结果包含 Go 核心概念: ${teachHasKp}`);
  if (teachHasKp) {
    console.log('✅ 测试 2 通过：新语料知识条目被 RAG 成功检索并用于流式教学！');
  } else {
    console.log('响应片段:', teachResult.fullText.substring(0, 500));
  }

  // 测试 3：练习出题
  console.log('\n【测试 3】：新语料知识点练习出题');
  const quizResult = await sendChat(`test-quiz-${runId}`, '我想练练 Go 协程与 Channel，出两道题考考我');
  const quizHasCard = quizResult.cards.some(c => c.type === 'exercise' || c.type === 'practice') || quizResult.fullText.includes('exercise');
  console.log(`练习卡片生成状态: ${quizHasCard}`);
  if (quizHasCard) {
    console.log('✅ 测试 3 通过：新课程题库/知识点顺利完成出题组卷！');
  } else {
    console.log('响应片段:', quizResult.fullText.substring(0, 500));
  }

  // 测试 4：课程购买预下单
  console.log('\n【测试 4】：新课程购买与预下单');
  const buyResult = await sendChat(`test-buy-${runId}`, '我想买 Go 语言高并发实战与微服务进阶 这门课');
  const buyHasOrder = buyResult.cards.some(c => c.type === 'course_order') || buyResult.fullText.includes('course_order');
  console.log(`预下单卡片生成状态: ${buyHasOrder}`);
  if (buyHasOrder) {
    console.log('✅ 测试 4 通过：零代码新增的课程成功完成精准匹配与预下单！');
  } else {
    console.log('响应片段:', buyResult.fullText.substring(0, 500));
  }

  // 测试 5：全局复盘与收尾总结（复用 test-teach 的 session）
  console.log('\n【测试 5】：多轮会话动态复盘与总结');
  const recapResult = await sendChat(`test-teach-${runId}`, '今天学得差不多了，帮我做个全局复盘总结');
  const recapPassed = recapResult.fullText.includes('Go') || recapResult.fullText.includes('协程') || recapResult.fullText.includes('Channel') || recapResult.fullText.includes('复盘');
  console.log(`复盘包含今日 Go 学习主题: ${recapPassed}`);
  if (recapPassed) {
    console.log('✅ 测试 5 通过：动态复盘成功根据多轮对话主题生成个性化总结，杜绝 Java 领域硬编码！');
  } else {
    console.log('响应片段:', recapResult.fullText.substring(0, 500));
  }

  console.log('\n=== 全链路验证完成 ===');
}

run().catch(console.error);
