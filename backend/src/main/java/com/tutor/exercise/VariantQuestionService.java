package com.tutor.exercise;

import com.fasterxml.jackson.databind.JsonNode;
import com.tutor.agent.CriticValidator;
import com.tutor.client.QuestionBankClient;
import com.tutor.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 举一反三·变式题生成服务（AI Variant Question Synthesizer）：
 * 针对学生做错的原题，提取核心考点与解题逻辑，现场变换数字、背景或设问角度，生成一道同构变式题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VariantQuestionService {

    private final LlmGateway gateway;
    private final CriticValidator criticValidator;
    private final com.tutor.config.AppProperties props;

    /**
     * 生成同构变式题
     */
    public QuestionBankClient.Question generateVariant(QuestionBankClient.Question origin, String errorType) {
        if (origin == null) {
            return fallbackVariant("JavaSE 基础", "choice");
        }

        if (gateway.available()) {
            try {
                String system = """
                        你是资深在线职业教育技术专家与技术面试命题导师。
                        根据学员的做题记录与错误类型，为同一个【技术考点】设计一道【新的变式考核题】（考察相同原理，但使用不同的业务代码场景或设问角度），帮助学员举一反三。
                        必须且仅输出合法的 JSON 格式，字段如下：
                        {
                          "stem": "题目题干文本（代码块使用标准代码格式）",
                          "type": "choice",
                          "options": ["A. ...", "B. ...", "C. ...", "D. ..."],
                          "answer": "A",
                          "analysis": "详细原理解析与解题思路",
                          "keywords": ["关键词1", "关键词2"],
                          "score": 10
                        }
                        """;

                String userPrompt = "【原题技术考点】：" + origin.getKp() + "\n"
                        + "【原题题型】：" + origin.getType() + "\n"
                        + "【原题题干】：" + origin.getStem() + "\n"
                        + "【学员易错归因】：" + (errorType != null ? errorType : "概念模糊/理解偏差") + "\n"
                        + "【原题参考答案】：" + (origin.getAnswer() != null ? origin.getAnswer() : origin.getReference());

                JsonNode node = gateway.callJson(system, userPrompt, props.getAi().getChatModel(), 0.7);

                // Critic 质检把关：检查题干、选项有效性与答案对齐度
                var criticResult = criticValidator.validateQuestion(node);
                if (!criticResult.passed()) {
                    log.warn("Critic 质检拦截变式题 [{}]: {}，启动兜底保护", origin.getKp(), criticResult.message());
                    return fallbackVariant(origin.getKp(), origin.getType());
                }

                QuestionBankClient.Question variant = new QuestionBankClient.Question();
                variant.setId("var_" + System.currentTimeMillis() % 100000);
                variant.setKp(origin.getKp());
                variant.setType(node.path("type").asText(origin.getType()));
                variant.setStem("【举一反三·变式训练】" + node.path("stem").asText(""));
                variant.setScore(node.path("score").asInt(origin.getScore() > 0 ? origin.getScore() : 10));

                if ("choice".equals(variant.getType()) && node.has("options") && node.get("options").isArray()) {
                    List<String> opts = new ArrayList<>();
                    node.get("options").forEach(o -> opts.add(o.asText()));
                    variant.setOptions(opts);
                    variant.setAnswer(node.path("answer").asText("A"));
                } else {
                    variant.setReference(node.path("reference").asText(""));
                    List<String> kws = new ArrayList<>();
                    if (node.has("keywords") && node.get("keywords").isArray()) {
                        node.get("keywords").forEach(k -> kws.add(k.asText()));
                    }
                    variant.setKeywords(kws.isEmpty() ? List.of(origin.getKp()) : kws);
                }

                log.info("成功为知识点 [{}] 生成变式题: {}", origin.getKp(), variant.getStem());
                return variant;
            } catch (Exception e) {
                log.warn("大模型生成变式题失败，触发规则变式: {}", e.getMessage());
            }
        }

        return fallbackVariant(origin.getKp(), origin.getType());
    }

    private QuestionBankClient.Question fallbackVariant(String kp, String type) {
        QuestionBankClient.Question q = new QuestionBankClient.Question();
        q.setId("var_" + System.currentTimeMillis() % 100000);
        q.setKp(kp != null && !kp.isBlank() ? kp : "JavaSE 基础");
        q.setType(type != null ? type : "choice");
        q.setScore(10);
        if ("choice".equals(q.getType())) {
            q.setStem("【举一反三·变式题】在 Java 集合框架中，关于 HashMap 与 ConcurrentHashMap 的描述，下列说法正确的是？");
            q.setOptions(List.of(
                    "A. HashMap 在多线程并发 put 时是绝对线程安全的",
                    "B. ConcurrentHashMap 在 JDK 8 中使用分段锁 Segment 保持不变",
                    "C. ConcurrentHashMap 在 JDK 8 中采用 Node 数组 + CAS + synchronized 细化锁粒度",
                    "D. HashMap 允许有多个 null 键"
            ));
            q.setAnswer("C");
            q.setReference("JDK 8 中 ConcurrentHashMap 废弃了 Segment 分段锁，改用 CAS + synchronized 对单个桶的头节点加锁，并发度更高；HashMap 是非线程安全的，且只允许一个 null 键。故选 C。");
        } else {
            q.setStem("【举一反三·变式题】简述 Spring 框架中 Bean 的生命周期包含哪些核心阶段？");
            q.setReference("核心阶段包括：1) 实例化 Bean（反射构造）；2) 属性赋值（依赖注入）；3) 检查 Aware 接口并注入环境资源；4) BeanPostProcessor 前置处理；5) 初始化（@PostConstruct / InitializingBean）；6) BeanPostProcessor 后置处理（可能生成 AOP 代理）；7) 使用中；8) 销毁。");
            q.setKeywords(List.of("实例化", "属性注入", "Aware", "BeanPostProcessor", "初始化", "销毁"));
        }
        return q;
    }
}
