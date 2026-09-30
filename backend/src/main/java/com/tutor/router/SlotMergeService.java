package com.tutor.router;

import org.springframework.stereotype.Component;

/**
 * 槽位跨轮合并（对齐 diet-agent SlotMergeService 模式）：
 * 多轮对话中用户的约束是增量表达的——"我想学 Java" → 下一句"偏后端" → 再下一句"出五道题"，
 * 本轮识别出的槽位为空时不应丢失历史轮次已经确定的约束。
 * 合并语义：本轮非空覆盖历史，本轮为空沿用历史（per-slot）。
 */
@Component
public class SlotMergeService {

    /** 合并历史槽位与本轮槽位：本轮非空的维度覆盖，为空的维度沿用历史 */
    public Slots merge(Slots history, Slots current) {
        Slots safeCurrent = current == null ? Slots.empty() : current;
        Slots safeHistory = history == null ? Slots.empty() : history;
        return new Slots(
                firstNonNull(safeCurrent.knowledgePoint(), safeHistory.knowledgePoint()),
                firstNonNull(safeCurrent.courseName(), safeHistory.courseName()),
                firstNonNull(safeCurrent.courseId(), safeHistory.courseId()),
                firstNonNull(safeCurrent.grade(), safeHistory.grade()),
                firstNonNull(safeCurrent.questionType(), safeHistory.questionType()),
                safeCurrent.questionCount() != null ? safeCurrent.questionCount() : safeHistory.questionCount(),
                firstNonNull(safeCurrent.timeRange(), safeHistory.timeRange()),
                firstNonNull(safeCurrent.difficulty(), safeHistory.difficulty()));
    }

    private String firstNonNull(String current, String history) {
        return current != null && !current.isBlank() ? current : history;
    }
}
