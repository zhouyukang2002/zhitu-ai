package com.tutor.pipeline;

import com.tutor.agent.ChitchatAgent;
import com.tutor.agent.ClarifyAgent;
import com.tutor.agent.CourseBuyAgent;
import com.tutor.agent.CourseRecommendAgent;
import com.tutor.agent.DiagnosisAgent;
import com.tutor.agent.ExerciseAgent;
import com.tutor.agent.GradingAgent;
import com.tutor.agent.PlanAgent;
import com.tutor.agent.TeachingAgent;
import com.tutor.sse.SseSession;
import com.tutor.agent.LoopContext;
import com.tutor.agent.TraceStep;
import com.tutor.common.context.UserContext;
import com.tutor.learning.LearningState;
import com.tutor.learning.SessionStateService;
import com.tutor.observ.TraceContext;
import com.tutor.observ.TraceContextHolder;
import com.tutor.observ.TraceRecorder;
import com.tutor.conversation.ConversationService;
import com.tutor.conversation.MessageService;
import com.tutor.conversation.entity.ConversationEntity;
import com.tutor.exercise.ExerciseService;
import com.tutor.learning.CognitiveDiagnosis;
import com.tutor.learning.DiagnosisPlanStore;
import com.tutor.router.Intent;
import com.tutor.router.IntentRouter;
import com.tutor.router.RouteDecision;
import com.tutor.router.Stage;
import com.tutor.agent.StreamingSupport;
import com.tutor.common.constant.SseCardType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 对话流水线编排（应用层）：
 * 落用户消息 → 三级意图路由 → 按意图分发场景 → 智能体产出卡片/流式文本 → 轨迹卡 → done。
 *
 * 场景分发（意图 → 编排）：
 * - 学习域意图共用完整闭环流水线（诊断→规划→讲解→练习），串行依赖；
 * - 交易域意图走旁路星型（推荐/购买），不触碰教学状态机；
 * - 闲聊兜底。任一环节失败不中断整轮（finally 里必发 done，前端解锁输入框）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatPipelineRunner {

    private final TraceRecorder traceRecorder;
    private final ConversationService conversationService;
    private final MessageService messageService;
    private final IntentRouter intentRouter;
    private final SessionStateService stateService;
    private final DiagnosisPlanStore diagnosisPlanStore;
    private final ExerciseService exerciseService;
    private final DiagnosisAgent diagnosisAgent;
    private final PlanAgent planAgent;
    private final TeachingAgent teachingAgent;
    private final ExerciseAgent exerciseAgent;
    private final GradingAgent gradingAgent;
    private final CourseRecommendAgent courseRecommendAgent;
    private final CourseBuyAgent courseBuyAgent;
    private final ChitchatAgent chitchatAgent;
    private final ClarifyAgent clarifyAgent;
    private final com.tutor.learning.HumanHandoffService humanHandoffService;
    private final com.tutor.security.SafetyGuardService safetyGuardService;
    private final com.tutor.learning.SkillMatrixProfiler skillMatrixProfiler;
    private final com.tutor.knowledge.SemanticCacheService semanticCacheService;

    /** 会话级锁（对齐 diet-agent Orchestrator 模式）：同会话并发请求串行化，防状态机/消息写入竞态 */
    private final Map<String, Object> sessionLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public void run(ChatRequest req, Long contextUser, SseSession sse) {
        // 用户识别优先级：服务端上下文（请求线程解析传入，登录后即真实身份）> 请求体 userId（仅开发期兜底）> 默认 1
        Long userId = contextUser != null ? contextUser
                : (req.userId() != null ? req.userId() : UserContext.DEFAULT_USER_ID);
        String message = req.message() == null ? "" : req.message().trim();
        try {
            if (message.isEmpty()) {
                sse.error("消息不能为空");
                sse.done(Map.of());
                return;
            }
            ConversationEntity conversation = conversationService.getOrCreate(req.sessionId(), userId);
            // 同会话串行：状态机推进/槽位合并/消息落库都以会话为单位线性执行（跨会话不受影响）
            Object lock = sessionLocks.computeIfAbsent(conversation.getId(), key -> new Object());
            synchronized (lock) {
                handleTurn(req, userId, message, conversation, sse);
            }
        } catch (Exception e) {
            log.error("流水线执行失败: sessionId={}", req.sessionId(), e);
            sse.error("处理失败：" + e.getMessage());
            sse.done(Map.of());
        } finally {
            sse.close();
        }
    }

    private void handleTurn(ChatRequest req, Long userId, String message,
                            ConversationEntity conversation, SseSession sse) throws Exception {
            // 用户消息先落库 + 首条消息生成标题（Mock 同款）
            messageService.append(conversation.getId(), "text", "user", Map.of("text", message));
            conversationService.touchAfterUserMessage(conversation, message);

            // 全链路可观测：本轮 Trace 上下文（close 时异步落库，观测不阻塞业务）
            TraceContext traceContext = traceRecorder.open(conversation.getId(), userId, message);
            TraceContextHolder.setTraceId(traceContext.getTraceId());
            try {
                // 🛡️ [AI安全网关] 前置检测 Prompt 越狱注入与合规敏感词
                var safetyResult = safetyGuardService.check(message);
                if (!safetyResult.safe()) {
                    com.tutor.agent.StreamingSupport.streamText(safetyResult.safeReply(), sse, 24, 18);
                    messageService.append(conversation.getId(), "text", "assistant", Map.of("text", safetyResult.safeReply()));
                    traceRecorder.span(traceContext, "AI安全网关", "Prompt防注入/合规拦截: " + safetyResult.blockType(), 1, "blocked");
                    sse.card(SseCardType.AGENT_TRACE, Map.of("steps", List.of(
                            new TraceStep("AI安全网关", "安全风控拦截 (" + safetyResult.blockType() + ")", "规则与特征防御引擎", 1, "blocked")
                    )));
                    traceRecorder.close(traceContext);
                    sse.done(Map.of("traceId", traceContext.getTraceId()));
                    return;
                }

                // 路由：三级意图识别 + 槽位提取（携带最近诊断薄弱点做槽位矫正）
                List<CognitiveDiagnosis.WeakPoint> latestWeakPoints =
                        diagnosisPlanStore.latestWeakPoints(conversation.getId());
                long routeStart = System.currentTimeMillis();
                RouteDecision decision = intentRouter.route(conversation.getId(), message,
                        stateService.get(conversation.getId()), latestWeakPoints, traceContext);
                traceContext.setActualSlots(com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                        .writeValueAsString(decision.slots()));
                long routeCost = System.currentTimeMillis() - routeStart;

                LoopContext ctx = new LoopContext(conversation.getId(), userId, message,
                        decision, stateService.get(conversation.getId()), latestWeakPoints);
                ctx.setTraceContext(traceContext);
                // 轨迹第一步：路由智能体（真实耗时与降级状态）
                ctx.addTrace(new TraceStep("路由智能体", "意图识别（6类意图+七维槽位）",
                        routeTool(decision.source()), routeCost,
                        decision.degraded() ? TraceStep.DEGRADED : TraceStep.SUCCESS));

                dispatch(ctx, sse);
                // 转人工检测：连续多轮降级/失败 → system 卡片告知学生（成功轮次自动清零计数）
                if (humanHandoffService.recordRound(conversation.getId(), statusOf(ctx.getTrace()))) {
                    Map<String, Object> handoff = Map.of(
                            "text", "检测到连续多轮服务异常，已为您转接人工老师，请注意查收回复。AI 助教会同时继续跟进您的问题。",
                            "state", "HUMAN_HANDOFF");
                    messageService.append(conversation.getId(), "system", "assistant", handoff);
                    sse.card(SseCardType.SYSTEM, handoff);
                    ctx.addTrace(new TraceStep("系统守护", "连续降级检测 → 转人工", "Redis 连败计数器", 0, TraceStep.SUCCESS));
                }
                // 执行轨迹（真实耗时/真实状态）最后统一下发：一张卡片可回放整轮链路
                sse.card(SseCardType.AGENT_TRACE, Map.of("steps", ctx.getTrace()));
                // 关闭 Trace 并异步落库（SPAN=轨迹步骤，GENERATION=LLM 调用）
                for (TraceStep step : ctx.getTrace()) {
                    traceRecorder.span(ctx.getTraceContext(), step.agent(), step.tool(), step.costMs(), step.status());
                }
                ctx.getTraceContext().setStatus(decision.intent().name(), decision.source(), statusOf(ctx.getTrace()));
                traceRecorder.close(ctx.getTraceContext());
                sse.done(Map.of("traceId", traceContext.getTraceId()));
            } finally {
                TraceContextHolder.clear();
            }
    }

    /** 路由来源 → 轨迹工具描述（面试讲点：三级链路可视化） */
    /** 轮次状态：任一失败 → failed；任一降级 → degraded；否则 success */
    private String statusOf(List<TraceStep> steps) {
        boolean degraded = false;
        for (TraceStep step : steps) {
            if (TraceStep.FAILED.equals(step.status())) return TraceStep.FAILED;
            if (TraceStep.DEGRADED.equals(step.status())) degraded = true;
        }
        return degraded ? TraceStep.DEGRADED : TraceStep.SUCCESS;
    }

    private String routeTool(String source) {
        return switch (source) {
            case "L2_LLM" -> "轻量 LLM 意图识别";
            case "STATE_CORRECTION" -> "意图状态矫正（兜底一）";
            case "LOW_CONFIDENCE_FALLBACK" -> "低置信降级 → 澄清追问（兜底二）";
            case "KEYWORD_FALLBACK" -> "关键词保守推断（兜底三）";
            default -> source;
        };
    }

    /**
     * 按路由产出的执行计划分发（stages 已过裁决：允许集交集/保守默认/封闭意图查表）。
     * 环节间串行依赖经 LoopContext 结构化传递（诊断薄弱点 → 规划 → 讲解/出题），
     * 任一环节失败不中断后续。
     */
    private void dispatch(LoopContext ctx, SseSession sse) {
        List<Stage> stages = new java.util.ArrayList<>(ctx.getDecision().stages());
        List<CognitiveDiagnosis.WeakPoint> weakPoints = ctx.getLatestWeakPoints();

        // 1. 前置诊断与动态分支（Dynamic State Branching）
        if (stages.contains(Stage.DIAGNOSE)) {
            if (diagnosisAgent.needsPlacement(ctx.getUserId())) {
                exerciseAgent.generatePlacement(ctx, sse);
            } else {
                weakPoints = doDiagnose(ctx, sse);
                // 动态状态图分支裁决：依据诊断薄弱度自适应调控后续出题与辅导策略
                if (weakPoints != null && !weakPoints.isEmpty()) {
                    double avgScore = weakPoints.stream()
                            .mapToDouble(CognitiveDiagnosis.WeakPoint::score)
                            .average().orElse(70.0);
                    if (avgScore < 60.0) {
                        ctx.addTrace(new TraceStep("自适应分支", "评估掌握度较弱 (<60)，动态切入【基础筑基】教学流", "动态状态图引擎", 1, TraceStep.SUCCESS));
                    } else if (avgScore >= 85.0) {
                        ctx.addTrace(new TraceStep("自适应分支", "评估掌握度优异 (>=85)，动态升级至【大厂面试冲刺】", "动态状态图引擎", 1, TraceStep.SUCCESS));
                    }
                }
            }
            stages.remove(Stage.DIAGNOSE);
        }

        if (sse.isClosed()) return;

        // 2. DAG 并发组调度（消除无数据强依赖的串行阻塞，大幅缩短等待延迟）
        final String tid = ctx.getTraceContext() != null ? ctx.getTraceContext().getTraceId() : null;

        // 并行组 A: PLAN + RECOMMEND（规划生成与课程召回无依赖，双路并行）
        if (stages.contains(Stage.PLAN) && stages.contains(Stage.RECOMMEND)) {
            final var finalWp = weakPoints;
            long pStart = System.currentTimeMillis();
            var planFuture = runAsyncWithTrace(tid, () -> doPlan(ctx, sse, finalWp));
            var recFuture = runAsyncWithTrace(tid, () -> courseRecommendAgent.recommend(ctx, sse, finalWp));
            CompletableFuture.allOf(planFuture, recFuture).join();
            ctx.addTrace(new TraceStep("DAG并行调度", "【学习规划 + 课程推荐】双路异步并发执行完成", "Java CompletableFuture", System.currentTimeMillis() - pStart, TraceStep.SUCCESS));
            stages.remove(Stage.PLAN);
            stages.remove(Stage.RECOMMEND);
        }

        // 并行组 B: TEACH + EXERCISE（知识讲解与配套习题拉取无依赖，双路并行）
        // 前提：PLAN 不在本次 stages——练习必须晚于学习计划产出，否则并行完成后
        // 剩余循环里的 PLAN 再写 PLANNED 会把状态机从 PRACTICING 回退（e2e 回归实证）。
        if (!stages.contains(Stage.PLAN) && stages.contains(Stage.TEACH) && stages.contains(Stage.EXERCISE)) {
            final var finalWp = weakPoints;
            long pStart = System.currentTimeMillis();
            var teachFuture = runAsyncWithTrace(tid, () -> doTeach(ctx, sse, finalWp));
            var exFuture = runAsyncWithTrace(tid, () -> doExercise(ctx, sse, finalWp));
            CompletableFuture.allOf(teachFuture, exFuture).join();
            // 状态跃迁终态裁决：TEACH 写 LEARNING 与 EXERCISE 写 PRACTICING 并发竞态，
            // 由编排层在并行完成后统一裁决最终态（对齐"会话流转由编排层统一管控"）
            stateService.set(ctx.getSessionId(), LearningState.PRACTICING);
            ctx.addTrace(new TraceStep("DAG并行调度", "【知识讲解 + 练习出题】双路异步并发执行完成", "Java CompletableFuture", System.currentTimeMillis() - pStart, TraceStep.SUCCESS));
            stages.remove(Stage.TEACH);
            stages.remove(Stage.EXERCISE);
        }

        // 并行组 C: RECOMMEND + EXERCISE（课程推荐与配套练习无依赖，双路并行；同样以无 PLAN 为前提）
        if (!stages.contains(Stage.PLAN) && stages.contains(Stage.RECOMMEND) && stages.contains(Stage.EXERCISE)) {
            final var finalWp = weakPoints;
            long pStart = System.currentTimeMillis();
            var recFuture = runAsyncWithTrace(tid, () -> courseRecommendAgent.recommend(ctx, sse, finalWp));
            var exFuture = runAsyncWithTrace(tid, () -> doExercise(ctx, sse, finalWp));
            CompletableFuture.allOf(recFuture, exFuture).join();
            ctx.addTrace(new TraceStep("DAG并行调度", "【课程推荐 + 练习出题】双路异步并发执行完成", "Java CompletableFuture", System.currentTimeMillis() - pStart, TraceStep.SUCCESS));
            stages.remove(Stage.RECOMMEND);
            stages.remove(Stage.EXERCISE);
        }

        // 3. 剩余常规 Stage 顺序执行
        for (Stage stage : stages) {
            if (sse.isClosed()) {
                return;
            }
            switch (stage) {
                case PLAN -> doPlan(ctx, sse, weakPoints);
                case TEACH -> doTeach(ctx, sse, weakPoints);
                case EXERCISE -> doExercise(ctx, sse, weakPoints);
                case REPORT -> reportScenario(ctx, sse);
                case RECOMMEND -> courseRecommendAgent.recommend(ctx, sse, weakPoints);
                case BUY -> courseBuyAgent.buy(ctx, sse, ctx.getDecision().slots().courseName());
                case CLARIFY -> clarifyAgent.clarify(ctx, sse);
                case CHITCHAT -> chitchatAgent.reply(ctx, sse);
            }
        }

        // 聚焦诊断后的引导：建议出题检验而非强塞练习卡
        if (ctx.getDecision().intent() == Intent.DIAGNOSE
                && !stages.contains(Stage.EXERCISE) && !sse.isClosed()) {
            String tip = "诊断完成。要不要我出几道题检验一下学习效果？";
            StreamingSupport.streamText(tip, sse, 24, 18);
            messageService.append(ctx.getSessionId(), "text", "assistant", Map.of("text", tip));
        }
    }

    /** 环节① 学情诊断：返回最新薄弱点（失败沿用带入值继续） */
    private List<CognitiveDiagnosis.WeakPoint> doDiagnose(LoopContext ctx, SseSession sse) {
        List<CognitiveDiagnosis.WeakPoint> weakPoints = ctx.getLatestWeakPoints();
        try {
            Map<String, Object> diagnosis = diagnosisAgent.diagnose(ctx);
            weakPoints = diagnosisAgent.weakPointsOf(diagnosis);
            messageService.append(ctx.getSessionId(), "diagnosis", "assistant", diagnosis);
            sse.card(SseCardType.DIAGNOSIS, diagnosis);
        } catch (Exception e) {
            log.warn("诊断环节失败，沿用最近诊断继续: {}", e.getMessage());
        }
        return weakPoints;
    }

    /** 环节② 学习规划：输出定制学习计划卡片（失败跳过不中断） */
    private void doPlan(LoopContext ctx, SseSession sse, List<CognitiveDiagnosis.WeakPoint> weakPoints) {
        try {
            Map<String, Object> plan = planAgent.plan(ctx, weakPoints);
            messageService.append(ctx.getSessionId(), "plan", "assistant", plan);
            sse.card(SseCardType.PLAN, plan);
        } catch (Exception e) {
            log.warn("规划环节失败，跳过: {}", e.getMessage());
        }
    }

    /** 环节③ 流式讲解：槽位知识点 > 最薄弱点 > 用户原话 */
    private void doTeach(LoopContext ctx, SseSession sse, List<CognitiveDiagnosis.WeakPoint> weakPoints) {
        // 语义缓存检查（优化点 2：高频问题毫秒级闪电直出与 0 Token 成本）
        var cached = semanticCacheService.get(ctx.getMessage());
        if (cached.isPresent()) {
            String ans = cached.get();
            com.tutor.agent.StreamingSupport.streamText(ans, sse, 24, 18);
            messageService.append(ctx.getSessionId(), "text", "assistant", Map.of("text", ans));
            ctx.addTrace(new TraceStep("语义缓存中枢", "高频查询命中语义缓存（20ms，0 Token）", "Redis 向量语义匹配 (sim>=0.95)", 20, TraceStep.SUCCESS));
            return;
        }

        String kp = ctx.getDecision().kpSlot() != null
                ? ctx.getDecision().kpSlot()
                : (!weakPoints.isEmpty() ? weakPoints.get(0).knowledgePoint() : ctx.getMessage());
        teachingAgent.teach(ctx, sse, kp);
    }

    /** 环节④ 出题检验：LLM 现场出题/私有题库组卷，成功后 system 卡片推进状态机；失败（道歉已流式）跳过 */
    private void doExercise(LoopContext ctx, SseSession sse, List<CognitiveDiagnosis.WeakPoint> weakPoints) {
        Map<String, Object> content = exerciseAgent.compose(ctx, weakPoints, sse);
        if (content == null) {
            return;
        }
        Map<String, Object> system = Map.of("text", "已进入练习环节", "state", "PRACTICING");
        messageService.append(ctx.getSessionId(), "system", "assistant", system);
        sse.card(SseCardType.SYSTEM, system);
        sse.card(SseCardType.EXERCISE, content);
    }

    /** 学情报告场景：多轮复盘流式总结 + 评估产物 + system 卡片推进状态机 */
    private void reportScenario(LoopContext ctx, SseSession sse) {
        try {
            // 1. 先进行多轮长对话的自然语言复盘总结与温情寄语（流式吐字并落库）
            gradingAgent.generateRecapSummary(ctx, sse);

            // 2. 下发学情报告卡片
            Integer lastGrade = exerciseService.findLatestScore(ctx.getSessionId());
            Map<String, Object> report = gradingAgent.buildReport(
                    ctx.getSessionId(), ctx.getUserId(), ctx.getLatestWeakPoints(), lastGrade,
                    ctx.getDecision().slots().timeRange());
            messageService.append(ctx.getSessionId(), "report", "assistant", report);
            sse.card(SseCardType.REPORT, report);

            // 3. 推进状态机至 EVALUATED
            Map<String, Object> system = Map.of("text", "学情报告已生成", "state", "EVALUATED");
            messageService.append(ctx.getSessionId(), "system", "assistant", system);
            sse.card(SseCardType.SYSTEM, system);
        } catch (Exception e) {
            log.warn("报告生成失败: {}", e.getMessage());
            sse.error("报告生成失败，请稍后重试");
        }
    }

    /**
     * 包装异步任务，并在子线程中传播与清理 Trace 上下文
     */
    private CompletableFuture<Void> runAsyncWithTrace(String traceId, Runnable action) {
        return CompletableFuture.runAsync(() -> {
            TraceContextHolder.setTraceId(traceId);
            try {
                action.run();
            } finally {
                TraceContextHolder.clear();
            }
        });
    }
}
