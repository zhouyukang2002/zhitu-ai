import http from 'node:http';

const QUESTIONS = [
  // 1-10: Java 深度（并发/JVM/集合）
  "synchronized 和 ReentrantLock 的区别是什么？分别说明公平性、可中断性和性能层面的取舍，并给出选型建议。",
  "线程池的核心参数有哪些？请说明 corePoolSize、maximumPoolSize、拒绝策略之间的协作关系，并分析为什么大厂禁止直接使用 Executors 创建线程池。",
  "volatile 能保证可见性但不保证原子性，请解释背后的内存屏障原理，并用一个计数器场景说明如何正确替代。",
  "JVM 新生代和老年代的分代回收策略是什么？Minor GC 和 Full GC 的触发条件分别是什么？如何通过参数调优减少 Full GC 频率？",
  "HashMap 在 JDK 7 和 JDK 8 中的实现有什么区别？为什么引入红黑树？扩容时 1.8 的拆分优化好在哪里？",
  "ThreadLocal 会造成内存泄漏的根因是什么？为什么 key 要用弱引用？正确的使用姿势是什么？",
  "什么是 JVM 的三色标记算法？G1 与 CMS 在并发标记上的关键差异是什么？",
  "CAS 的 ABA 问题是怎么产生的？AtomicStampedReference 是如何解决的？举例说明什么场景必须处理。",
  "请解释 Java 内存模型中 happens-before 规则的核心含义，并说明它如何指导并发编程。",
  "CompletableFuture 相比 Future 的优势是什么？请用编排三个远程调用的例子说明 thenCombine 和 allOf 的用法差异。",
  // 11-20: MySQL/Spring/Redis
  "什么是覆盖索引？为什么 SELECT * 会破坏覆盖索引优化？请结合执行计划 explain 说明验证方法。",
  "MySQL 的可重复读隔离级别下是否完全避免了幻读？请结合间隙锁和 MVCC 快照读区分两种场景。",
  "在线上出现慢 SQL，请给出一条完整的排查链路：从 slow log 定位到执行计划分析再到索引优化决策。",
  "Spring Bean 的生命周期包含哪些关键阶段？BeanPostProcessor 在其中扮演什么角色？请按顺序完整描述。",
  "Spring 事务在什么情况下会失效？请至少列举四种典型失效场景并解释根因，例如自调用与异常被吞。",
  "循环依赖在 Spring 中是如何通过三级缓存解决的？为什么构造器注入的循环依赖无法解决？",
  "缓存穿透、击穿、雪崩分别是什么？请给出三者各自的组合解决方案，并说明互斥锁与逻辑过期的取舍。",
  "Redis 的持久化机制 RDB 和 AOF 各有什么优缺点？混合持久化是如何取长补短的？",
  "Redis 分布式锁为什么要用 SET NX PX 而不是 SETNX+EXPIRE 两步？看门狗机制解决什么问题？Redisson 的实现要点是什么？",
  "缓存与数据库双写一致性有哪些方案？为什么推荐先更新数据库再删除缓存？延迟双删解决什么问题？",
  // 21-30: 分布式/微服务/项目
  "什么是幂等性？在订单支付场景中请说明 Token 机制、数据库唯一索引、状态机三种幂等方案的实现细节与适用边界。",
  "消息队列如何保证消息不丢失？请从生产者确认、Broker 持久化、消费者手动 ACK 三个环节分别说明 RabbitMQ 的配置要点。",
  "请解释 Kafka 的 ISR 机制与 acks=all 的关系，并说明 min.insync.replicas 参数如何影响可用性与一致性的权衡。",
  "分布式事务的 TCC 和本地消息表方案有什么区别？在电商下单扣库存场景中应该怎么选？为什么？",
  "服务熔断、降级、限流三者的区别与联系是什么？请说明令牌桶和漏桶算法的差异以及各自的适用场景。",
  "什么是服务的优雅上下线？请结合注册中心的心跳与发现延迟说明为什么滚动发布需要就绪探针。",
  "设计一个秒杀系统：从流量削峰、库存预扣、防超卖到订单异步化，请给出分层架构设计与关键代码思路。",
  "网关层应该承担哪些职责？请说明鉴权、限流、路由在网关层的实现方式，以及网关自身如何做高可用。",
  "分布式链路追踪的原理是什么？TraceId 是如何在跨服务调用时传播的？结合 SkyWalking 说明埋点方式。",
  "电商系统中如何设计优惠券的超发防护？请从数据库乐观锁、Redis 预扣、库存分桶三个层面展开。",
  // 31-40: 前端/数据分析/AI
  "浏览器的渲染流水线包含哪些阶段？重排和重绘的区别是什么？如何用合成层优化动画性能？",
  "React Hooks 的工作原理是什么？为什么 Hooks 不能放在条件语句里？fiber 链表和 Hooks 的调用顺序有什么关系？",
  "Vue3 的响应式为什么用 Proxy 替代 Object.defineProperty？新的实现解决了哪些旧版痛点？",
  "前端首屏加载慢的优化方案有哪些？请从代码分割、资源压缩、SSR、缓存策略四个维度给出完整方案。",
  "TypeScript 的泛型和条件类型如何组合使用？请用 Partial、Pick 的实现原理说明映射类型的工作方式。",
  "Pandas 的 merge、join、concat 有什么区别？在处理百万级数据合并时有哪些性能优化手段？",
  "什么是假设检验的 p 值？A/B 测试中如何确定样本量和实验时长？辛普森悖论对数据分析有什么警示？",
  "数据仓库为什么分 ODS、DWD、DWS、ADS 层？维度建模中的星型模型和雪花模型如何选择？",
  "RAG 系统中为什么生产环境要用 BM25 与向量混合检索？RRF 融合相对加权分数融合的优势是什么？",
  "大模型应用中的幻觉问题有哪些缓解手段？请从检索增强、提示词约束、引用溯源三个层面展开说明。",
  // 41-50: AI 应用/DevOps/求职
  "AI Agent 的 Function Calling 与 MCP 协议有什么区别？MCP 如何解决工具生态的标准化问题？",
  "如何为 RAG 系统设计评估指标体系？请说明忠实度、上下文精确率、上下文召回率的计算口径与互相关系。",
  "LLM 应用的成本治理有哪些手段？请从模型分级路由、上下文裁剪、语义缓存三个维度说明各自的适用场景。",
  "Docker 镜像的分层存储原理是什么？多阶段构建如何减小镜像体积？镜像瘦身对部署速度的影响如何量化？",
  "Kubernetes 中 Deployment、Service、Ingress 三者的职责边界是什么？滚动更新的 maxSurge 和 maxUnavailable 如何影响发布节奏？",
  "CI/CD 流水线中应该设置哪些质量门禁？请说明单元测试覆盖率、静态扫描、安全扫描在流水线中的拦截策略。",
  "转行做 Java 后端，简历上的项目经历如何包装才能通过简历筛选又经得起面试深挖？请给出一条可执行的原则。",
  "面试中被问到不会的问题时，什么回应方式既诚实又能展示学习能力？请给出话术框架。",
  "谈谈你对技术选型的理解：什么时候应该引入新框架，什么时候应该坚持现有方案？请给出评估维度清单。",
  "从测试工程师转 Java 后端开发，你的测试背景会是优势还是劣势？如何在面试中把测试背景转化为质量意识的加分项？"
];

