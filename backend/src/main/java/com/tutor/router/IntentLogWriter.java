package com.tutor.router;

import cn.hutool.core.io.FileUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图路由 JSONL 日志（阶段 5 评测 Harness 的数据基础）：
 * 从第一条意图开始攒数据——消息、路由来源、意图、置信度、槽位、耗时、是否降级。
 * 阶段 5 直接回放这批日志算「路由准确率」等指标，不用现造数据。
 *
 * <p>P1 起额外记录 decisionTrace（决策演变链）：LLM原始意图/置信 → 规则互证 → 矫正 → 校准 → 迟滞 → 最终路由，
 * 线上出 badcase 时可逐层归因（是 LLM 分错、矫正改坏、还是兜底兜错），同时是置信度校准的原始样本来源。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentLogWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppProperties props;

    private File dir;

    @PostConstruct
    public void init() {
        dir = new File(props.getLogDir());
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("意图日志目录创建失败: {}", dir.getAbsolutePath());
        }
    }

    public synchronized void write(String sessionId, String message, RouteDecision decision,
                                   boolean llmAvailable, long costMs) {
        try {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("ts", System.currentTimeMillis());
            line.put("time", LocalDateTime.now().toString());
            line.put("sessionId", sessionId);
            line.put("message", message);
            line.put("intent", decision.intent().name());
            line.put("source", decision.source());
            line.put("confidence", decision.confidence());
            var sl = decision.slots() == null ? new Slots(null, null, null, null, null, null, null, null) : decision.slots();
            line.put("kpSlot", decision.kpSlot());
            line.put("courseSlot", decision.courseName());
            line.put("slots", java.util.Map.of(
                    "knowledgePoint", java.util.Optional.ofNullable(sl.knowledgePoint()).orElse(""),
                    "courseName", java.util.Optional.ofNullable(sl.courseName()).orElse(""),
                    "grade", java.util.Optional.ofNullable(sl.grade()).orElse(""),
                    "questionType", java.util.Optional.ofNullable(sl.questionType()).orElse(""),
                    "questionCount", sl.questionCount() == null ? "" : sl.questionCount(),
                    "timeRange", java.util.Optional.ofNullable(sl.timeRange()).orElse(""),
                    "difficulty", java.util.Optional.ofNullable(sl.difficulty()).orElse("")));
            line.put("degraded", decision.degraded());
            line.put("llmAvailable", llmAvailable);
            line.put("costMs", costMs);
            List<String> trace = decision.decisionTrace();
            line.put("decisionTrace", trace == null ? List.of() : trace);
            File file = new File(dir, "intent-" + LocalDateTime.now().toLocalDate() + ".jsonl");
            FileUtil.appendUtf8String(MAPPER.writeValueAsString(line) + "\n", file);
        } catch (Exception e) {
            log.warn("意图日志写入失败: {}", e.getMessage());
        }
    }
}
