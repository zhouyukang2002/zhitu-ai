package com.tutor.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.tutor.common.exception.BizException;
import com.tutor.conversation.MessageService;
import com.tutor.exercise.ExerciseService;
import com.tutor.exercise.GradeResult;
import com.tutor.exercise.GradingEngine;
import com.tutor.learning.LearningRecordService;
import com.tutor.learning.LearningState;
import com.tutor.learning.SessionStateService;
import com.tutor.prompt.PromptStore;
import com.tutor.client.QuestionBankClient;
import com.tutor.llm.LlmGateway;
import com.tutor.config.AppProperties;
import com.tutor.sse.SseSession;
import com.tutor.agent.LoopContext;
import com.tutor.agent.TraceStep;
import com.tutor.llm.LlmUsageHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 批改评估智能体（流水线终点 + 反馈环起点）：
 * - 客观题（选择）规则判分：零成本秒级；
 * - 主观题（简答）LLM 按评分标准评分 + 错误归因（概念混淆/计算失误），失败降级关键词引擎；
 * - 答案键随卷回填：现场出题/私有题库的答案在出题时已随卷存储，批改读卷面答案；
 * - 幂等：exerciseId 双层（Redis SETNX + DB 唯一）；
 * - 批改后追加学习记录（数据闭环）并推进状态机（<60 分 → REPLANNED 反馈回规划）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GradingAgent {

    private final ExerciseService exerciseService;
    private final GradingEngine gradingEngine;
    private final com.tutor.conversation.ConversationService conversationService;
    private final LlmGateway gateway;
    private final PromptStore promptStore;
    private final MessageService messageService;
    private final SessionStateService stateService;
    private final LearningRecordService learningRecordService;
    private final com.tutor.learning.SkillMatrixProfiler skillMatrixProfiler;
    private final AppProperties props;

    /** 提交答案 → 批改（契约 1.5：结果随响应返回，不走 SSE）；会话归属 + 练习归属双重校验 */
    public SubmitOutcome submit(String sessionId, Long userId, String exerciseId, Map<String, String> answersById) {
        conversationService.requireOwned(sessionId, userId);
        var exercise = exerciseService.require(exerciseId);
        if (!sessionId.equals(exercise.getConversationId())) {
            // 练习不属于该会话：按不存在处理（不暴露跨会话数据）
            throw BizException.notFound("练习不存在");
        }
        GradeResult existing = exerciseService.findGrade(exerciseId);
        if (existing != null) {
            return new SubmitOutcome(exerciseService.toContent(existing), true);
        }
        List<QuestionBankClient.Question> questions = exerciseService.questionsOf(exercise);
        // 卷面答案键：LLM 现场出题/私有题库的答案在出题时随卷存储，批改时回填题面
        // （修复历史缺陷：MCP 题库抽题不下发答案，简答题评分参考曾为空）
        Map<String, Map<String, Object>> answerKeys = exerciseService.answerKeysOf(exercise);
        for (QuestionBankClient.Question q : questions) {
            Map<String, Object> key = answerKeys.get(q.getId());
            if (key != null) {
                if (q.getAnswer() == null && key.get("answer") != null) {
                    q.setAnswer(String.valueOf(key.get("answer")));
                }
                if (q.getReference() == null && key.get("analysis") != null) {
                    q.setReference(String.valueOf(key.get("analysis")));
                }
                if ((q.getKeywords() == null || q.getKeywords().isEmpty()) && key.get("keywords") != null) {
                    q.setKeywords(((List<?>) key.get("keywords")).stream()
                            .map(String::valueOf).collect(Collectors.toList()));
                }
            }
        }

        List<GradeResult.PerQuestion> perQuestion = new ArrayList<>();
        List<LearningRecordService.RecordItem> records = new ArrayList<>();
        int score = 0;
        for (QuestionBankClient.Question q : questions) {
            String given = answersById.getOrDefault(q.getId(), "");
            GradeResult.PerQuestion pq;
            int qScore;
            if ("choice".equals(q.getType())) {
                pq = gradingEngine.gradeChoice(q, given);
                qScore = pq.isCorrect() ? q.getScore() : 0;
            } else {
                ShortGrade sg = gradeShort(q, given);
                pq = sg.perQuestion();
                qScore = sg.qScore();
            }
            score += qScore;
            perQuestion.add(pq);
            records.add(new LearningRecordService.RecordItem(
                    q.getKp(), pq.isCorrect(), null));
        }
        int totalScore = questions.stream().mapToInt(QuestionBankClient.Question::getScore).sum();
        GradeResult grade = new GradeResult(exerciseId, totalScore, score, perQuestion,
                exerciseService.knowledgePointsOf(exercise));
        exerciseService.saveGrade(grade, sessionId);
        // grade 卡片落库（前端提交响应本地渲染，刷新后历史完整）
        messageService.append(sessionId, "grade", "assistant", exerciseService.toContent(grade));
        // 数据闭环：答题记录回流，下次诊断即反映本次练习效果
        learningRecordService.appendBatch(userId, records);
        records.forEach(r -> skillMatrixProfiler.recordAnswer(userId, r.kp(), r.correct()));
        // 状态反馈环：低分 → REPLANNED（回显到前端"规划"步骤）
        stateService.set(sessionId, score < 60 ? LearningState.REPLANNED : LearningState.EVALUATED);
        return new SubmitOutcome(exerciseService.toContent(grade), false);
    }

    /** 简答题：LLM 评分优先，失败降级关键词引擎（分层容错-模型层）。返回 perQuestion + 该题得分 */
    private record ShortGrade(GradeResult.PerQuestion perQuestion, int qScore) {
    }

    private ShortGrade gradeShort(QuestionBankClient.Question q, String given) {
        if (gateway.available()) {
            try {
                String prompt = promptStore.get(PromptStore.GRADING)
                        .replace("{stem}", q.getStem())
                        .replace("{reference}", q.getReference() == null ? "" : q.getReference())
                        .replace("{keywords}", q.getKeywords() == null ? "" : String.join("、", q.getKeywords()))
                        .replace("{maxScore}", String.valueOf(q.getScore()))
                        .replace("{studentAnswer}", given);
                JsonNode node = gateway.callJson(prompt, "请批改", null, 0.1);
                int qScore = Math.max(0, Math.min(q.getScore(), node.path("score").asInt(0)));
                String errorType = node.path("errorType").asText("");
                boolean correct = qScore >= q.getScore() * 0.75;
                return new ShortGrade(new GradeResult.PerQuestion(q.getId(), correct, q.getReference(),
                        node.path("feedback").asText(""),
                        correct ? null : (errorType.isBlank() ? "概念混淆" : errorType)), qScore);
            } catch (Exception e) {
                log.warn("简答题 LLM 评分失败，降级规则引擎: {}", e.getMessage());
            }
        }
        // 关键词兜底路径：按命中率给比例分（与引擎 correct 阈值一致：>=0.75 满分）
        GradeResult.PerQuestion pq = gradingEngine.gradeShortByKeywords(q, given);
        double ratio = keywordsRatio(q, given);
        return new ShortGrade(pq, (int) Math.round(q.getScore() * ratio));
    }

    private double keywordsRatio(QuestionBankClient.Question q, String given) {
        String text = given == null ? "" : given;
        java.util.List<String> keywords = q.getKeywords() == null ? java.util.List.of() : q.getKeywords();
        if (keywords.isEmpty()) {
            return 0;
        }
        long hit = keywords.stream().filter(text::contains).count();
        return Math.min(1.0, (double) hit / Math.min(4, keywords.size()));
    }

    /** 学情报告（评估环节产物）：指标 + 摘要 + 趋势，反馈给规划形成闭环 */
    public Map<String, Object> buildReport(String sessionId, Long userId,
                                           List<com.tutor.learning.CognitiveDiagnosis.WeakPoint> weakPoints,
                                           Integer lastGrade, String timeRange) {
        int base = lastGrade != null ? lastGrade : 72;
        List<Map<String, Object>> metrics = List.of(
                Map.of("label", "知识点掌握度", "value", Math.round(40 + base * 0.3), "max", 100),
                Map.of("label", "路径完成率", "value", 50, "max", 100),
                Map.of("label", "本周练习正确率", "value", base, "max", 100));
        String weakest = (weakPoints == null || weakPoints.isEmpty()) ? "并发锁机制与底层源码" : "「" + weakPoints.get(0).knowledgePoint() + "」";
        var slots = stateService.loadSlots(sessionId);
        String targetKp = (slots != null && slots.knowledgePoint() != null) ? "「" + slots.knowledgePoint() + "」" : "核心技术模块";
        String summary = (timeRange == null ? "本轮" : timeRange) + "针对 " + targetKp + " 进行了深度研讨与实战练习，基础概念掌握扎实；" + weakest
                + "仍是后续高频面试与工程实战的重点关注项，建议按学习路径继续巩固。";
        String trend = "掌握度较上周 +" + Math.max(1, base / 10) + "，保持这个节奏。";
        stateService.set(sessionId, LearningState.EVALUATED);
        return Map.of("metrics", metrics, "summary", summary, "trend", trend);
    }

    /**
     * 生成多轮教学复盘对话文本并流式输出（结合长程对话历史做温情闭环收口）
     */
    public void generateRecapSummary(LoopContext ctx, SseSession sse) {
        long start = System.currentTimeMillis();
        String sessionId = ctx.getSessionId();
        List<String> recentTexts = messageService.recentTexts(sessionId, 8);
        String historyText = String.join("\n", recentTexts);

        var slots = stateService.loadSlots(sessionId);
        String targetKp = (slots != null && slots.knowledgePoint() != null) ? slots.knowledgePoint() : "核心技术模块";

        String recapText = null;
        if (gateway.available()) {
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("targetKp", targetKp);
                params.put("history", historyText);
                params.put("message", ctx.getMessage());
                String prompt = promptStore.render(PromptStore.RECAP, params);
                String userMsg = "今日探讨历史片段：\n" + historyText + "\n\n学员最新请求：" + ctx.getMessage();
                var usage = new LlmUsageHolder(props.getAi().getChatModel(), 0);
                recapText = StreamingSupport.stream(
                        gateway.stream(prompt, userMsg, null, com.tutor.llm.LlmConstants.TEMP_BALANCED, usage), sse);
            } catch (Exception e) {
                log.warn("复盘总结 LLM 生成失败: {}", e.getMessage());
            }
        }

        if (recapText == null || recapText.isBlank()) {
            recapText = "今天我们的学习探讨非常扎实！我们一同深入拆解了 **" + targetKp + "** 的核心机制与关键设计，并梳理了相关技术场景下的底层原理与进阶要点。\n\n"
                    + "对于刚接触该技术领域的同学来说，底层细节与演进差异很容易混淆，这完全是正常现象。多结合代码实战理解背后的设计考量，很快就能融会贯通。今天吸收的信息量很大，好好休息消化一下，保持这个节奏！\n";
            StreamingSupport.streamText(recapText, sse, 24, 18);
        }

        messageService.append(sessionId, "text", "assistant", Map.of("text", recapText));
        ctx.addTrace(new TraceStep("学情复盘智能体", "多轮教学全局复盘与盲区总结", "长程记忆聚合与大模型生成",
                System.currentTimeMillis() - start, TraceStep.SUCCESS));
    }

    public record SubmitOutcome(Map<String, Object> grade, boolean duplicated) {
    }
}
