package com.tutor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class TutorEngineApplication {

    public static void main(String[] args) {
        // 兜底：JDK 17 的 HttpClient keepalive 默认 1200s（JDK 20 才降为 30s），
        // 缩短客户端空闲保活，先于服务端掐断陈旧连接（LLM 主链路已迁移 Apache HttpClient 5）
        System.setProperty("jdk.httpclient.keepalive.timeout", "30");
        SpringApplication.run(TutorEngineApplication.class, args);
    }
}
