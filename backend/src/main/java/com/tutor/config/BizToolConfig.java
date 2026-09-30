package com.tutor.config;

import com.tutor.tool.BizToolBridge;
import com.tutor.tool.CourseTools;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 业务工具桥装配 + Resilience4j 容错策略。
 * MCP Server（tutor-mcp-server:8081）正常时走 MCP 发现的工具回调；
 * MCP 不可用/降级时回退进程内直调 @Tool 对象——全链路 Fallback 的一环。
 */
@Slf4j
@Configuration
public class BizToolConfig {

    @Bean
    public BizToolBridge bizToolBridge(CourseTools localCourseTools) {
        // BizToolBridge 自行通过 Spring 注入 MCP ToolCallback 列表
        // 这里只传降级用的本地工具
        return new BizToolBridge(null, "local", localCourseTools);
    }

    /** Resilience4j 熔断策略（LLM 调用 + MCP 调用共享） */
    @Bean
    public io.github.resilience4j.circuitbreaker.CircuitBreaker llmCircuitBreaker() {
        return io.github.resilience4j.circuitbreaker.CircuitBreaker.of("llm",
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .build());
    }

    @Bean
    public io.github.resilience4j.retry.Retry bizRetry() {
        return io.github.resilience4j.retry.Retry.of("biz",
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofSeconds(1))
                        .retryExceptions(Exception.class)
                        .build());
    }
}
