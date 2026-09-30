package com.tutor.learning;

import com.tutor.learning.entity.LearningRecordEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 认知诊断（简化版，纯领域算法可单测）：
 * 正确率为主 + 耗时加权（效率修正），输出薄弱点列表（升序）+ 置信度。
 * 面试锚点：为什么不用 LLM 做诊断——可解释、零成本、可评测；LLM 只负责把结果讲成人话。
 */
@Component
public class CognitiveDiagnosis {

    /** 基准作答时长（毫秒）：超过基准视为低效，扣效率分 */
    private static final long BASELINE_MS = 60_000;

    public Result diagnose(List<LearningRecordEntity> records) {
        // 按知识点聚合
        Map<String, Agg> aggs = new LinkedHashMap<>();
        for (LearningRecordEntity r : records) {
            Agg a = aggs.computeIfAbsent(r.getKnowledgePoint(), k -> new Agg());
            a.total++;
            if (Boolean.TRUE.equals(r.getCorrect())) {
                a.correct++;
            }
            if (r.getTimeMs() != null && r.getTimeMs() > 0) {
                a.timeSum += r.getTimeMs();
                a.timeCount++;
            }
        }
        List<WeakPoint> weakPoints = new ArrayList<>();
        for (Map.Entry<String, Agg> e : aggs.entrySet()) {
            Agg a = e.getValue();
            double accuracy = (double) a.correct / a.total;
            // 效率修正：平均耗时越短效率越高，修正区间 [0.6, 1.0]
            double efficiency = 1.0;
            if (a.timeCount > 0) {
                double avgMs = (double) a.timeSum / a.timeCount;
                efficiency = Math.max(0.6, Math.min(1.0, BASELINE_MS / Math.max(avgMs, 1)));
            }
            int score = (int) Math.round(accuracy * 70 + efficiency * 30);
            weakPoints.add(new WeakPoint(e.getKey(), score, confidence(a.total, accuracy)));
        }
        weakPoints.sort(Comparator.comparingInt(WeakPoint::score));
        return new Result(weakPoints, summarize(weakPoints, records.size()));
    }

    /** 置信度：样本量不足为低；错误率高的高频错题为高；其余为中 */
    private String confidence(int attempts, double accuracy) {
        if (attempts < 4) {
            return "低";
        }
        if (accuracy < 0.5) {
            return "高";
        }
        return "中";
    }

    private String summarize(List<WeakPoint> weakPoints, int totalRecords) {
        if (weakPoints.isEmpty()) {
            return "暂无足够答题记录，先做一组练习建立学情基线吧。";
        }
        WeakPoint weakest = weakPoints.get(0);
        return "近 " + totalRecords + " 次答题记录显示：你在「" + weakest.knowledgePoint()
                + "」上掌握度最低（" + weakest.score() + " 分），建议优先按学习路径补齐。";
    }

    public record WeakPoint(String knowledgePoint, int score, String confidence) {
    }

    public record Result(List<WeakPoint> weakPoints, String summary) {
    }

    private static class Agg {
        int total;
        int correct;
        long timeSum;
        int timeCount;
    }
}
