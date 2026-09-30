package com.tutor.biz.tool;

import com.tutor.biz.model.BizModels;
import com.tutor.biz.service.QuestionBankService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 题库域工具（模拟考务业务系统题库服务）。
 * 安全设计：题库为课程付费权益，getQuestions/getAnswerKey 均按 (userId, courseId) 鉴权，
 * 未购课在工具层直接拒绝；getQuestions 不下发答案字段（防泄题），getAnswerKey 仅批改链路专用。
 * 消费者白名单：ExerciseAgent（组卷）、GradingAgent（答案与解析）。
 */
@Component
@RequiredArgsConstructor
public class QuestionTools {

    private final QuestionBankService questionBank;

    @Tool(description = "从指定课程的私有题库抽题（不含答案）。需用户已购买该课程，未购买将返回权限拒绝。组卷前必须调用本工具")
    public List<BizModels.QuestionItem> getQuestions(
            @ToolParam(description = "用户ID（用于购课权限校验）") Long userId,
            @ToolParam(description = "课程ID，如 c001。私有题库按课程隔离") String courseId,
            @ToolParam(description = "知识点过滤，如：集合框架。可为空", required = false) String knowledgePoint,
            @ToolParam(description = "题型：choice(选择题)/short(简答题)。可为空", required = false) String questionType,
            @ToolParam(description = "难度：easy/medium/hard。可为空", required = false) String difficulty,
            @ToolParam(description = "题数，1~5，默认 3", required = false) Integer count) {
        return questionBank.getQuestions(userId, courseId, knowledgePoint, questionType, difficulty, count);
    }

    @Tool(description = "按题目ID批量获取标准答案与评分要点（仅批改链路使用，禁止在讲解/出题时调用）。需用户已购对应课程")
    public List<BizModels.AnswerKey> getAnswerKey(
            @ToolParam(description = "用户ID（用于购课权限校验）") Long userId,
            @ToolParam(description = "题目ID列表") List<String> questionIds) {
        return questionBank.getAnswerKey(userId, questionIds);
    }
}
