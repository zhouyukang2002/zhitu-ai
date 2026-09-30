package com.tutor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程模型（对应《后端开发注意事项》2.2 的「线程阻塞」坑）：
 * - Tomcat 工作线程在 Controller 里立即返回 SseEmitter，不等待 LLM；
 * - 每轮对话的流水线跑在独立 sseExecutor 线程池，回调和发送都在该线程池做；
 * - 心跳用独立的 1 线程调度器。
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "sseExecutor")
    public ThreadPoolTaskExecutor sseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        // 队列必须浅：ThreadPoolExecutor 的扩容条件是"队列满"，
        // 200 深队列曾配合无超时的 LLM 调用把 8 个核心线程全部锁死且永远扩不了容（服务假死）。
        // 收紧队列让突发流量扩到 max，超出部分走 AbortPolicy 快速失败——前端错误条可重试，
        // 好过排队数分钟才得到响应
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("sse-pipe-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /** SSE 心跳调度器：注释帧防长静默断连 */
    @Bean(name = "sseHeartbeatScheduler")
    public ScheduledExecutorService sseHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("sse-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler.getScheduledExecutor();
    }
}
