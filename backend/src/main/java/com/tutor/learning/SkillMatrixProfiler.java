package com.tutor.learning;

import com.tutor.common.constant.TutorKeys;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.knowledge.SkillDictionary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 全局学员技能矩阵画像智能体（Skill Matrix Profiler）：
 * 聚合学员的练习做题记录、错题归因与诊断结论，在跨会话维度构建结构化技能能力树与掌握度画像。
 * 为后续会话提供自适应开场白引导与差异化教学策略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillMatrixProfiler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REDIS_KEY_PREFIX = TutorKeys.USER_SKILL_MATRIX;

    private final StringRedisTemplate redisTemplate;
    private final SkillDictionary skillDictionary;

    // 内存兜底缓存（单机降级）
    private final ConcurrentHashMap<Long, SkillProfile> localCache = new ConcurrentHashMap<>();

    // 预置四大核心技术赛道
    public static final List<String> TRACKS = List.of("java-backend", "data-analytics", "ai-app", "frontend");

    /**
     * 记录一次答题评测，动态更新学员技能树
     */
    public void recordAnswer(Long userId, String kp, boolean correct) {
        if (userId == null || kp == null || kp.isBlank()) return;
        try {
            SkillProfile profile = getProfile(userId);
            profile.updateSkill(kp, correct);
            saveProfile(userId, profile);
            log.info("学员 [{}] 技能树已更新：考点 [{}] 结果={}", userId, kp, correct ? "正确" : "错误");
        } catch (Exception e) {
            log.warn("更新学员技能树异常: {}", e.getMessage());
        }
    }

    /**
     * 获取学员技能矩阵全貌
     */
    public SkillProfile getProfile(Long userId) {
        if (userId == null) return new SkillProfile(userId);
        try {
            String key = REDIS_KEY_PREFIX + userId;
            String json = redisTemplate.opsForValue().get(key);
            if (json != null && !json.isBlank()) {
                return MAPPER.readValue(json, SkillProfile.class);
            }
        } catch (Exception e) {
            log.debug("Redis 获取技能矩阵异常，使用内存缓存: {}", e.getMessage());
        }
        return localCache.computeIfAbsent(userId, SkillProfile::new);
    }

    /**
     * 持久化技能画像
     */
    public void saveProfile(Long userId, SkillProfile profile) {
        if (userId == null || profile == null) return;
        localCache.put(userId, profile);
        try {
            String key = REDIS_KEY_PREFIX + userId;
            String json = MAPPER.writeValueAsString(profile);
            redisTemplate.opsForValue().set(key, json, 30, TimeUnit.DAYS);
        } catch (Exception e) {
            log.debug("Redis 存储技能矩阵异常: {}", e.getMessage());
        }
    }

    /**
     * 生成自适应开场白/导学提示
     */
    public Optional<String> generateAdaptiveGreeting(Long userId) {
        SkillProfile profile = getProfile(userId);
        if (profile == null || profile.getTotalAnswers() < 2) {
            return Optional.empty();
        }

        // 寻找掌握度最低且练习过的考点
        String focusKp = profile.getLowestMasteryKp();
        if (focusKp != null) {
            return Optional.of("欢迎回来！检测到你上次在【" + focusKp + "】相关的实战题目中还有疑问，今天我们是针对它进行专题强化，还是开启新的技术主题？");
        }
        return Optional.empty();
    }

    /**
     * 技能画像实体模型
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    public static class SkillProfile {
        private Long userId;
        private int totalAnswers = 0;
        private int totalCorrect = 0;
        // 知识点维度统计：kp -> [总次数, 正确次数]
        private Map<String, int[]> kpStats = new LinkedHashMap<>();

        public SkillProfile(Long userId) {
            this.userId = userId;
        }

        public synchronized void updateSkill(String kp, boolean correct) {
            totalAnswers++;
            if (correct) totalCorrect++;
            int[] stats = kpStats.computeIfAbsent(kp, k -> new int[]{0, 0});
            stats[0]++; // 总次数
            if (correct) stats[1]++; // 正确次数
        }

        public double getOverallAccuracy() {
            return totalAnswers == 0 ? 0.0 : Math.round((double) totalCorrect / totalAnswers * 100.0) / 100.0;
        }

        public String getLowestMasteryKp() {
            String lowestKp = null;
            double lowestRate = 1.0;
            for (Map.Entry<String, int[]> entry : kpStats.entrySet()) {
                int total = entry.getValue()[0];
                int correct = entry.getValue()[1];
                if (total >= 1) {
                    double rate = (double) correct / total;
                    if (rate < lowestRate) {
                        lowestRate = rate;
                        lowestKp = entry.getKey();
                    }
                }
            }
            return lowestKp;
        }
    }
}
