package com.tutor.observ;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.config.AppProperties;
import com.tutor.llm.LlmUsageHolder;
import com.tutor.llm.LlmGateway;
import com.tutor.observ.entity.AiObservationEntity;
import com.tutor.observ.entity.AiScoreEntity;
import com.tutor.observ.entity.AiTraceEntity;
import com.tutor.observ.mapper.AiObservationMapper;
import com.tutor.observ.mapper.AiScoreMapper;
import com.tutor.observ.mapper.AiTraceMapper;
import com.tutor.prompt.PromptStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评估体系与评估闭环（三路加权，对齐 diet-agent EvaluationService 模式）：
 * 批量解析 Trace 的离线评估——
 *   后端规则（意图/槽位准确率、延迟、成本、降级）+ LLM Judge（解释质量/自然度）
 *   + 用户反馈（点赞/点踩）三者加权，输出百分制评分报告；
 *   低分样本（<60）回流清单，反哺优化，形成「标注—批量评估—指标驱动迭代」闭环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 1000;
    private static final double LOW_SCORE_THRESHOLD = 60.0;
    /** 三路权重（缺失的路自动按剩余权重归一） */
    private static final double W_RULE = 0.6;
    private static final double W_JUDGE = 0.25;
    private static final double W_FEEDBACK = 0.15;

    private final AiTraceMapper traceMapper;
    private final AiObservationMapper observationMapper;
    private final AiScoreMapper scoreMapper;
    private final com.tutor.observ.mapper.EvaluationReportMapper reportMapper;
    private final LlmGateway gateway;
    private final PromptStore promptStore;
    private final AppProperties props;

    public Map<String, Object> evaluate(LocalDateTime startAt, LocalDateTime endAt,
                                        boolean includeJudge, Integer limit) {
        List<AiTraceEntity> traces = traceMapper.selectList(new LambdaQueryWrapper<AiTraceEntity>()
                .between(AiTraceEntity::getCreatedAt, startAt, endAt)
                .orderByDesc(AiTraceEntity::getCreatedAt)
                .last("LIMIT " + (limit == null || limit <= 0 ? DEFAULT_LIMIT : limit)));
        Map<String, List<AiScoreEntity>> feedbackByTrace = loadFeedback(traces);

        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, List<Double>> metricSums = new LinkedHashMap<>();
        double scoreSum = 0;
        int labeledCount = 0;
        List<Map<String, Object>> lowSamples = new ArrayList<>();

        for (AiTraceEntity trace : traces) {
            Map<String, Object> r = evaluateTrace(trace, feedbackByTrace.getOrDefault(trace.getId(), List.of()), includeJudge);
            results.add(r);
            double score = (double) r.get("score");
            scoreSum += score;
            if (trace.getExpectedIntent() != null) {
                labeledCount++;
            }
            for (Map.Entry<String, Object> e : ((Map<String, Object>) r.get("metrics")).entrySet()) {
                if (e.getValue() instanceof Number n) {
                    metricSums.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(n.doubleValue());
                }
            }
            if (score < LOW_SCORE_THRESHOLD) {
                lowSamples.add(Map.of("traceId", trace.getId(), "sessionId", trace.getSessionId(),
                        "message", StrUtil.nullToEmpty(trace.getMessage()),
                        "score", score, "status", trace.getStatus()));
            }
        }

        Map<String, Object> averages = new LinkedHashMap<>();
        metricSums.forEach((k, v) -> averages.put(k, round(v.stream().mapToDouble(d -> d).average().orElse(0))));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startAt", startAt.toString());
        report.put("endAt", endAt.toString());
        report.put("count", traces.size());
        report.put("labeledCount", labeledCount);
        report.put("avgScore", round(scoreSum / Math.max(1, traces.size())));
        report.put("metricAverages", averages);
        report.put("lowScoreSamples", lowSamples);
        report.put("results", results);
        // 报告落库（不含逐条 results，只存聚合与低分回流清单），形成历次评估的迭代存证
        report.put("reportId", saveReport(startAt, endAt, report, includeJudge));
        return report;
    }

    private Long saveReport(LocalDateTime startAt, LocalDateTime endAt,
                            Map<String, Object> report, boolean includeJudge) {
        try {
            com.tutor.observ.entity.EvaluationReportEntity entity = new com.tutor.observ.entity.EvaluationReportEntity();
            entity.setStartAt(startAt);
            entity.setEndAt(endAt);
            entity.setTraceCount((Integer) report.get("count"));
            entity.setLabeledCount((Integer) report.get("labeledCount"));
            entity.setAvgScore(BigDecimal.valueOf((Double) report.get("avgScore")));
            entity.setMetricAverages(MAPPER.writeValueAsString(report.get("metricAverages")));
            entity.setLowScoreSamples(MAPPER.writeValueAsString(report.get("lowScoreSamples")));
            entity.setIncludeJudge(includeJudge);
            entity.setCreatedAt(LocalDateTime.now());
            reportMapper.insert(entity);
            return entity.getId();
        } catch (Exception e) {
            log.warn("评估报告落库失败（评估结果照常返回）: {}", e.getMessage());
            return null;
        }
    }

    /** 历次评估报告（看板历史趋势） */
    public List<com.tutor.observ.entity.EvaluationReportEntity> reportHistory(int limit) {
        return reportMapper.selectList(new LambdaQueryWrapper<com.tutor.observ.entity.EvaluationReportEntity>()
                .orderByDesc(com.tutor.observ.entity.EvaluationReportEntity::getId)
                .last("LIMIT " + limit));
    }

    /** 单条 Trace 评估：规则指标 + LLM Judge + 用户反馈 → 三路加权百分制 */
    private Map<String, Object> evaluateTrace(AiTraceEntity trace,
                                              List<AiScoreEntity> feedbacks, boolean includeJudge) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("intentAccuracy", intentAccuracy(trace.getExpectedIntent(), trace.getIntent()));
        metrics.put("slotAccuracy", slotAccuracy(trace.getExpectedSlots(), trace.getActualSlots()));
        metrics.put("latencyScore", latencyScore(trace.getLatencyMs()));
        metrics.put("costScore", costScore(trace.getCost()));
        metrics.put("fallbackScore",
                "degraded".equals(trace.getStatus()) || "failed".equals(trace.getStatus()) ? 0.0 : 1.0);

        Double judgeScore = null;
        String judgeReason = null;
        if (includeJudge && gateway.available()) {
            try {
                String output = collectGenerationOutput(trace.getId());
                JsonNode node = gateway.callJson(
                        promptStore.get(PromptStore.JUDGE)
                                .replace("{message}", StrUtil.nullToEmpty(trace.getMessage())),
                        "待评估回复：\n" + StrUtil.nullToEmpty(output),
                        null, 0.2, null);
                double explanation = Math.max(1, Math.min(5, node.path("explanationQuality").asDouble(3)));
                double naturalness = Math.max(1, Math.min(5, node.path("naturalness").asDouble(3)));
                judgeScore = (explanation / 5 + naturalness / 5) / 2;
                judgeReason = node.path("reason").asText("");
            } catch (Exception e) {
                log.warn("LLM Judge 失败（跳过该路）: {}", e.getMessage());
            }
        }

        Double feedbackScore = feedbackScore(feedbacks);
        List<Double> ruleValues = new ArrayList<>();
        for (Double v : metrics.values()) {
            if (v != null) {
                ruleValues.add(v);
            }
        }
        Double ruleScore = avg(ruleValues);

        List<Double> present = new ArrayList<>();
        double weighted = 0;
        double weightSum = 0;
        if (ruleScore != null) {
            present.add(ruleScore);
            weighted += W_RULE * ruleScore;
            weightSum += W_RULE;
        }
        if (judgeScore != null) {
            present.add(judgeScore);
            weighted += W_JUDGE * judgeScore;
            weightSum += W_JUDGE;
        }
        if (feedbackScore != null) {
            present.add(feedbackScore);
            weighted += W_FEEDBACK * feedbackScore;
            weightSum += W_FEEDBACK;
        }
        double score = weightSum == 0 ? 0 : weighted / weightSum * 100;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("predictedIntent", trace.getIntent());
        detail.put("expectedIntent", trace.getExpectedIntent());
        detail.put("judgeReason", judgeReason);
        detail.put("feedbackCount", feedbacks.size());

        Map<String, Object> metricsRounded = new LinkedHashMap<>();
        metrics.forEach((k, v) -> metricsRounded.put(k, v == null ? null : round(v)));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("traceId", trace.getId());
        r.put("sessionId", trace.getSessionId());
        r.put("message", trace.getMessage());
        r.put("score", round(score));
        r.put("ruleScore", round(ruleScore == null ? 0 : ruleScore * 100));
        r.put("judgeScore", round(judgeScore == null ? 0 : judgeScore * 100));
        r.put("feedbackScore", round(feedbackScore == null ? 0 : feedbackScore * 100));
        r.put("metrics", metricsRounded);
        r.put("detail", detail);
        return r;
    }

    /** 用户反馈按 traceId 分组（source=user 一路） */
    private Map<String, List<AiScoreEntity>> loadFeedback(List<AiTraceEntity> traces) {
        if (traces.isEmpty()) {
            return Map.of();
        }
        List<String> ids = traces.stream().map(AiTraceEntity::getId).toList();
        List<AiScoreEntity> scores = scoreMapper.selectList(new LambdaQueryWrapper<AiScoreEntity>()
                .in(AiScoreEntity::getTraceId, ids)
                .eq(AiScoreEntity::getSource, "user"));
        return scores.stream().collect(java.util.stream.Collectors.groupingBy(AiScoreEntity::getTraceId));
    }

    /** 意图准确率：有标注才计分 */
    private Double intentAccuracy(String expected, String actual) {
        return expected == null ? null : (expected.equals(actual) ? 1.0 : 0.0);
    }

    /** 槽位准确率：期望槽位键值对在实际槽位中的命中率 */
    private Double slotAccuracy(String expectedJson, String actualJson) {
        if (StrUtil.isBlank(expectedJson) || StrUtil.isBlank(actualJson)) {
            return null;
        }
        try {
            Map<String, Object> expected = MAPPER.readValue(expectedJson, Map.class);
            Map<String, Object> actual = MAPPER.readValue(actualJson, Map.class);
            int hit = 0;
            int total = 0;
            for (Map.Entry<String, Object> e : expected.entrySet()) {
                if (e.getValue() == null || String.valueOf(e.getValue()).isBlank()) {
                    continue;
                }
                total++;
                if (String.valueOf(e.getValue()).equals(String.valueOf(actual.get(e.getKey())))) {
                    hit++;
                }
            }
            return total == 0 ? null : (double) hit / total;
        } catch (Exception e) {
            return null;
        }
    }

    /** 延迟分：流式输出 2s 满分、20s 保底 0.2 */
    private Double latencyScore(Long latencyMs) {
        if (latencyMs == null) {
            return null;
        }
        if (latencyMs <= 2000) return 1.0;
        if (latencyMs <= 5000) return 0.8;
        if (latencyMs <= 10000) return 0.6;
        if (latencyMs <= 20000) return 0.4;
        return 0.2;
    }

    /** 成本分：单轮 ≤0.02 元满分，>0.1 元 0.2 */
    private Double costScore(BigDecimal cost) {
        if (cost == null) {
            return null;
        }
        double c = cost.doubleValue();
        if (c <= 0.02) return 1.0;
        if (c <= 0.05) return 0.8;
        if (c <= 0.1) return 0.6;
        return 0.2;
    }

    /** 用户反馈分：赞=1.0，踩=0.3 */
    private Double feedbackScore(List<AiScoreEntity> feedbacks) {
        if (feedbacks == null || feedbacks.isEmpty()) {
            return null;
        }
        double avg = feedbacks.stream()
                .mapToInt(f -> f.getRating() != null && f.getRating() > 0 ? 1 : 0)
                .average().orElse(0);
        return avg == 1.0 ? 1.0 : 0.3;
    }

    /** 评估用的回复原文：拼接该 Trace 的 GENERATION 输出 */
    private String collectGenerationOutput(String traceId) {
        List<AiObservationEntity> gens = observationMapper.selectList(
                new LambdaQueryWrapper<AiObservationEntity>()
                        .eq(AiObservationEntity::getTraceId, traceId)
                        .eq(AiObservationEntity::getType, "GENERATION")
                        .orderByAsc(AiObservationEntity::getTs));
        StringBuilder sb = new StringBuilder();
        for (AiObservationEntity g : gens) {
            sb.append("【").append(g.getName()).append("】\n")
              .append(StrUtil.nullToEmpty(g.getOutput())).append("\n\n");
        }
        return sb.toString();
    }

    /** 时间解析容错：兼容 Z 后缀的 ISO 立即时间与本地时间 */
    public static LocalDateTime parseFlexible(String s) {
        if (s == null || s.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            // 前端发送 UTC ISO（带 Z），必须换算到系统时区，直接 toLocalDateTime 会少 8 小时
            return java.time.OffsetDateTime.parse(s)
                    .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime();
        }
    }

    private Double avg(List<Double> values) {
        return values.isEmpty() ? null : values.stream().mapToDouble(d -> d).average().orElse(0);
    }

    private double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
