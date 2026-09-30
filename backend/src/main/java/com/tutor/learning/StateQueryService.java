package com.tutor.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tutor.exercise.entity.GradeResultEntity;
import com.tutor.exercise.mapper.GradeResultMapper;
import com.tutor.learning.LearningState;
import com.tutor.learning.SessionStateService;
import com.tutor.learning.DiagnosisPlanStore;
import com.tutor.learning.entity.LearningPlanEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 状态读模型（右侧面板数据源 GET /api/session/{id}/state）：
 * state + pipeline + weakPoints（最近诊断）+ pathProgress（最近计划）+ lastGrade（最近批改）。
 * 刷新时机由前端控制（卡片驱动），后端只需保证拉取时返回当前值。
 */
@Service
@RequiredArgsConstructor
public class StateQueryService {

    private final SessionStateService stateService;
    private final DiagnosisPlanStore diagnosisPlanStore;
    private final GradeResultMapper gradeMapper;

    public StateVO query(String sessionId) {
        var state = stateService.get(sessionId);
        List<StateVO.WeakPoint> weakPoints = diagnosisPlanStore.latestWeakPoints(sessionId)
                .stream()
                .map(w -> new StateVO.WeakPoint(w.knowledgePoint(), w.score()))
                .toList();
        double pathProgress = 0;
        LearningPlanEntity plan = diagnosisPlanStore.latestPlan(sessionId);
        if (plan != null && plan.getProgress() != null) {
            pathProgress = plan.getProgress().doubleValue();
        }
        GradeResultEntity lastGrade = gradeMapper.selectOne(new LambdaQueryWrapper<GradeResultEntity>()
                .eq(GradeResultEntity::getConversationId, sessionId)
                .orderByDesc(GradeResultEntity::getId)
                .last("LIMIT 1"));
        Long lastGradeId = lastGrade == null ? null : Long.valueOf(lastGrade.getScore());
        return new StateVO(
                state == null ? null : state.name(),
                List.of(LearningState.PIPELINE),
                weakPoints,
                pathProgress,
                lastGradeId);
    }
}
