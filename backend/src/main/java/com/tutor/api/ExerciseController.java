package com.tutor.api;

import com.tutor.agent.GradingAgent;
import com.tutor.api.dto.SubmitRequest;
import com.tutor.common.web.Result;
import com.tutor.common.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 提交答案（契约 1.5）：批改结果随响应返回（不走 SSE），exerciseId 幂等；
 * 前端拿 grade 本地渲染卡片，duplicated=true 时不重复渲染。
 */
@RestController
@RequestMapping("/api/exercise")
@RequiredArgsConstructor
public class ExerciseController {

    private final GradingAgent gradingAgent;
    private final com.tutor.client.QuestionBankClient questionBank;
    private final com.tutor.exercise.VariantQuestionService variantQuestionService;
    private final com.tutor.exercise.ExerciseService exerciseService;
    private final com.tutor.conversation.MessageService messageService;
    private final com.tutor.conversation.ConversationService conversationService;
    private final com.tutor.learning.SessionStateService stateService;

    @PostMapping("/submit")
    public Result<SubmitRequest.SubmitResponse> submit(@RequestBody SubmitRequest req) {
        Map<String, String> answersById = req.answers() == null ? Map.of()
                : req.answers().stream().collect(Collectors.toMap(
                        SubmitRequest.Answer::id, a -> a.answer() == null ? "" : a.answer()));
        var outcome = gradingAgent.submit(req.sessionId(), UserContext.getUser(),
                req.exerciseId(), answersById);
        return Result.ok(new SubmitRequest.SubmitResponse(outcome.grade(), outcome.duplicated()));
    }

    /**
     * 举一反三·同构变式题生成接口
     */
    @PostMapping("/variant")
    public Result<Map<String, Object>> variant(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", "");
        conversationService.requireOwned(sessionId, UserContext.getUser());

        String questionId = body.getOrDefault("questionId", "");
        String rawKp = body.get("kp");
        String errorType = body.getOrDefault("errorType", "技术概念混淆");
        String stem = body.getOrDefault("stem", "");

        com.tutor.client.QuestionBankClient.Question origin = questionBank.byId(questionId);
        String kp = (rawKp != null && !rawKp.isBlank()) ? rawKp : (origin != null ? origin.getKp() : null);
        if (kp == null || kp.isBlank()) {
            var slots = stateService.loadSlots(sessionId);
            if (slots != null && slots.knowledgePoint() != null) {
                kp = slots.knowledgePoint();
            } else {
                kp = "综合考点";
            }
        }
        if (origin == null) {
            origin = new com.tutor.client.QuestionBankClient.Question();
            origin.setId(questionId);
            origin.setKp(kp);
            origin.setStem(stem.isBlank() ? "原题技术考点：" + kp : stem);
            origin.setType("choice");
            origin.setScore(10);
        }

        com.tutor.client.QuestionBankClient.Question variantQ = variantQuestionService.generateVariant(origin, errorType);
        String exerciseId = exerciseService.register(sessionId, List.of(variantQ), List.of(variantQ.getKp()));

        java.util.Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("exerciseId", exerciseId);
        content.put("tip", "🔁【举一反三·变式巩固】针对刚才的技术易错考点「" + variantQ.getKp() + "」，试做这道同构变式题，提交后自动批改：");
        content.put("questions", List.of(java.util.Map.of(
                "id", variantQ.getId(),
                "type", variantQ.getType(),
                "stem", variantQ.getStem(),
                "options", variantQ.getOptions() != null ? variantQ.getOptions() : List.of()
        )));

        messageService.append(sessionId, "exercise", "assistant", content);
        return Result.ok(content);
    }
}
