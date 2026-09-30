package com.tutor.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.Retry;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM 防腐层：所有智能体只通过本网关访问大模型能力，不直接依赖任何厂商 SDK 类型
 * （模型可替换、降级逻辑集中、面试可讲"模型层容错"的实现载体）。
 * 运行时选项用通用 ChatOptions（model/temperature），DashScope / OpenAI 兼容端点（DeepSeek）皆可用。
 *
 * 可观测性：usageHolder 非空时，流式/同步调用均捕获 Token 消耗（usage 在最后一个 chunk，
 * OpenAI 兼容协议 stream_options.include_usage），供观测层 LlmUsageHolder 聚合。
 *
 * 可用性判定：模型自动装配未生效（如未配置 api-key / 开关关闭）时 ChatModel Bean 不存在，
 * available()=false，上层智能体全部走规则/模板降级（全链路 Fallback）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmGateway {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectProvider<ChatMemory> chatMemoryProvider;
    private final AppProperties props;

    /** ChatClient 从 ChatModel 惰性构建（避免配置类 @ConditionalOnBean 的装配顺序问题） */
    private volatile ChatClient lazyClient;

    /** 舱壁隔离：限制并发 LLM 操作数，饱和时快速失败走降级链（关键词路由/知识库直出），
     *  保证 LLM 瘫痪/变慢时业务线程不被集体拖死——对应"分层容错-模型层"。 */
    private Semaphore bulkhead;
    private CircuitBreaker circuitBreaker;
    private Retry retry;

    @PostConstruct
    void initBulkhead() {
        bulkhead = new Semaphore(props.getAi().getBulkheadPermits());
        circuitBreaker = CircuitBreaker.of("llm-gateway",
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .waitDurationInOpenState(java.time.Duration.ofSeconds(30))
                        .build());
        retry = Retry.of("llm-gateway",
                RetryConfig.custom()
                        .maxAttempts(2)
                        .waitDuration(java.time.Duration.ofSeconds(1))
                        .build());
    }

    /** 获取舱壁许可：10 秒拿不到即视为 LLM 通道饱和，抛出降级 */
    private void acquirePermit() {
        try {
            if (!bulkhead.tryAcquire(10, TimeUnit.SECONDS)) {
                throw new LlmUnavailableException("LLM 舱壁饱和（并发>" + props.getAi().getBulkheadPermits() + "）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("线程中断");
        }
    }

    public boolean available() {
        return chatModelProvider.getIfAvailable() != null;
    }

    private ChatClient requireClient() {
        if (!available()) {
            throw new LlmUnavailableException("LLM 未配置（未装配 ChatModel）");
        }
        if (lazyClient == null) {
            synchronized (this) {
                if (lazyClient == null) {
                    lazyClient = ChatClient.builder(chatModelProvider.getObject()).build();
                }
            }
        }
        return lazyClient;
    }

    /** 流式输出（无记忆）——讲解等一次性上下文场景；tools 为 Function Calling 白名单 */
    public Flux<String> stream(String system, String user, String model, Double temperature,
                               LlmUsageHolder usage, Object... tools) {
        acquirePermit();
        try {
            long start = System.currentTimeMillis();
            AtomicReference<Usage> captured = new AtomicReference<>();
            var spec = requireClient().prompt()
                    .system(system)
                    .user(user)
                    .options(options(model, temperature));
            if (tools != null && tools.length > 0) {
                spec = spec.tools(tools);
            }
            // 许可随流的终止/取消释放（用户 abort 触发 doFinally）
            return spec.stream().chatResponse()
                    .doOnNext(cr -> capture(usage, captured, cr.getMetadata()))
                    .mapNotNull(cr -> cr.getResult() == null || cr.getResult().getOutput() == null
                            ? null : cr.getResult().getOutput().getText())
                    .doFinally(sig -> {
                        finish(usage, captured, model, start);
                        bulkhead.release();
                    });
        } catch (RuntimeException e) {
            bulkhead.release();
            throw e;
        }
    }

    /** 流式输出（带 Redis 短期记忆，滑动窗口）——闲聊多轮场景 */
    public Flux<String> streamWithMemory(String system, String user, String conversationId,
                                         String model, Double temperature, LlmUsageHolder usage) {
        ChatMemory memory = chatMemoryProvider.getIfAvailable();
        if (memory == null) {
            return stream(system, user, model, temperature, usage);
        }
        acquirePermit();
        try {
            long start = System.currentTimeMillis();
            AtomicReference<Usage> captured = new AtomicReference<>();
            return requireClient().prompt()
                    .system(system)
                    .user(user)
                    .options(options(model, temperature))
                    .advisors(MessageChatMemoryAdvisor.builder(memory).build())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .stream()
                    .chatResponse()
                    .doOnNext(cr -> capture(usage, captured, cr.getMetadata()))
                    .mapNotNull(cr -> cr.getResult() == null || cr.getResult().getOutput() == null
                            ? null : cr.getResult().getOutput().getText())
                    .doFinally(sig -> {
                        finish(usage, captured, model, start);
                        bulkhead.release();
                    });
        } catch (RuntimeException e) {
            bulkhead.release();
            throw e;
        }
    }

    /** 结构化 JSON 调用（意图识别/简答批改）：剥代码围栏再解析，解析失败重试 1 次 */
    public JsonNode callJson(String system, String user, String model, Double temperature, LlmUsageHolder usage) {
        acquirePermit();
        try {
            long start = System.currentTimeMillis();
            AtomicReference<Usage> captured = new AtomicReference<>();
            // Resilience4j 熔断+重试：挂起超时/网络抖动/格式异常统一纳入容错统计
            var chatResponse = CircuitBreaker.decorateSupplier(circuitBreaker,
                    Retry.decorateSupplier(retry, () ->
                        requireClient().prompt()
                            .system(system)
                            .user(user)
                            .options(options(model, temperature))
                            .call()
                            .chatResponse()
                    )).get();
            String content = chatResponse.getResult().getOutput().getText();
            capture(usage, captured, chatResponse.getMetadata());
            finish(usage, captured, model, start);
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    return MAPPER.readTree(stripFences(content));
                } catch (Exception e) {
                    log.warn("LLM JSON 解析失败（第 {} 次）：{}", attempt, content);
                    if (attempt == 2) {
                        throw new LlmUnavailableException("LLM 输出格式非法");
                    }
                }
            }
            throw new LlmUnavailableException("unreachable");
        } finally {
            bulkhead.release();
        }
    }

    /** 同步纯文本调用（推荐理由、长期记忆抽取等单次调用） */
    public String callText(String system, String user, String model, Double temperature, LlmUsageHolder usage) {
        acquirePermit();
        try {
            long start = System.currentTimeMillis();
            AtomicReference<Usage> captured = new AtomicReference<>();
            var chatResponse = requireClient().prompt()
                    .system(system)
                    .user(user)
                    .options(options(model, temperature))
                    .call()
                    .chatResponse();
            String content = chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null
                    ? chatResponse.getResult().getOutput().getText() : "";
            capture(usage, captured, chatResponse.getMetadata());
            finish(usage, captured, model, start);
            return content;
        } finally {
            bulkhead.release();
        }
    }

    /** 兼容旧签名（无观测需求处） */
    public JsonNode callJson(String system, String user, String model, Double temperature) {
        return callJson(system, user, model, temperature, null);
    }

    private void capture(LlmUsageHolder usage, AtomicReference<Usage> captured, ChatResponseMetadata metadata) {
        if (usage != null && metadata != null) {
            usage.capture(captured, metadata.getUsage());
        }
    }

    private void finish(LlmUsageHolder usage, AtomicReference<Usage> captured, String model, long start) {
        if (usage != null) {
            usage.complete(model, captured.get(), System.currentTimeMillis() - start);
        }
    }

    /** 运行时选项：通用 ChatOptions（厂商无关），model/temperature 由各提供方合并进请求 */
    private ChatOptions options(String model, Double temperature) {
        return ChatOptions.builder()
                .model(model != null ? model : props.getAi().getChatModel())
                .temperature(temperature != null ? temperature : 0.7)
                .build();
    }

    /** 剥掉 ```json ... ``` 围栏 */
    static String stripFences(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('\n');
            int last = trimmed.lastIndexOf("```");
            if (first > 0 && last > first) {
                return trimmed.substring(first + 1, last).trim();
            }
        }
        return trimmed;
    }

    /**
     * 启动探活（P0-4）：就绪后异步探测一次 LLM 连通性，
     * 失败打 WARN 提前暴露 Key/网络问题（顺带验证配置），期间全链路降级照常可用。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void healthProbe() {
        if (!available()) {
            log.info("LLM 未配置：全链路降级模式（关键词路由/规则判分/知识库直出）");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                String answer = requireClient().prompt()
                        .user("ping")
                        .options(ChatOptions.builder().maxTokens(8).build())
                        .call()
                        .content();
                log.info("LLM 健康探测成功，模型在线（响应: {}）", answer);
            } catch (Exception e) {
                log.warn("LLM 健康探测失败，请检查 Key/网络；期间全链路降级可用: {}", e.getMessage());
            }
        });
    }

    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message) {
            super(message);
        }
    }
}
