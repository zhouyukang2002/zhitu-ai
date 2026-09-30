package com.tutor.exercise;

import com.tutor.common.constant.TutorKeys;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.common.exception.BizException;
import com.tutor.exercise.entity.ExerciseEntity;
import com.tutor.exercise.entity.GradeResultEntity;
import com.tutor.exercise.mapper.ExerciseMapper;
import com.tutor.exercise.mapper.GradeResultMapper;
import com.tutor.client.QuestionBankClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 练习域存储服务：出题登记 + 批改结果持久化 + 幂等。
 * 幂等双层：Redis SETNX 快路径 + grade_result.exercise_id 唯一约束兜底
 * （契约 1.5：同 exerciseId 重复提交直接返回原结果）。
 * 答案键随卷存储：LLM 现场出题/私有题库的答案在出题时即落卷（answer_keys_json），
 * 批改读取卷面答案键而非再次生成——保证"出题与批改同一把尺子"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExerciseService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String IDEMPOTENT_PREFIX = TutorKeys.GRADE_IDEMPOTENT;

    private final ExerciseMapper exerciseMapper;
    private final GradeResultMapper gradeMapper;
    private final StringRedisTemplate redis;

    /** 出题登记（题库直选路径，无独立答案键） */
    public String register(String conversationId, List<QuestionBankClient.Question> questions, List<String> kps) {
        return register(conversationId, questions, kps, null);
    }

    /** 出题登记（携带答案键：LLM 现场出题/私有题库路径），keys 为 qid → 答案键 Map */
    public String register(String conversationId, List<QuestionBankClient.Question> questions,
                           List<String> kps, Map<String, Map<String, Object>> keys) {
        String exerciseId = "e_" + IdUtil.fastSimpleUUID().substring(0, 12);
        ExerciseEntity e = new ExerciseEntity();
        e.setExerciseId(exerciseId);
        e.setConversationId(conversationId);
        try {
            e.setQuestionsJson(MAPPER.writeValueAsString(questions));
            e.setAnswerKeysJson(keys == null || keys.isEmpty() ? null : MAPPER.writeValueAsString(keys));
        } catch (Exception ex) {
            throw new IllegalStateException("题目序列化失败", ex);
        }
        e.setKnowledgePoints(String.join(",", kps));
        e.setCreatedAt(LocalDateTime.now());
        exerciseMapper.insert(e);
        return exerciseId;
    }

    public ExerciseEntity require(String exerciseId) {
        ExerciseEntity e = exerciseMapper.selectById(exerciseId);
        if (e == null) {
            throw BizException.notFound("练习不存在");
        }
        return e;
    }

    public List<QuestionBankClient.Question> questionsOf(ExerciseEntity exercise) {
        try {
            return MAPPER.readValue(exercise.getQuestionsJson(),
                    new TypeReference<List<QuestionBankClient.Question>>() {
                    });
        } catch (Exception e) {
            throw new IllegalStateException("题目反序列化失败", e);
        }
    }

    /** 卷面答案键（可能为空：纯题库直选历史卷），qid → {answer/analysis/keywords/score} */
    public Map<String, Map<String, Object>> answerKeysOf(ExerciseEntity exercise) {
        if (exercise.getAnswerKeysJson() == null || exercise.getAnswerKeysJson().isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(exercise.getAnswerKeysJson(),
                    new TypeReference<Map<String, Map<String, Object>>>() {
                    });
        } catch (Exception e) {
            log.warn("答案键反序列化失败: {}", e.getMessage());
            return Map.of();
        }
    }

    public List<String> knowledgePointsOf(ExerciseEntity exercise) {
        return splitKps(exercise.getKnowledgePoints());
    }

    private List<String> splitKps(String kps) {
        return kps == null ? List.of() : List.of(kps.split(","));
    }

    /** 幂等查询：命中返回原结果 */
    public GradeResult findGrade(String exerciseId) {
        try {
            String cached = redis.opsForValue().get(IDEMPOTENT_PREFIX + exerciseId);
            if (cached != null) {
                return MAPPER.readValue(cached, GradeResult.class);
            }
        } catch (Exception e) {
            log.debug("幂等缓存读取失败，回退 DB: {}", e.getMessage());
        }
        GradeResultEntity entity = gradeMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GradeResultEntity>()
                        .eq(GradeResultEntity::getExerciseId, exerciseId));
        return entity == null ? null : toResult(entity);
    }

    public void saveGrade(GradeResult grade, String conversationId) {
        GradeResultEntity entity = new GradeResultEntity();
        entity.setExerciseId(grade.getExerciseId());
        entity.setConversationId(conversationId);
        entity.setScore(grade.getScore());
        entity.setTotalScore(grade.getTotalScore());
        try {
            entity.setPerQuestionJson(MAPPER.writeValueAsString(grade.getPerQuestion()));
        } catch (Exception e) {
            entity.setPerQuestionJson("[]");
        }
        entity.setKnowledgePoints(String.join(",", grade.getKnowledgePoints()));
        entity.setCreatedAt(LocalDateTime.now());
        gradeMapper.insert(entity);
        try {
            // TTL 7 天：演示期内的快速幂等路径；DB 唯一约束兜底长期幂等
            redis.opsForValue().set(IDEMPOTENT_PREFIX + grade.getExerciseId(),
                    MAPPER.writeValueAsString(grade), Duration.ofDays(7));
        } catch (Exception e) {
            log.warn("幂等缓存写入失败（DB 兜底）: {}", e.getMessage());
        }
    }

    /** 会话最近一次批改得分（state 接口 lastGrade 数据源） */
    public Integer findLatestScore(String conversationId) {
        GradeResultEntity entity = gradeMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GradeResultEntity>()
                        .eq(GradeResultEntity::getConversationId, conversationId)
                        .orderByDesc(GradeResultEntity::getId)
                        .last("LIMIT 1"));
        return entity == null ? null : entity.getScore();
    }

    private GradeResult toResult(GradeResultEntity entity) {
        List<GradeResult.PerQuestion> perQuestion;
        try {
            perQuestion = MAPPER.readValue(entity.getPerQuestionJson(),
                    new TypeReference<List<GradeResult.PerQuestion>>() {
                    });
        } catch (Exception e) {
            perQuestion = List.of();
        }
        return new GradeResult(entity.getExerciseId(), entity.getTotalScore(), entity.getScore(),
                perQuestion, splitKps(entity.getKnowledgePoints()));
    }

    /** grade 卡片 content（含 Map 形态工具方法） */
    public Map<String, Object> toContent(GradeResult grade) {
        return MAPPER.convertValue(grade, new TypeReference<Map<String, Object>>() {
        });
    }
}
