package com.tutor.agent;

import com.tutor.sse.SseSession;
import com.tutor.conversation.MessageService;
import com.tutor.agent.LoopContext;
import com.tutor.agent.TraceStep;
import com.tutor.prompt.PromptStore;
import com.tutor.llm.LlmGateway;
import com.tutor.llm.LlmUsageHolder;
import com.tutor.observ.TraceRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 闲聊兜底智能体：低置信/寒暄请求的最终出口，保证对话始终有回复。
 * 带短期记忆（Redis 滑动窗口），多轮寒暄上下文连贯；
 * LLM 不可用时模板接管（全链路 Fallback 的最后一环）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChitchatAgent {

    public static final String AGENT = "闲聊智能体";

    private final LlmGateway gateway;
    private final PromptStore promptStore;
    private final MessageService messageService;
    private final TraceRecorder traceRecorder;
    private final com.tutor.config.AppProperties props;
    private final com.tutor.llm.MemoryCompactionService memoryCompaction;

    public void reply(LoopContext ctx, SseSession sse) {
        long start = System.currentTimeMillis();
        String text;
        String status;
        if (gateway.available()) {
            try {
                var usage = new LlmUsageHolder(props.getAi().getChatModel(), 0);
                // 超限摘要：滑动窗口逼近上限时压缩旧轮次，摘要注入 system，长闲聊不断语境
                String summary = memoryCompaction.compactIfNeeded(ctx.getSessionId());
                String system = promptStore.get(PromptStore.CHITCHAT)
                        + (summary.isBlank() ? "" : "\n\n【历史对话摘要】\n" + summary);
                text = StreamingSupport.stream(
                        gateway.streamWithMemory(system,
                                ctx.getMessage(), ctx.getSessionId(), null, 0.8, usage),
                        sse);
                status = TraceStep.SUCCESS;
                traceRecorder.generation(ctx.getTraceContext(), "LlmChat(chitchat)",
                        system, text, usage.toUsage(), status);
            } catch (Exception e) {
                log.warn("闲聊 LLM 失败，模板接管: {}", e.getMessage());
                text = StreamingSupport.streamText(template(ctx.getMessage()), sse, 24, 18);
                status = TraceStep.DEGRADED;
            }
        } else {
            text = StreamingSupport.streamText(template(ctx.getMessage()), sse, 24, 18);
            status = TraceStep.DEGRADED;
        }
        if (!text.isBlank()) {
            messageService.append(ctx.getSessionId(), "text", "assistant", java.util.Map.of("text", text));
        }
        ctx.addTrace(new TraceStep(AGENT, "兜底回复（带短期记忆）",
                gateway.available() ? "Redis 短期记忆 + LLM" : "规则模板接管",
                System.currentTimeMillis() - start, status));
    }

    private String template(String message) {
        return "收到～你说的是：\"" + message + "\"。\n\n当前是 **降级模式**（未配置 LLM），试试这些指令体验完整教学闭环：\n\n"
                + "- **\"帮我诊断一下学情\"** → 诊断 + 规划 + 讲解 + 练习全流程\n"
                + "- **\"再出一组练习题\"** → 只出题\n"
                + "- **\"看看我的学情报告\"** → 生成学情报告\n"
                + "- **\"推荐几门课程\"** → 课程推荐（旁路交易域）";
    }
}
