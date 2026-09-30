package com.tutor.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tutor.common.web.Result;
import com.tutor.observ.EvaluationService;
import com.tutor.observ.entity.AiObservationEntity;
import com.tutor.observ.entity.AiScoreEntity;
import com.tutor.observ.entity.AiTraceEntity;
import com.tutor.observ.mapper.AiObservationMapper;
import com.tutor.observ.mapper.AiScoreMapper;
import com.tutor.observ.mapper.AiTraceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 研发侧可观测 REST（对齐 diet-agent Trace/Evaluation/Feedback 控制器职责）：
 * Trace 排查（列表/详情/标注）、批量评估（三路加权百分制报告）、用户反馈采集。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class ObservationController {

    private final AiTraceMapper traceMapper;
    private final AiObservationMapper observationMapper;
    private final AiScoreMapper scoreMapper;
    private final EvaluationService evaluationService;

    /** Trace 列表（支持万能 query/消息关键词模糊搜、traceId、sessionId、intent 意图、状态、日期多维组合筛选） */
    @GetMapping("/traces")
    public Result<Map<String, Object>> traces(@RequestParam(required = false) String sessionId,
                                              @RequestParam(required = false) String query,
                                              @RequestParam(required = false) String traceId,
                                              @RequestParam(required = false) String intent,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String date,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size) {
        var wrapper = new LambdaQueryWrapper<AiTraceEntity>();

        // 1. 指定精确参数
        if (traceId != null && !traceId.isBlank()) {
            wrapper.eq(AiTraceEntity::getId, traceId.trim());
        } else if (sessionId != null && !sessionId.isBlank()) {
            wrapper.eq(AiTraceEntity::getSessionId, sessionId.trim());
        }

        // 2. 万能检索 query（支持自动判定 traceId(t_...) / sessionId(s_...) / 消息内容模糊搜）
        if (query != null && !query.isBlank()) {
            String q = query.trim();
            boolean isHexOrUuid = q.matches("(?i)^[0-9a-fA-F\\-]{32,36}$");
            if (q.startsWith("t_") || isHexOrUuid) {
                // 精准 ID 命中走索引，跳过 message 大字段模糊扫表
                wrapper.and(w -> w.eq(AiTraceEntity::getId, q).or().eq(AiTraceEntity::getSessionId, q));
            } else if (q.startsWith("s_")) {
                wrapper.eq(AiTraceEntity::getSessionId, q);
            } else {
                wrapper.and(w -> w.like(AiTraceEntity::getMessage, q)
                        .or().eq(AiTraceEntity::getId, q)
                        .or().eq(AiTraceEntity::getSessionId, q));
                // 若未指定日期，兜底限定最近 30 天，防止大文本模糊查询引发历史冷数据全表扫描
                if (date == null || date.isBlank()) {
                    wrapper.ge(AiTraceEntity::getCreatedAt, LocalDateTime.now().minusDays(30));
                }
            }
        }

        // 3. 意图筛选
        if (intent != null && !intent.isBlank()) {
            wrapper.eq(AiTraceEntity::getIntent, intent.trim());
        }

        // 4. 状态筛选
        if (status != null && !status.isBlank()) {
            wrapper.eq(AiTraceEntity::getStatus, status.trim());
        }

        // 5. 日期筛选
        if (date != null && !date.isBlank()) {
            LocalDateTime dayStart = java.time.LocalDate.parse(date).atStartOfDay();
            wrapper.ge(AiTraceEntity::getCreatedAt, dayStart)
                   .lt(AiTraceEntity::getCreatedAt, dayStart.plusDays(1));
        }

        wrapper.orderByDesc(AiTraceEntity::getCreatedAt);
        var result = traceMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotal());
        data.put("items", result.getRecords());
        return Result.ok(data);
    }

    /** Trace 详情：链路 + 观测节点（瀑布时间线数据源） */
    @GetMapping("/trace/{id}")
    public Result<Map<String, Object>> trace(@PathVariable("id") String id) {
        AiTraceEntity trace = traceMapper.selectById(id);
        if (trace == null) {
            return Result.error(404, "Trace 不存在");
        }
        List<AiObservationEntity> observations = observationMapper.selectList(
                new LambdaQueryWrapper<AiObservationEntity>()
                        .eq(AiObservationEntity::getTraceId, id)
                        .orderByAsc(AiObservationEntity::getTs));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("trace", trace);
        data.put("observations", observations);
        return Result.ok(data);
    }

    /** Trace 标注：写入期望意图/期望槽位（评估闭环的标注环节） */
    @PostMapping("/trace/{id}/label")
    public Result<Void> label(@PathVariable("id") String id,
                              @RequestBody Map<String, Object> body) {
        AiTraceEntity trace = traceMapper.selectById(id);
        if (trace == null) {
            return Result.error(404, "Trace 不存在");
        }
        trace.setExpectedIntent((String) body.get("expectedIntent"));
        Object slots = body.get("expectedSlots");
        try {
            trace.setExpectedSlots(slots == null ? null
                    : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(slots));
        } catch (Exception e) {
            return Result.error(400, "槽位格式非法");
        }
        traceMapper.updateById(trace);
        return Result.ok();
    }

    /** 批量评估：解析时间范围内的 Trace，三路加权输出百分制报告 */
    @PostMapping("/eval")
    public Result<Map<String, Object>> eval(@RequestBody Map<String, Object> body) {
        LocalDateTime startAt = EvaluationService.parseFlexible((String) body.getOrDefault("startAt",
                LocalDateTime.now().minusDays(7).toString()));
        LocalDateTime endAt = EvaluationService.parseFlexible((String) body.getOrDefault("endAt",
                LocalDateTime.now().toString()));
        boolean includeJudge = Boolean.TRUE.equals(body.get("includeJudge"));
        Integer limit = body.get("limit") == null ? null : Integer.parseInt(String.valueOf(body.get("limit")));
        return Result.ok(evaluationService.evaluate(startAt, endAt, includeJudge, limit));
    }

    /** 评估报告历史（历次批量评估的落库存证，最新在前） */
    @GetMapping("/eval/history")
    public Result<List<Map<String, Object>>> evalHistory(@RequestParam(defaultValue = "20") int limit) {
        var reports = evaluationService.reportHistory(Math.min(Math.max(limit, 1), 100));
        List<Map<String, Object>> items = new ArrayList<>();
        for (var r : reports) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("startAt", String.valueOf(r.getStartAt()));
            m.put("endAt", String.valueOf(r.getEndAt()));
            m.put("traceCount", r.getTraceCount());
            m.put("labeledCount", r.getLabeledCount());
            m.put("avgScore", r.getAvgScore());
            m.put("includeJudge", Boolean.TRUE.equals(r.getIncludeJudge()));
            m.put("createdAt", String.valueOf(r.getCreatedAt()));
            items.add(m);
        }
        return Result.ok(items);
    }

    /** 用户反馈（赞/踩），按 (traceId, source) 覆盖更新 */
    @PostMapping("/feedback")
    public Result<Void> feedback(@RequestBody Map<String, String> body) {
        String traceId = body.get("traceId");
        int rating = Integer.parseInt(body.getOrDefault("rating", "1"));
        AiTraceEntity trace = traceMapper.selectById(traceId);
        if (trace == null) {
            return Result.error(404, "Trace 不存在");
        }
        AiScoreEntity existing = scoreMapper.selectOne(new LambdaQueryWrapper<AiScoreEntity>()
                .eq(AiScoreEntity::getTraceId, traceId)
                .eq(AiScoreEntity::getSource, "user"));
        if (existing == null) {
            existing = new AiScoreEntity();
            existing.setTraceId(traceId);
            existing.setSessionId(trace.getSessionId());
            existing.setSource("user");
            existing.setCreatedAt(java.time.LocalDateTime.now());
        }
        existing.setRating(rating);
        existing.setComment(body.get("comment"));
        if (existing.getId() == null) {
            scoreMapper.insert(existing);
        } else {
            scoreMapper.updateById(existing);
        }
        return Result.ok();
    }

    /**
     * 指标总览（近 N 天，默认 7）：汇总卡 + P50/P95 + 意图分布 + 反馈计数 + 模型统计 + 最近异常 Trace。
     */
    @GetMapping("/metrics")
    public Result<Map<String, Object>> metrics(@RequestParam(defaultValue = "7") int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<AiTraceEntity> traces = traceMapper.selectList(new LambdaQueryWrapper<AiTraceEntity>()
                .ge(AiTraceEntity::getCreatedAt, since));
        int requests = traces.size();
        long promptTokens = traces.stream().mapToLong(t -> t.getPromptTokens() == null ? 0 : t.getPromptTokens()).sum();
        long completionTokens = traces.stream().mapToLong(t -> t.getCompletionTokens() == null ? 0 : t.getCompletionTokens()).sum();
        double cost = traces.stream().mapToDouble(t -> t.getCost() == null ? 0 : t.getCost().doubleValue()).sum();
        double avgLatency = traces.stream().filter(t -> t.getLatencyMs() != null)
                .mapToLong(AiTraceEntity::getLatencyMs).average().orElse(0);
        long degraded = traces.stream().filter(t -> "degraded".equals(t.getStatus()) || "failed".equals(t.getStatus())).count();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requests", requests);
        data.put("promptTokens", promptTokens);
        data.put("completionTokens", completionTokens);
        data.put("cost", Math.round(cost * 10000) / 10000.0);
        data.put("avgLatencyMs", (long) avgLatency);
        data.put("degradedRate", requests == 0 ? 0 : Math.round(degraded * 1000.0 / requests) / 1000.0);

        // 延迟分位（企业看板标配：均值会被长尾骗，P95 才是体验真相）
        List<Long> latencies = traces.stream()
                .filter(t -> t.getLatencyMs() != null)
                .map(AiTraceEntity::getLatencyMs).sorted().toList();
        data.put("p50LatencyMs", percentile(latencies, 0.50));
        data.put("p95LatencyMs", percentile(latencies, 0.95));

        // 路由来源分布（原有）
        Map<String, Object> routing = new LinkedHashMap<>();
        traces.stream()
                .filter(t -> t.getRouteSource() != null)
                .collect(java.util.stream.Collectors.groupingBy(AiTraceEntity::getRouteSource, java.util.stream.Collectors.counting()))
                .forEach(routing::put);
        data.put("routing", routing);

        // 意图分布（业务视角，6 类）
        Map<String, Object> intents = new LinkedHashMap<>();
        traces.stream()
                .filter(t -> t.getIntent() != null)
                .collect(java.util.stream.Collectors.groupingBy(AiTraceEntity::getIntent, java.util.stream.Collectors.counting()))
                .forEach(intents::put);
        data.put("intents", intents);

        // 用户反馈计数（赞/踩）
        List<AiScoreEntity> feedbacks = scoreMapper.selectList(new LambdaQueryWrapper<AiScoreEntity>()
                .eq(AiScoreEntity::getSource, "user")
                .ge(AiScoreEntity::getCreatedAt, since));
        long up = feedbacks.stream().filter(f -> f.getRating() != null && f.getRating() > 0).count();
        long down = feedbacks.stream().filter(f -> f.getRating() != null && f.getRating() < 0).count();
        data.put("feedbackUp", up);
        data.put("feedbackDown", down);

        // 模型统计（GENERATION 观测按模型聚合：调用数/Token/成本）
        List<AiObservationEntity> gens = observationMapper.selectList(new LambdaQueryWrapper<AiObservationEntity>()
                .eq(AiObservationEntity::getType, "GENERATION")
                .ge(AiObservationEntity::getCreatedAt, since));
        Map<String, Map<String, Object>> models = new LinkedHashMap<>();
        for (AiObservationEntity g : gens) {
            String model = g.getModel() == null ? "unknown" : g.getModel();
            Map<String, Object> m = models.computeIfAbsent(model, k -> {
                Map<String, Object> nm = new LinkedHashMap<>();
                nm.put("calls", 0);
                nm.put("promptTokens", 0L);
                nm.put("completionTokens", 0L);
                nm.put("cost", 0.0);
                return nm;
            });
            m.put("calls", (int) m.get("calls") + 1);
            m.put("promptTokens", (long) m.get("promptTokens") + (g.getPromptTokens() == null ? 0 : g.getPromptTokens()));
            m.put("completionTokens", (long) m.get("completionTokens") + (g.getCompletionTokens() == null ? 0 : g.getCompletionTokens()));
            m.put("cost", Math.round(((double) m.get("cost") + (g.getCost() == null ? 0 : g.getCost().doubleValue())) * 10000) / 10000.0);
        }
        data.put("modelStats", models);

        // 最近降级/失败 Trace（运维下钻入口，点击进链路追踪）
        List<Map<String, Object>> recentIssues = traces.stream()
                .filter(t -> "degraded".equals(t.getStatus()) || "failed".equals(t.getStatus()))
                .sorted(java.util.Comparator.comparing(AiTraceEntity::getCreatedAt).reversed())
                .limit(8)
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", t.getId());
                    m.put("sessionId", t.getSessionId());
                    m.put("message", t.getMessage());
                    m.put("intent", t.getIntent());
                    m.put("status", t.getStatus());
                    m.put("latencyMs", t.getLatencyMs());
                    m.put("createdAt", String.valueOf(t.getCreatedAt()));
                    return m;
                })
                .toList();
        data.put("recentIssues", recentIssues);
        return Result.ok(data);
    }

    /** 按日时序（请求量/Token/成本/平均延迟），研发看板时序图数据源 */
    @GetMapping("/metrics/timeseries")
    public Result<List<Map<String, Object>>> timeseries(@RequestParam(defaultValue = "14") int days) {
        int safeDays = Math.min(Math.max(days, 1), 90);
        var since = LocalDateTime.now().minusDays(safeDays - 1).toLocalDate().atStartOfDay();
        List<AiTraceEntity> traces = traceMapper.selectList(new LambdaQueryWrapper<AiTraceEntity>()
                .ge(AiTraceEntity::getCreatedAt, since));

        Map<java.time.LocalDate, List<AiTraceEntity>> byDay = traces.stream()
                .filter(t -> t.getCreatedAt() != null)
                .collect(java.util.stream.Collectors.groupingBy(t -> t.getCreatedAt().toLocalDate()));

        List<Map<String, Object>> items = new ArrayList<>();
        for (java.time.LocalDate d = since.toLocalDate(); !d.isAfter(java.time.LocalDate.now()); d = d.plusDays(1)) {
            List<AiTraceEntity> day = byDay.getOrDefault(d, List.of());
            double dayCost = day.stream().mapToDouble(t -> t.getCost() == null ? 0 : t.getCost().doubleValue()).sum();
            double dayLatency = day.stream().filter(t -> t.getLatencyMs() != null)
                    .mapToLong(AiTraceEntity::getLatencyMs).average().orElse(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.toString());
            m.put("requests", day.size());
            m.put("promptTokens", day.stream().mapToLong(t -> t.getPromptTokens() == null ? 0 : t.getPromptTokens()).sum());
            m.put("completionTokens", day.stream().mapToLong(t -> t.getCompletionTokens() == null ? 0 : t.getCompletionTokens()).sum());
            m.put("cost", Math.round(dayCost * 10000) / 10000.0);
            m.put("avgLatencyMs", (long) dayLatency);
            items.add(m);
        }
        return Result.ok(items);
    }

    private long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        return sorted.get((int) Math.floor(p * (sorted.size() - 1)));
    }
}
