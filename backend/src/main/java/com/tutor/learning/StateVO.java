package com.tutor.learning;

import java.util.List;

/**
 * 学习状态读模型（GET /api/session/{id}/state 的返回体）。
 * 归属学习域：字段与前端契约一致，但类型由领域层定义（避免领域反向依赖契约包）。
 */
public record StateVO(String state, List<String> pipeline,
                      List<WeakPoint> weakPoints, double pathProgress, Long lastGrade) {

    public record WeakPoint(String knowledgePoint, int score) {
    }
}
