package com.tutor.router;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.tutor.config.AppProperties;
import com.tutor.prompt.PromptStore;
import com.tutor.llm.LlmGateway;
import com.tutor.llm.LlmUsageHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 意图识别 Agent（主路径，对齐 diet-agent IntentAgentService 模式）：
 * 一次 LLM 调用识别意图 + 七维槽位。
 * - 槽位词典（slotOptions）注入提示词，LLM 只能从词典中选；
 * - 解析后 SlotExtractor.validate 后置校验，模型多写、写错的槽位一律丢弃；
 * - 失败/解析失败抛 LlmUnavailableException，由 IntentRouter 走关键词链式兜底；
 * - 轻量模型（tutor.ai.route-model），与主线模型拉开成本与延迟差距。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmIntentClassifier {

    private final LlmGateway gateway;
    private final PromptStore promptStore;
    private final SlotExtractor slotExtractor;
    private final AppProperties props;

    /**
     * 识别意图 + 七维槽位 + 环节计划（每次请求全量走此主路径）。
     * recentTurns 为最近对话（可能为空），用于消解"再来一组/那个呢"等指代表述。
     * 失败时抛异常，由 IntentRouter 兜底，保证流程不中断。
     */
    public IntentResult recognize(String message, LlmUsageHolder usage, java.util.List<String> recentTurns) {
        String system = promptStore.get(PromptStore.ROUTER);
        String user = buildUserPrompt(message, recentTurns);
        JsonNode node = gateway.callJson(system, user, props.getAi().getRouteModel(), 0.1, usage);

        String intentName = node.path("intent").asText("").toUpperCase(Locale.ROOT);
        double confidence = node.path("confidence").asDouble(0.5);

        Intent intent;
        try {
            intent = "OTHER".equalsIgnoreCase(intentName) ? Intent.CHITCHAT : Intent.valueOf(intentName);
        } catch (Exception e) {
            if (intentName.contains("TEACH") || intentName.contains("MATH") || intentName.contains("QUESTION") || intentName.contains("EXPLAIN") || intentName.contains("SOLVE")) {
                intent = Intent.TEACH;
            } else if (intentName.contains("PLAN")) {
                intent = Intent.PLAN;
            } else if (intentName.contains("DIAGNOS")) {
                intent = Intent.DIAGNOSE;
            } else if (intentName.contains("EXERCISE") || intentName.contains("TEST")) {
                intent = Intent.EXERCISE;
            } else {
                intent = Intent.TEACH;
            }
        }

        Slots slots = parseSlots(node.path("slots"));
        slots = slotExtractor.validate(slots);
        return new IntentResult(intent, slots, confidence, parseStages(node.path("stages")));
    }

    /** 环节计划解析：仅开放意图会输出，非法值丢弃（裁决在编排层） */
    private java.util.List<Stage> parseStages(JsonNode stagesNode) {
        if (stagesNode == null || !stagesNode.isArray() || stagesNode.isEmpty()) {
            return null;
        }
        java.util.List<Stage> stages = new java.util.ArrayList<>();
        for (JsonNode n : stagesNode) {
            Stage s = Stage.fromName(n.asText(""));
            if (s != null && !stages.contains(s)) {
                stages.add(s);
            }
        }
        return stages.isEmpty() ? null : stages;
    }

    /** 槽位解析：courseId 不信任 LLM 输出，由 courseName 词典归一后反查 */
    private Slots parseSlots(JsonNode slotsNode) {
        if (slotsNode == null || !slotsNode.isObject()) {
            return Slots.empty();
        }
        Integer count = slotsNode.path("questionCount").isInt()
                ? slotsNode.path("questionCount").asInt() : null;
        return new Slots(
                slotsNode.path("knowledgePoint").asText(null),
                slotsNode.path("courseName").asText(null),
                null,
                slotsNode.path("grade").asText(null),
                slotsNode.path("questionType").asText(null),
                count,
                slotsNode.path("timeRange").asText(null),
                slotsNode.path("difficulty").asText(null));
    }

    /** 用户提示词：原始消息 + 最近对话（指代消解）+ 七维槽位词典（LLM 只能从词典中选） */
    private String buildUserPrompt(String message, java.util.List<String> recentTurns) {
        Map<String, java.util.List<String>> options = slotExtractor.slotOptions();
        String history = recentTurns == null || recentTurns.isEmpty()
                ? "（无）"
                : String.join("\n", recentTurns);
        return "用户消息：" + message + "\n\n【最近对话】\n" + history + "\n\n【槽位词典】\n" + JSONUtil.toJsonStr(options);
    }
}
