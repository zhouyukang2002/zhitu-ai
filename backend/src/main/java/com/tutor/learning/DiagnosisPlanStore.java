package com.tutor.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.learning.entity.DiagnosisReportEntity;
import com.tutor.learning.entity.LearningPlanEntity;
import com.tutor.learning.mapper.DiagnosisReportMapper;
import com.tutor.learning.mapper.LearningPlanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 诊断报告与学习计划的持久化（state 接口 weakPoints/pathProgress 的数据源）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisPlanStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DiagnosisReportMapper diagnosisMapper;
    private final LearningPlanMapper planMapper;

    public void saveDiagnosis(String conversationId, List<CognitiveDiagnosis.WeakPoint> weakPoints, String summary) {
        DiagnosisReportEntity e = new DiagnosisReportEntity();
        e.setConversationId(conversationId);
        e.setWeakPointsJson(toJson(weakPoints));
        e.setSummary(summary);
        e.setCreatedAt(LocalDateTime.now());
        diagnosisMapper.insert(e);
    }

    public List<CognitiveDiagnosis.WeakPoint> latestWeakPoints(String conversationId) {
        DiagnosisReportEntity latest = diagnosisMapper.selectOne(new LambdaQueryWrapper<DiagnosisReportEntity>()
                .eq(DiagnosisReportEntity::getConversationId, conversationId)
                .orderByDesc(DiagnosisReportEntity::getId)
                .last("LIMIT 1"));
        if (latest == null) {
            return List.of();
        }
        try {
            return MAPPER.readValue(latest.getWeakPointsJson(), new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    public void savePlan(String conversationId, List<PathStep> path, double progress) {
        LearningPlanEntity e = new LearningPlanEntity();
        e.setConversationId(conversationId);
        e.setPathJson(toJson(path));
        e.setProgress(java.math.BigDecimal.valueOf(progress));
        e.setCreatedAt(LocalDateTime.now());
        planMapper.insert(e);
    }

    public LearningPlanEntity latestPlan(String conversationId) {
        return planMapper.selectOne(new LambdaQueryWrapper<LearningPlanEntity>()
                .eq(LearningPlanEntity::getConversationId, conversationId)
                .orderByDesc(LearningPlanEntity::getId)
                .last("LIMIT 1"));
    }

    /** plan 卡片的 path 步骤 */
    public record PathStep(int step, String title, String status) {
    }

    private String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            log.error("序列化失败", e);
            return "[]";
        }
    }
}
