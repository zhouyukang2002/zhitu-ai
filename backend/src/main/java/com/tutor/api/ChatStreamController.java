package com.tutor.api;

import com.tutor.pipeline.ChatRequest;
import com.tutor.sse.SseSession;
import com.tutor.pipeline.ChatPipelineRunner;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.RejectedExecutionException;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/**
 * SSE 对话入口：POST /api/chat/stream。
 *
 * 线程模型：Tomcat 线程在这里只做 emitter 创建与任务投递，立即返回；
 * 流水线（路由+智能体+LLM 流式）全部跑在独立 sseExecutor 线程池。
 * 客户端中断（abort）体现在 send 抛异常 / onCompletion / onTimeout，
 * SseSession.close() 统一置位，业务循环据此停止后续 LLM 调用。
 */
@RestController
@RequiredArgsConstructor
public class ChatStreamController {

    private final ChatPipelineRunner runner;

    @Resource(name = "sseHeartbeatScheduler")
    private ScheduledExecutorService heartbeatScheduler;

    /**
     * SSE 连接超时（5 分钟）：必须大于"舱壁排队 + LLM 最长耗时 + 心跳间隔"之和，
     * 否则长请求在生成中就被容器断开；与 ResponseBodyEmitter 超时语义绑定。
     */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    @Resource(name = "sseExecutor")
    private ThreadPoolTaskExecutor sseExecutor;

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseBodyEmitter stream(@RequestBody ChatRequest req, HttpServletResponse resp) {
        // 防 Nginx 反向代理缓冲（契约：X-Accel-Buffering: no）
        resp.setHeader("X-Accel-Buffering", "no");
        resp.setHeader("Cache-Control", "no-cache");

        // 用父类 ResponseBodyEmitter：SseEmitter 的 send 会追加 data: 前缀破坏自组帧格式
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(SSE_TIMEOUT_MS);
        SseSession sse = new SseSession(emitter, heartbeatScheduler);
        emitter.onCompletion(sse::close);
        emitter.onError(e -> sse.close());
        emitter.onTimeout(sse::close);

        try {
            // 在请求线程上解析服务端识别的用户并显式传参——ThreadLocal 不随线程池切换传播，
            // 若在工作线程再读会丢失身份、回退到请求体 userId（冒充风险）
            Long contextUser = com.tutor.common.context.UserContext.getUserOrNull();
            sseExecutor.execute(() -> runner.run(req, contextUser, sse));
        } catch (RejectedExecutionException e) {
            sse.error("服务繁忙，请稍后重试");
            sse.done(Map.of());
            sse.close();
        }
        return emitter;
    }
}
