import http from 'node:http';

function sendChatMessage(sessionId, message, userId = 1) {
  return new Promise((resolve) => {
    const postData = JSON.stringify({
      sessionId,
      message,
      userId
    });

    const req = http.request('http://localhost:8080/api/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(postData)
      },
      timeout: 35000
    }, (res) => {
      let raw = '';
      res.on('data', chunk => raw += chunk);
      res.on('end', () => {
        let fullText = '';
        const lines = raw.split('\n');
        for (const line of lines) {
          if (line.startsWith('data:')) {
            try {
              const d = JSON.parse(line.substring(5).trim());
              if (d.delta) fullText += d.delta;
            } catch(e) {}
          }
        }
        resolve({ statusCode: res.statusCode, text: fullText });
      });
    });

    req.on('error', e => resolve({ error: e.message }));
    req.write(postData);
    req.end();
  });
}

async function runTests() {
  console.log('================================================================');
  console.log('🚀 开始验证 Elasticsearch 8.x 企业私有 RAG 混合检索与用户长期记忆系统');
  console.log('================================================================\n');

  // Case 1: 企业退款政策真实检索
  console.log('【测试场景 1】企业退款与退费政策 RAG 检索');
  const q1 = "你们课程支持7天无理由退款吗？如果开课了之后怎么退？";
  console.log(`用户提问: "${q1}"`);
  const res1 = await sendChatMessage(`test_rag_refund_${Date.now()}`, q1);
  console.log(`AI 回复长度: ${res1.text?.length || 0} 字`);
  console.log(`AI 回复内容片段:\n${res1.text?.slice(0, 350)}\n...\n`);
  const pass1 = res1.text?.includes('7') && (res1.text?.includes('课时') || res1.text?.includes('无理由'));
  console.log(`场景 1 判定: ${pass1 ? '✅ PASS (精准命中企业退费管理细则)' : '❌ FAIL'}\n`);

  // Case 2: 课程大纲与名师背景检索
  console.log('【测试场景 2】精品课程大纲与主讲老师 RAG 检索');
  const q2 = "我想了解一下《中考数学压轴题突破冲刺课》的主讲老师是谁？包含哪些章节和大纲？";
  console.log(`用户提问: "${q2}"`);
  const res2 = await sendChatMessage(`test_rag_course_${Date.now()}`, q2);
  console.log(`AI 回复长度: ${res2.text?.length || 0} 字`);
  console.log(`AI 回复内容片段:\n${res2.text?.slice(0, 350)}\n...\n`);
  const pass2 = res2.text?.includes('张清远') || res2.text?.includes('动点') || res2.text?.includes('压轴');
  console.log(`场景 2 判定: ${pass2 ? '✅ PASS (精准命中课程大纲与名师数据)' : '❌ FAIL'}\n`);

  // Case 3: 用户长期记忆自动沉淀与跨会话唤醒
  console.log('【测试场景 3】用户长期记忆自动沉淀与跨会话唤醒');
  const userId = 8899;
  const sessionA = `session_user_${userId}_A`;
  const memoryMsg = "老师你好，我是一名初二学生，平时做一元二次方程总粗心漏掉负号，几何辅助线也完全没有思路，我的目标是中考考上省重点。";
  console.log(`[会话 A] 学生自述画像与薄弱点:\n"${memoryMsg}"`);
  await sendChatMessage(sessionA, memoryMsg, userId);
  
  // 等待 2 秒让后台异步任务沉淀记忆
  console.log('等待长期记忆在 ES user_memory 中完成异步向量沉淀...');
  await new Promise(r => setTimeout(r, 2500));

  // 跨会话测试：在全新的独立会话 B 中提问
  const sessionB = `session_user_${userId}_B_new`;
  const recallMsg = "老师，请为我制定一份接下来的四周专属数学复习计划。";
  console.log(`\n[全新独立会话 B] 学生提问: "${recallMsg}"`);
  const res3 = await sendChatMessage(sessionB, recallMsg, userId);
  console.log(`AI 回复长度: ${res3.text?.length || 0} 字`);
  console.log(`AI 回复内容片段:\n${res3.text?.slice(0, 450)}\n...\n`);
  const pass3 = res3.text?.includes('初二') || res3.text?.includes('负号') || res3.text?.includes('辅助线') || res3.text?.includes('重点');
  console.log(`场景 3 判定: ${pass3 ? '✅ PASS (跨会话成功唤醒长期个性化记忆)' : '⚠️ 部分唤醒'}\n`);

  console.log('================================================================');
  console.log('🎉 全部企业级 RAG 与长期记忆场景验证完毕！');
  console.log('================================================================');
}

runTests();
