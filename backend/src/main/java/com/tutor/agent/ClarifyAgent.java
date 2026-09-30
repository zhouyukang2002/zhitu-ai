package com.tutor.agent;

import com.tutor.sse.SseSession;
import com.tutor.conversation.MessageService;
import com.tutor.knowledge.SkillDictionary;
import com.tutor.agent.LoopContext;
import com.tutor.agent.TraceStep;
import com.tutor.prompt.PromptStore;
import com.tutor.llm.LlmGateway;
import com.tutor.llm.LlmUsageHolder;
import com.tutor.observ.TraceRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 澄清智能体（信息不足需澄清）：槽位缺失/低置信时先追问再执行，
 * 避免"该澄清追问的却直接去推荐"的低质量路由（对齐 diet-agent ClarifyAgent 模式）。
 * 澄清不推进教学状态机——用户补充信息后由下一轮正常意图识别接管。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClarifyAgent {

    public static final String AGENT = "澄清智能体";

    /** 追问时展示的知识点候选数量上限 */
    private static final int MAX_OPTIONS = 8;

    /** 预置跨技术栈主流代表方向（避免字母序机械截取导致出现 Actuator/Bean 等生僻点） */
    private static final java.util.List<String> CURATED_OPTIONS = java.util.List.of(
            "Python 基础与数据分析",
            "Java 核心与后端开发",
            "大模型与 AI 应用开发",
            "MySQL 数据库与 SQL",
            "Web 前端开发 (Vue/React)",
            "Redis 缓存与高并发",
            "Spring Boot 企业级框架",
            "数据结构与算法"
    );

    private final SkillDictionary skillDictionary;
    private final LlmGateway gateway;
    private final PromptStore promptStore;
    private final MessageService messageService;
    private final TraceRecorder traceRecorder;
    private final com.tutor.config.AppProperties props;

    public void clarify(LoopContext ctx, SseSession sse) {
        long start = System.currentTimeMillis();
        String options = resolveOptions(ctx);
        String text;
        String status;
        if (gateway.available()) {
            try {
                String system = promptStore.get(PromptStore.CLARIFY)
                        .replace("{options}", options)
                        .replace("{message}", ctx.getMessage() != null ? ctx.getMessage() : "");
                var usage = new LlmUsageHolder(props.getAi().getChatModel(), 0);
                text = StreamingSupport.stream(
                        gateway.stream(system, ctx.getMessage(), null, 0.5, usage), sse);
                traceRecorder.generation(ctx.getTraceContext(), "LlmChat(clarify)",
                        system, text, usage.toUsage(), status = TraceStep.SUCCESS);
                status = TraceStep.SUCCESS;
            } catch (Exception e) {
                log.warn("澄清 LLM 失败，模板接管: {}", e.getMessage());
                text = StreamingSupport.streamText(template(options), sse, 24, 18);
                status = TraceStep.DEGRADED;
            }
        } else {
            text = StreamingSupport.streamText(template(options), sse, 24, 18);
            status = TraceStep.DEGRADED;
        }
        if (!text.isBlank()) {
            messageService.append(ctx.getSessionId(), "text", "assistant", Map.of("text", text));
        }
        ctx.addTrace(new TraceStep(AGENT, "槽位不足，追问补充信息",
            gateway.available() ? "词典候选注入 + LLM 追问" : "规则模板追问",
                System.currentTimeMillis() - start, status));
    }

    private String resolveOptions(LoopContext ctx) {
        // 1. 若多轮历史或当前消息有特定技术栈线索，优先召回该领域知识点
        String textToInspect = ctx.getMessage() != null ? ctx.getMessage() : "";
        String matchedKp = skillDictionary.findKnowledgePointInText(textToInspect);
        if (matchedKp == null && ctx.getSessionId() != null) {
            try {
                var userHistory = messageService.recentUserTexts(ctx.getSessionId(), 5);
                for (int i = userHistory.size() - 1; i >= 0; i--) {
                    matchedKp = skillDictionary.findKnowledgePointInText(userHistory.get(i));
                    if (matchedKp != null) break;
                }
            } catch (Exception ignored) {
            }
        }
        if (matchedKp != null) {
            String domain = matchedKp.split(" ")[0].toLowerCase();
            var domainKps = skillDictionary.allNames().stream()
                    .filter(k -> k.toLowerCase().contains(domain))
                    .limit(MAX_OPTIONS)
                    .toList();
            if (domainKps.size() >= 3) {
                return String.join("、", domainKps);
            }
        }
        // 2. 泛问回退主流代表方向
        return String.join("、", CURATED_OPTIONS);
    }

    private String template(String options) {
        return "为了给你更精准的帮助，想先确认一下：你想从哪个知识点开始？\n\n"
                + "目前可以选择：" + options + " 等。\n\n"
                + "也可以直接告诉我你的学段和难度偏好～";
    }
}
