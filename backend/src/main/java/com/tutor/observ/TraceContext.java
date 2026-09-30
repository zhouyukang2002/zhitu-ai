package com.tutor.observ;

import com.tutor.observ.entity.AiObservationEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一轮对话的 Trace 上下文：观测事件在请求线程收集，close 时异步批量落库
 * （对齐 diet-agent TraceScope + Langfuse 非阻塞导出模式）。
 */
public class TraceContext {

    private final String traceId;
    private final String sessionId;
    private final Long userId;
    private final String message;
    private final long startTs = System.currentTimeMillis();
    private final List<AiObservationEntity> observations = new ArrayList<>();
    private final AtomicInteger promptTokens = new AtomicInteger();
    private final AtomicInteger completionTokens = new AtomicInteger();
    private String intent;
    private String actualSlots;
    private String routeSource;
    private String status;

    public TraceContext(String traceId, String sessionId, Long userId, String message) {
        this.traceId = traceId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.message = message;
    }

    public String getTraceId() { return traceId; }
    public String getSessionId() { return sessionId; }
    public Long getUserId() { return userId; }
    public String getMessage() { return message; }
    public long getStartTs() { return startTs; }
    public List<AiObservationEntity> getObservations() { return observations; }
    public int getPromptTokens() { return promptTokens.get(); }
    public int getCompletionTokens() { return completionTokens.get(); }
    public String getIntent() { return intent; }
    public String getActualSlots() { return actualSlots; }
    public String getRouteSource() { return routeSource; }
    public String getStatus() { return status; }
    public void setActualSlots(String actualSlots) { this.actualSlots = actualSlots; }
    public void setStatus(String intent, String routeSource, String status) {
        this.intent = intent;
        this.routeSource = routeSource;
        this.status = status;
    }

    void addTokens(int prompt, int completion) {
        promptTokens.addAndGet(prompt);
        completionTokens.addAndGet(completion);
    }
}
