package com.tutor.observ;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.config.AppProperties;
import com.tutor.observ.entity.AiObservationEntity;
import com.tutor.observ.entity.AiTraceEntity;
import com.tutor.observ.mapper.AiObservationMapper;
import com.tutor.observ.mapper.AiTraceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PreDestroy;

/**
 * 全链路可观测：Trace 事件采集与落库（对齐 diet-agent AgentTraceService 模式）。
 * 业务关键节点采集输入/输出/Token/耗时，close 时异步批量写入——观测不阻塞业务主流程。
 * ai_trace 预留 expected_intent/expected_slots 标注列，支撑「标注—评估—迭代」闭环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceRecorder {

    private static final int INPUT_TRUNCATE = 4096;
    private static final int OUTPUT_TRUNCATE = 32768;

    private final AiTraceMapper traceMapper;
    private final AiObservationMapper observationMapper;
    private final AppProperties props;

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);

    /**
     * 有界异步落库线程池：
     * 1. 采用容量 5000 的有界阻塞队列，防止数据库变慢时无限堆积 Task 触发 JVM OOM；
     * 2. 队列饱和时采用 DiscardOldestPolicy 保护主业务不受阻，同时打印预警日志；
     * 3. 观测写入不阻塞 SSE 主流程。
     */
    private final ThreadPoolExecutor persistExecutor = new ThreadPoolExecutor(
            1, 2, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(5000),
            r -> {
                Thread t = new Thread(r, "trace-persist-" + THREAD_COUNTER.getAndIncrement());
                t.setDaemon(true);
                return t;
            },
            (r, executor) -> log.warn("Trace 异步落库队列已满（5000），丢弃积压任务以保护系统内存稳定")
    );

    @PreDestroy
    public void shutdown() {
        log.info("正在优雅关闭 TraceRecorder 异步持久化线程池...");
        persistExecutor.shutdown();
        try {
            if (!persistExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                persistExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            persistExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public TraceContext open(String sessionId, Long userId, String message) {
        return new TraceContext("t_" + IdUtil.fastSimpleUUID().substring(0, 12), sessionId, userId, message);
    }

    /** SPAN 观测：路由/智能体等业务节点（真实耗时与状态） */
    public void span(TraceContext ctx, String name, String tool, long costMs, String status) {
        AiObservationEntity e = base(ctx, "SPAN", name);
        e.setTool(tool);
        e.setLatencyMs(costMs);
        e.setStatus(status);
        ctx.getObservations().add(e);
    }

    /** GENERATION 观测：一次 LLM 调用（含 Token 消耗与成本核算） */
    public void generation(TraceContext ctx, String name, String input, String output,
                           com.tutor.llm.LlmUsage usage, String status) {
        AiObservationEntity e = base(ctx, "GENERATION", name);
        e.setModel(usage == null ? null : usage.model());
        e.setInput(truncate(input, INPUT_TRUNCATE));
        e.setOutput(truncate(output, OUTPUT_TRUNCATE));
        int prompt = usage == null || usage.promptTokens() == null ? 0 : usage.promptTokens();
        int completion = usage == null || usage.completionTokens() == null ? 0 : usage.completionTokens();
        e.setPromptTokens(prompt);
        e.setCompletionTokens(completion);
        e.setCost(cost(prompt, completion));
        e.setLatencyMs(usage == null ? 0 : usage.latencyMs());
        e.setStatus(status);
        ctx.getObservations().add(e);
        ctx.addTokens(prompt, completion);
    }

    /** 关闭并异步落库：汇总 Token/成本/状态，批量写 Trace + 观测节点 */
    public void close(TraceContext ctx) {
        persistExecutor.submit(() -> persist(ctx));
    }

    private void persist(TraceContext ctx) {
        try {
            AiTraceEntity trace = new AiTraceEntity();
            trace.setId(ctx.getTraceId());
            trace.setSessionId(ctx.getSessionId());
            trace.setUserId(ctx.getUserId());
            trace.setMessage(ctx.getMessage());
            trace.setIntent(ctx.getIntent());
            trace.setActualSlots(ctx.getActualSlots());
            trace.setRouteSource(ctx.getRouteSource());
            trace.setPromptTokens(ctx.getPromptTokens());
            trace.setCompletionTokens(ctx.getCompletionTokens());
            trace.setCost(cost(ctx.getPromptTokens(), ctx.getCompletionTokens()));
            trace.setLatencyMs(System.currentTimeMillis() - ctx.getStartTs());
            trace.setStatus(ctx.getStatus());
            trace.setCreatedAt(LocalDateTime.now());
            traceMapper.insert(trace);
            ctx.getObservations().forEach(observationMapper::insert);
        } catch (Exception e) {
            log.warn("Trace 落库失败（不影响业务）: {}", e.getMessage());
        }
    }

    private AiObservationEntity base(TraceContext ctx, String type, String name) {
        AiObservationEntity e = new AiObservationEntity();
        e.setTraceId(ctx.getTraceId());
        e.setType(type);
        e.setName(name);
        e.setStatus("success");
        e.setTs(System.currentTimeMillis());
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }

    private BigDecimal cost(int promptTokens, int completionTokens) {
        var o = props.getObserv();
        double c = promptTokens / 1e6 * o.getInputPricePerMToken()
                + completionTokens / 1e6 * o.getOutputPricePerMToken();
        return BigDecimal.valueOf(c).setScale(6, RoundingMode.HALF_UP);
    }
}
