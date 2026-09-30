package com.tutor.biz.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.biz.model.BizModels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 课程私有题库服务（业务真相层，读 tutor_biz.question_bank）：
 * 题库是课程的付费权益——未购课用户在工具层即被拦截，模型上下文里永远不存在未购课程的题目数据
 * （授权收敛在业务系统层而非提示词层，即使 LLM 被注入也无法越权取题）。
 * 防泄题：getQuestions 不下发答案字段；getAnswerKey 仅批改链路且同样鉴权。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionBankService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final OrderService orderService;

    /**
     * 从课程私有题库抽题（不含答案字段）。
     *
     * @throws IllegalArgumentException 未购课程时抛出（调用方应向用户转达"购买后解锁"）
     */
    public List<BizModels.QuestionItem> getQuestions(Long userId, String courseId,
                                                     String kp, String type, String difficulty, Integer count) {
        requirePurchased(userId, courseId);
        int n = count == null || count <= 0 ? 3 : Math.min(count, 5);
        StringBuilder sql = new StringBuilder(
                "SELECT id, kp, type, difficulty, score, stem, options FROM question_bank WHERE course_id=?");
        List<Object> args = new ArrayList<>();
        args.add(courseId);
        if (StrUtil.isNotBlank(kp)) {
            sql.append(" AND kp=?");
            args.add(kp);
        }
        if (StrUtil.isNotBlank(type)) {
            sql.append(" AND type=?");
            args.add(mapType(type));
        }
        if (StrUtil.isNotBlank(difficulty)) {
            sql.append(" AND difficulty=?");
            args.add(difficulty);
        }
        sql.append(" ORDER BY id LIMIT ?");
        args.add(n);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<BizModels.QuestionItem> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            result.add(new BizModels.QuestionItem(
                    (String) row.get("id"), (String) row.get("kp"), (String) row.get("type"),
                    (String) row.get("difficulty"), (String) row.get("stem"),
                    parseList((String) row.get("options")),
                    row.get("score") == null ? 5 : ((Number) row.get("score")).intValue()));
        }
        log.info("私有题库抽题: userId={}, courseId={}, 命中 {} 题", userId, courseId, result.size());
        return result;
    }

    /** 答案与评分要点（批改链路专用）：按题目归属课程鉴权，同样要求已购 */
    public List<BizModels.AnswerKey> getAnswerKey(Long userId, List<String> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return List.of();
        }
        String placeholders = questionIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, kp, answer, analysis, keywords, score, course_id FROM question_bank WHERE id IN (" + placeholders + ")",
                questionIds.toArray());
        List<BizModels.AnswerKey> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String courseId = (String) row.get("course_id");
            requirePurchased(userId, courseId);
            result.add(new BizModels.AnswerKey((String) row.get("id"), (String) row.get("answer"),
                    (String) row.get("analysis"), parseList((String) row.get("keywords")),
                    row.get("score") == null ? 5 : ((Number) row.get("score")).intValue()));
        }
        return result;
    }

    /** 权限拦截：题库是课程付费权益，未购课直接拒绝（工具层防线，不依赖提示词） */
    private void requirePurchased(Long userId, String courseId) {
        if (userId == null || StrUtil.isBlank(courseId)) {
            throw new IllegalArgumentException("NOT_PURCHASED:访问私有题库必须提供用户与课程标识");
        }
        if (!orderService.isPaid(userId, courseId)) {
            log.info("题库权限拦截: userId={}, courseId={} 未购课", userId, courseId);
            throw new IllegalArgumentException("NOT_PURCHASED:该课程的私有题库为付费权益，购买后解锁");
        }
    }

    private String mapType(String type) {
        // 兼容中英文题型表述
        return switch (type) {
            case "选择题" -> "choice";
            case "简答题", "解答题", "填空题" -> "short";
            default -> type;
        };
    }

    private List<String> parseList(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }
}
