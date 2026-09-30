package com.tutor.agent;

import com.tutor.learning.CognitiveDiagnosis;
import com.tutor.learning.DiagnosisPlanStore;
import com.tutor.learning.LearningRecordService;
import com.tutor.agent.LoopContext;
import com.tutor.learning.LearningState;
import com.tutor.learning.SessionStateService;
import com.tutor.agent.TraceStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 学情分析智能体（流水线起点）：读取答题记录 → 认知诊断 → 薄弱点诊断卡片。
 * LLM 不参与诊断本身（可解释、零成本、可评测），只把结果讲成人话由 summary 承担。
 */
@Component
@RequiredArgsConstructor
public class DiagnosisAgent {

    public static final String AGENT = "学情分析智能体";

    private final LearningRecordService learningRecordService;
    private final CognitiveDiagnosis cognitiveDiagnosis;
    private final DiagnosisPlanStore diagnosisPlanStore;
    private final SessionStateService stateService;

    /** 冷启动判定：无任何答题记录 → 需要先摸底（作答回流后才有诊断数据源） */
    public boolean needsPlacement(Long userId) {
        return learningRecordService.recordsForUser(userId, 1).isEmpty();
    }

    public Map<String, Object> diagnose(LoopContext ctx) {
        long start = System.currentTimeMillis();
        try {
            var records = learningRecordService.recordsForUser(ctx.getUserId(), 50);
            CognitiveDiagnosis.Result result = cognitiveDiagnosis.diagnose(records);
            List<CognitiveDiagnosis.WeakPoint> weakPoints = result.weakPoints();
            // 冷启动兜底：无答题记录时给出中性基线，保证流水线可继续
            if (weakPoints.isEmpty()) {
                weakPoints = List.of(new CognitiveDiagnosis.WeakPoint("通用基础", 50, "低"));
                result = new CognitiveDiagnosis.Result(weakPoints,
                        "暂无足够答题记录，先按默认基线规划，做一组练习后诊断会更准。");
            }
            diagnosisPlanStore.saveDiagnosis(ctx.getSessionId(), weakPoints, result.summary());
            stateService.set(ctx.getSessionId(), LearningState.DIAGNOSED);
            ctx.addTrace(new TraceStep(AGENT, "读取答题记录，诊断薄弱点",
                    "认知诊断模型（正确率+耗时加权）",
                    System.currentTimeMillis() - start, TraceStep.SUCCESS));
            return Map.of(
                    "weakPoints", weakPoints.stream()
                            .map(w -> Map.of("knowledgePoint", (Object) w.knowledgePoint(),
                                    "score", w.score(), "confidence", w.confidence()))
                            .toList(),
                    "summary", result.summary());
        } catch (Exception e) {
            ctx.addTrace(new TraceStep(AGENT, "读取答题记录，诊断薄弱点", "认知诊断模型",
                    System.currentTimeMillis() - start, TraceStep.DEGRADED));
            throw e;
        }
    }

    /** 从卡片 content 中还原结构化薄弱点（流水线下游输入） */
    @SuppressWarnings("unchecked")
    public List<CognitiveDiagnosis.WeakPoint> weakPointsOf(Map<String, Object> diagnosisContent) {
        List<Object> raw = (List<Object>) diagnosisContent.get("weakPoints");
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .map(o -> {
                    Map<String, Object> m = (Map<String, Object>) o;
                    return new CognitiveDiagnosis.WeakPoint(
                            (String) m.get("knowledgePoint"),
                            ((Number) m.get("score")).intValue(),
                            (String) m.get("confidence"));
                })
                .toList();
    }
}
