package com.tutor.agent;

import com.tutor.sse.SseSession;
import com.tutor.conversation.MessageService;
import com.tutor.knowledge.KnowledgeBase;
import com.tutor.learning.LearningState;
import com.tutor.agent.LoopContext;
import com.tutor.learning.SessionStateService;
import com.tutor.agent.TraceStep;
import com.tutor.config.AppProperties;
import com.tutor.prompt.PromptStore;
import com.tutor.tool.BizToolBridge;
import com.tutor.client.QuestionBankClient;
import com.tutor.llm.LlmGateway;
import com.tutor.llm.LlmUsageHolder;
import com.tutor.observ.TraceRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 内容讲解智能体：RAG-lite 检索（知识点+关键词打分）→ LLM 流式讲解（带引用溯源）。
 * 降级链：LLM 不可用/流式失败 → 直接下发知识库检索内容（纯文本仍可演示）。
 * 挂载 CourseTools（Function Calling 白名单）：LLM 可自主查询真实课程数据做衔接推荐。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeachingAgent {

    public static final String AGENT = "内容讲解智能体";

    private final KnowledgeBase knowledgeBase;
    private final LlmGateway gateway;
    private final PromptStore promptStore;
    private final MessageService messageService;
    private final SessionStateService stateService;
    private final BizToolBridge bizToolBridge;
    private final TraceRecorder traceRecorder;
    private final AppProperties props;
    private final com.tutor.memory.UserMemoryService userMemoryService;
    private final com.tutor.knowledge.QueryRewriter queryRewriter;
    private final com.tutor.llm.MemoryCompactionService memoryCompactionService;
    private final CriticValidator criticValidator;

    public void teach(LoopContext ctx, SseSession sse, String kp) {
        long start = System.currentTimeMillis();
        // Query Rewrite：多轮指代消解后再进混合检索，提升召回质量（失败原样返回）
        String searchQuery = queryRewriter.rewrite(ctx.getSessionId(), ctx.getMessage());
        boolean rewritten = !searchQuery.equals(ctx.getMessage());
        // 三段式 RAG：RRF 宽召回 → Cross-Encoder 重排 → 精取 rerankTopN 个知识块
        List<KnowledgeBase.Chunk> chunks = knowledgeBase.search(kp, searchQuery, props.getAi().getRerankTopN());
        String context = chunks.stream()
                .map(c -> "【" + c.getTitle() + "】\n" + c.getText())
                .collect(Collectors.joining("\n\n"));
        String memoryContext = userMemoryService.buildMemoryContext(ctx.getUserId(), ctx.getMessage());
        // 超限摘要：长会话的旧轮次摘要注入，讲解不失语境（无摘要为空串）
        String summary = memoryCompactionService.loadSummary(ctx.getSessionId());
        String fullContext = (!memoryContext.isBlank() ? memoryContext + "\n\n" : "")
                + (!summary.isBlank() ? "【历史对话摘要】\n" + summary + "\n\n" : "") + context;
        String citations = chunks.stream()
                .map(c -> c.getSource() == null ? c.getTitle() : c.getSource())
                .distinct()
                .collect(Collectors.joining("、"));

        // 异步提取用户潜在长期记忆（学段/薄弱点/升学目标）
        userMemoryService.extractAndSaveAsync(ctx.getUserId(), ctx.getMessage(), ctx.getSessionId());

        String text;
        String status;
        if (gateway.available()) {
            try {
                var slots = ctx.getDecision().slots();
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("kp", kp);
                params.put("message", ctx.getMessage());
                params.put("context", fullContext);
                params.put("citations", citations);
                params.put("grade", slots == null || slots.grade() == null ? "未指定" : slots.grade());
                params.put("difficulty", slots == null || slots.difficulty() == null ? "未指定" : slots.difficulty());
                String system = promptStore.render(PromptStore.TEACHING, params);
                var usage = new com.tutor.llm.LlmUsageHolder(props.getAi().getChatModel(), 0);
                text = StreamingSupport.stream(
                        gateway.stream(system, ctx.getMessage(), null, com.tutor.llm.LlmConstants.TEMP_BALANCED, usage,
                                bizToolBridge.getTools("TeachingAgent")), sse);
                status = TraceStep.SUCCESS;
                traceRecorder.generation(ctx.getTraceContext(), "LlmChat(teaching)",
                        system, text, usage.toUsage(), status);

                // Critic 质检把关：检查代码块闭包与语法对称性
                var criticCheck = criticValidator.validateExplanation(text);
                if (!criticCheck.passed()) {
                    log.warn("Critic 质检发现讲解代码存在语法瑕疵: {}", criticCheck.issues());
                    if (criticCheck.issues().contains("Markdown 代码块未闭合")) {
                        sse.delta("\n```\n");
                        text += "\n```\n";
                    }
                    ctx.addTrace(new TraceStep("Critic质检智能体", "发现未闭合代码标记，已执行安全闭合补偿", "代码语法AST质检器", 1, TraceStep.SUCCESS));
                }
            } catch (Exception e) {
                log.warn("LLM 讲解失败，降级为知识库直出: {}", e.getMessage());
                text = streamFallback(ctx, sse, kp, chunks, citations);
                status = TraceStep.DEGRADED;
            }
        } else {
            text = streamFallback(ctx, sse, kp, chunks, citations);
            status = TraceStep.DEGRADED;
        }
        // 中断/完成后持久化已流出内容（半截回复刷新后仍可见，体验自然）
        if (!text.isBlank()) {
            messageService.append(ctx.getSessionId(), "text", "assistant", Map.of("text", text));
        }
        stateService.set(ctx.getSessionId(), LearningState.LEARNING);
        ctx.addTrace(new TraceStep(AGENT, "按知识点检索并讲解",
                gateway.available() ? (bizToolBridge.isMcpMode()
                        ? "RAG 检索 + MCP 工具调用" : "RAG 检索（本地工具）") : "RAG 检索（降级直出）",
                System.currentTimeMillis() - start, status));
        if (rewritten) {
            // 查询改写作为独立轨迹步骤可见（面试讲点：检索前指代消解）
            ctx.getTrace().add(ctx.getTrace().size() - 1,
                    new TraceStep("查询改写器", "指代消解：" + searchQuery, "轻量 LLM Rewrite", 0, TraceStep.SUCCESS));
        }
    }

    private String streamFallback(LoopContext ctx, SseSession sse, String kp,
                                  List<KnowledgeBase.Chunk> chunks, String citations) {
        StringBuilder body = new StringBuilder();
        body.append("## ").append(kp).append("\n\n");
        if (chunks.isEmpty()) {
            body.append("知识库中暂未收录该知识点的讲解内容，我先按通用思路说明：建议先回顾定义与公式，再通过练习巩固。");
        } else {
            chunks.forEach(c -> body.append("### ").append(c.getTitle()).append("\n\n")
                    .append(c.getText()).append("\n\n"));
        }
        body.append("---\n\n*参考来源：").append(citations).append("*\n\n");
        body.append("理解之后，要不要做一组小练习检验一下？");
        return StreamingSupport.streamText(body.toString(), sse, 24, 18);
    }
}
