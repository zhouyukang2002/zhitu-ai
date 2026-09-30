package com.tutor.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 8.x 官方客户端配置（支持 Dense Vector HNSW + BM25 + RRF 融合检索）。
 * 针对本地开发支持一键免密与自动探活降级。
 */
@Slf4j
@Configuration
public class ElasticsearchConfig {

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String serverUrl;

    @Bean(destroyMethod = "close")
    public RestClient esRestClient() {
        HttpHost host = HttpHost.create(serverUrl);
        return RestClient.builder(host)
                .setRequestConfigCallback(requestConfigBuilder ->
                        requestConfigBuilder
                                .setConnectTimeout(3000)
                                .setSocketTimeout(10000))
                .build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient esRestClient) {
        RestClientTransport transport = new RestClientTransport(
                esRestClient, new JacksonJsonpMapper()
        );
        return new ElasticsearchClient(transport);
    }
}
