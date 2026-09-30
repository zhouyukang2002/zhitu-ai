# -*- coding: utf-8 -*-
# 一次性脚本：增强 ObservationController（/metrics 增强 + /metrics/timeseries 新增）
import io

path = 'src/main/java/com/tutor/api/ObservationController.java'
content = io.open(path, encoding='utf-8').read()

start = content.find('    /** 指标总览：请求数/Token/成本/延迟/降级率/路由分布（近 7 天） */')
assert start > 0, 'metrics comment not found'
marker = 'return Result.ok(data);\n    }'
end = content.find(marker, start) + len(marker)
old_method = content[start:end]

new_method = '''    /**
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
        data.put("models", models);

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

    /** 时序聚合：近 N 天按日聚合（请求量/Token/成本/平均延迟/P95 延迟），指标总览主视觉数据源 */
    @GetMapping("/metrics/timeseries")
    public Result<List<Map<String, Object>>> timeseries(@RequestParam(defaultValue = "14") int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days).toLocalDate().atStartOfDay();
        List<AiTraceEntity> traces = traceMapper.selectList(new LambdaQueryWrapper<AiTraceEntity>()
                .ge(AiTraceEntity::getCreatedAt, since));
        Map<String, List<AiTraceEntity>> byDay = new LinkedHashMap<>();
        for (int i = days - 1; i >= 0; i--) {
            byDay.put(java.time.LocalDate.now().minusDays(i).toString(), new ArrayList<>());
        }
        for (AiTraceEntity t : traces) {
            String day = t.getCreatedAt().toLocalDate().toString();
            byDay.computeIfPresent(day, (k, v) -> { v.add(t); return v; });
        }
        List<Map<String, Object>> series = new ArrayList<>();
        byDay.forEach((day, list) -> {
            List<Long> lat = list.stream()
                    .filter(t -> t.getLatencyMs() != null)
                    .map(AiTraceEntity::getLatencyMs).sorted().toList();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day);
            point.put("requests", list.size());
            point.put("promptTokens", list.stream().mapToLong(t -> t.getPromptTokens() == null ? 0 : t.getPromptTokens()).sum());
            point.put("completionTokens", list.stream().mapToLong(t -> t.getCompletionTokens() == null ? 0 : t.getCompletionTokens()).sum());
            point.put("cost", Math.round(list.stream().mapToDouble(t -> t.getCost() == null ? 0 : t.getCost().doubleValue()).sum() * 10000) / 10000.0);
            point.put("avgLatencyMs", (long) lat.stream().mapToLong(Long::longValue).average().orElse(0));
            point.put("p95LatencyMs", percentile(lat, 0.95));
            series.add(point);
        });
        return Result.ok(series);
    }

    /** 就地计算分位（输入需已升序） */
    private long percentile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }'''

content = content.replace(old_method, new_method)
io.open(path, 'w', encoding='utf-8').write(content)
print('OK: metrics enhanced + timeseries added')
