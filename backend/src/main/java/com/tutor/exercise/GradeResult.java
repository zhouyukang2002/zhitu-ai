package com.tutor.exercise;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批改结果（grade 卡片 content 契约）：
 * {exerciseId, totalScore, score, perQuestion:[{id,correct,answer,feedback,errorType}], knowledgePoints}
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GradeResult {

    private String exerciseId;
    private int totalScore;
    private int score;
    private List<PerQuestion> perQuestion;
    private List<String> knowledgePoints;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerQuestion {
        private String id;
        private boolean correct;
        private String answer;
        private String feedback;
        /** 概念混淆 / 计算失误 / null（前端有对应标签配色） */
        private String errorType;
    }
}