function sendQuestion(message, index) {
  return new Promise((resolve) => {
    const sessionId = `test_eval_${Date.now()}_${index}`;
    const postData = JSON.stringify({
      sessionId,
      message,
      userId: 1
    });

    const req = http.request('http://localhost:8080/api/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(postData)
      },
      timeout: 60000
    }, (res) => {
      let raw = '';
      res.on('data', chunk => raw += chunk);
      res.on('end', () => {
        // 解析 SSE 产生的 cards, messages
        const events = [];
        const lines = raw.split('\n');
        let currentEvent = 'message';
        let currentData = '';
        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.substring(6).trim();
          } else if (line.startsWith('data:')) {
            currentData = line.substring(5).trim();
            try {
              events.push({ event: currentEvent, data: JSON.parse(currentData) });
            } catch (e) {
              events.push({ event: currentEvent, data: currentData });
            }
          }
        }
        
        let fullText = '';
        const cards = [];
        for (const ev of events) {
          if (ev.event === 'message' && ev.data?.delta) {
            fullText += ev.data.delta;
          } else if (ev.event === 'card') {
            cards.push(ev.data?.type);
          }
        }

        // 质量与合理性判定
        const isRefusal = fullText.includes('无法回答') || fullText.includes('降级模式');
        const isOverBrief = fullText.length < 50 && cards.length === 0;
        const isOffTopic = fullText.includes('体验完整教学闭环') || (fullText.includes('诊断学情') && !message.includes('诊断'));
        const isGood = fullText.length >= 80 && !isRefusal && !isOffTopic;

        resolve({
          index: index + 1,
          question: message.slice(0, 30) + '...',
          statusCode: res.statusCode,
          textLength: fullText.length,
          preview: fullText.slice(0, 80).replace(/\n/g, ' '),
          cards,
          isGood,
          isRefusal,
          isOffTopic
        });
      });
    });

    req.on('error', (e) => {
      resolve({
        index: index + 1,
        question: message.slice(0, 30) + '...',
        error: e.message,
        isGood: false
      });
    });

    req.write(postData);
    req.end();
  });
}

async function runAll() {
  console.log(`=======================================================`);
  console.log(`开始执行 50 道职教高难度综合题大模型测试评测...`);
  console.log(`=======================================================\n`);

  let successCount = 0;
  const results = [];

  const BATCH_SIZE = 2;
  for (let i = 0; i < QUESTIONS.length; i += BATCH_SIZE) {
    const batch = QUESTIONS.slice(i, i + BATCH_SIZE).map((q, idx) => sendQuestion(q, i + idx));
    const batchRes = await Promise.all(batch);
    for (const r of batchRes) {
      results.push(r);
      if (r.isGood) successCount++;
      const tag = r.isGood ? '✅ 合理且专业' : '❌ 异常/答非所问';
      console.log(`[#${r.index}/50] ${tag} | 长度: ${r.textLength || 0}字 | 问: ${r.question}`);
      if (!r.isGood) {
        console.log(`    ⚠️ 诊断详情: ${r.preview || r.error}`);
      }
    }
  }

  console.log(`\n=======================================================`);
  console.log(`评测完成：成功率 ${successCount} / ${QUESTIONS.length} (${(successCount / 50 * 100).toFixed(1)}%)`);
  console.log(`=======================================================`);
}

runAll();
