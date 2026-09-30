package com.tutor.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一轮 SSE 会话的发送端封装。
 *
 * 关键约束（与 frontend/src/api/chat.js 的解析器逐字节对齐）：
 * 1. 前端按「空行分帧 + 行正则 /^event: (.+)$/、/^data: (.+)$/」提取，要求冒号后带空格；
 *    而 Spring 的 SseEventBuilder 写出 "event:xxx"（无空格），会令前端静默丢帧；
 *    且 SseEmitter.send(Object, MediaType) 会把内容再包一层 "data:" 前缀，裸帧不可行。
 *    因此这里用父类 ResponseBodyEmitter（不做任何 SSE 包装），由本类自组字节帧，
 *    一次 send 恰好一帧，与 Mock（node mock/server.mjs）字节一致：
 *       event: xxx\ndata: {...}\n\n     （注释帧：: keepalive\n\n）
 * 2. 中断检测：send 抛 IOException / 容器 onCompletion / onTimeout 都会置 closed，
 *    业务线程据此停止后续 LLM 调用（token 不白烧）。
 */
@Slf4j
public class SseSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ResponseBodyEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ScheduledFuture<?> heartbeatTask;

    public SseSession(ResponseBodyEmitter emitter, ScheduledExecutorService heartbeatScheduler) {
        this.emitter = emitter;
        // 长时间无输出（等待 LLM）时发注释帧防代理断连；前端解析器对无 data 的帧安全跳过
        this.heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(
                () -> sendComment("keepalive"), 15, 15, TimeUnit.SECONDS);
    }

    /** 注册到 emitter 的生命周期回调统一走这里，幂等关闭 */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        heartbeatTask.cancel(false);
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 客户端已断开时 complete 会抛异常，忽略即可
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** 结构化事件：event: xxx\ndata: {...}\n\n（字节级原样写出，线程安全） */
    public synchronized void send(String event, Object data) {
        if (closed.get()) {
            return;
        }
        try {
            String json = MAPPER.writeValueAsString(data);
            byte[] frame = ("event: " + event + "\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
            emitter.send(frame, MediaType.APPLICATION_OCTET_STREAM);
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
            close();
        }
    }

    /** 注释帧：: keepalive\n\n（线程安全） */
    private synchronized void sendComment(String comment) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send((": " + comment + "\n\n").getBytes(StandardCharsets.UTF_8),
                    MediaType.APPLICATION_OCTET_STREAM);
        } catch (IOException | IllegalStateException e) {
            close();
        }
    }

    // ---------- 前端契约的四个事件 + 心跳 ----------

    /** 文本增量（打字机） */
    public void delta(String text) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("delta", text);
        send("message", payload);
    }

    /** 结构化卡片（type + content，id/role/ts 由前端补齐） */
    public void card(String type, Object content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("content", content);
        send("card", payload);
    }

    /** 结束本轮（必须发，前端据此解锁输入框）；payload 可携带 traceId 供反馈埋点 */
    public void done(Object payload) {
        send("done", payload == null ? Map.of() : payload);
    }

    /** 错误（前端顶部错误条 + 重试） */
    public void error(String message) {
        send("error", Map.of("message", message));
    }
}
