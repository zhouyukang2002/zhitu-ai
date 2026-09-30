package com.tutor.client;

import cn.hutool.core.io.FileUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * 题库服务本地 Mock 实现（防腐层）。数据面：data/question-bank.json。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalQuestionBankClient implements QuestionBankClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppProperties props;

    private List<Question> questions = List.of();

    @PostConstruct
    public void load() {
        try {
            File file = new File(props.getDataDir(), "question-bank.json");
            questions = MAPPER.readValue(FileUtil.readUtf8String(file), new TypeReference<>() {
            });
            log.info("题库 Mock 数据加载完成：{} 道题", questions.size());
        } catch (Exception e) {
            log.error("题库加载失败", e);
        }
    }

    @Override
    public List<Question> byKp(String kp, String type) {
        return questions.stream()
                .filter(q -> q.getKp().equals(kp) && (type == null || q.getType().equals(type)))
                .toList();
    }

    @Override
    public List<Question> byCourseOrKp(String courseId, String kp, String type) {
        if (courseId != null && !courseId.isBlank()) {
            List<Question> courseQuestions = questions.stream()
                    .filter(q -> courseId.equals(q.getCourseId()) && (kp == null || q.getKp().equals(kp))
                            && (type == null || q.getType().equals(type)))
                    .toList();
            if (!courseQuestions.isEmpty()) {
                return courseQuestions;
            }
        }
        return byKp(kp, type);
    }

    @Override
    public Question byId(String id) {
        return questions.stream().filter(q -> q.getId().equals(id)).findFirst().orElse(null);
    }
}
