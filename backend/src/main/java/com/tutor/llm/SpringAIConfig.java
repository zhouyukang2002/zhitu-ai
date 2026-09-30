package com.tutor.llm;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 装配。
 *
 * 配置要点：
 * 1. 去掉 defaultTools 重复注册 bug（原代码 .defaultTools(courseTools) 后又
 *    .defaultTools(courseTools, orderTools)，courseTools 注册了两次）；
 *    工具改为按智能体在调用点白名单挂载（工具最小暴露面，防业务幻觉）。
 * 2. ChatClient 不在配置类里定义（@ConditionalOnBean 在普通配置类上有装配顺序问题，
 *    会早于模型自动装配判定导致永远不生效）——由 LlmGateway 从 ChatModel 惰性构建。
 * 3. 未配置 API Key 时 ChatModel Bean 不存在，全链路走降级模式。
 */
@Configuration
public class SpringAIConfig {

    /** 短期记忆：Redis 仓储 + 滑动窗口（20 条），超窗自动舍弃最旧消息 */
    @Bean
    public ChatMemory chatMemory(RedisChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();
    }
}
