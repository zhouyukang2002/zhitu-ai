package com.tutor.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.HttpComponentsClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * LLM HTTP 客户端配置（P0 双修复：超时 + 陈旧连接）。
 *
 * 事故复盘一（超时）：classpath 无 httpclient5 时 Boot 退回 JDK HttpClient，
 * 连接/读取超时默认无限——网络抖动的半开连接永久占死流水线线程。
 *
 * 事故复盘二（陈旧连接复用，首字 183s）：JDK 17 的 keepalive 默认 1200s（JDK 20 才降为 30s），
 * 而 LLM 服务端负载均衡几十秒就静默掐断空闲连接——空闲后的第一条请求写进"已死"的池化连接，
 * 读超时 60s × 重试 3 次 = 183s 才降级（意图日志实证 costMs=183039）。
 *
 * 修复（业界标准，Apache HttpClient 5）：
 * 1. validateAfterInactivity(1s)：复用前廉价校验连接死活，死连接直接弃用重连；
 * 2. evictIdleConnections(30s) + evictExpiredConnections：后台线程驱逐空闲连接，
 *    客户端先于服务端掐断，陈旧窗口归零；
 * 3. 超时全部有界：连接 5s，同步读取 60s，流式 socket 空闲 120s。
 */
@Configuration
public class HttpTimeoutConfig {

    /** 同步客户端（意图识别/批改/推荐理由）：读取 60s */
    @Bean(destroyMethod = "close")
    public CloseableHttpClient llmHttpClient() {
        ConnectionConfig connConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(5))
                .setSocketTimeout(Timeout.ofSeconds(60))
                .setValidateAfterInactivity(TimeValue.ofSeconds(1))
                .build();
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connConfig)
                .setMaxConnPerRoute(16)
                .setMaxConnTotal(32)
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .evictExpiredConnections()
                .build();
    }

    /** 异步客户端（讲解/闲聊流式）：socket 空闲 120s（逐 token 出流，空闲窗口足够宽） */
    @Bean(destroyMethod = "close")
    public CloseableHttpAsyncClient llmHttpAsyncClient() {
        ConnectionConfig connConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(5))
                .setSocketTimeout(Timeout.ofSeconds(120))
                .setValidateAfterInactivity(TimeValue.ofSeconds(1))
                .build();
        var connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connConfig)
                .setMaxConnPerRoute(16)
                .setMaxConnTotal(32)
                .build();
        CloseableHttpAsyncClient client = HttpAsyncClients.custom()
                .setConnectionManager(connectionManager)
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .evictExpiredConnections()
                .build();
        client.start();
        return client;
    }

    @Bean
    @Scope("prototype")
    public RestClient.Builder restClientBuilder(CloseableHttpClient llmHttpClient) {
        return RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(llmHttpClient));
    }

    @Bean
    @Scope("prototype")
    public WebClient.Builder webClientBuilder(CloseableHttpAsyncClient llmHttpAsyncClient) {
        return WebClient.builder().clientConnector(new HttpComponentsClientHttpConnector(llmHttpAsyncClient));
    }
}
