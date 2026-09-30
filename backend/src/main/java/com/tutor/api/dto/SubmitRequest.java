package com.tutor.api.dto;

import java.util.List;

/**
 * POST /api/exercise/submit 请求/响应（幂等键 exerciseId）
 */
public record SubmitRequest(String sessionId, String exerciseId, List<Answer> answers) {

    public record Answer(String id, String answer) {
    }

    /** data: {grade, duplicated}；duplicated=true 时前端不重复渲染 grade 卡片 */
    public record SubmitResponse(Object grade, boolean duplicated) {
    }
}
