package com.tutor.exercise;

import cn.hutool.core.util.StrUtil;
import com.tutor.client.QuestionBankClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则评分引擎（纯领域算法，可单测）：
 * 客观题（选择）规则判分零成本秒级；主观题关键词命中兜底（LLM 判分的降级路径）。
 * 话术锚点：客观题规则判 / 主观题 LLM 判——成本、速度、可解释性的权衡。
 */
@Component
public class GradingEngine {

    /** 客观题：比对标准答案 */
    public GradeResult.PerQuestion gradeChoice(QuestionBankClient.Question q, String given) {
        String answer = StrUtil.nullToEmpty(given).trim();
        boolean correct = !answer.isEmpty()
                && answer.charAt(0) == StrUtil.nullToEmpty(q.getAnswer()).trim().charAt(0);
        return new GradeResult.PerQuestion(
                q.getId(), correct, q.getAnswer(),
                correct ? "回答正确，" + q.getKp() + "运用熟练。"
                        : "本题主考查「" + q.getKp() + "」，正确答案 " + q.getAnswer() + "。",
                correct ? null : "概念混淆");
    }

    /**
     * 主观题兜底：评分关键词命中数决定档位（模拟 LLM 按评分标准的输出结构）。
     */
    public GradeResult.PerQuestion gradeShortByKeywords(QuestionBankClient.Question q, String given) {
        String text = StrUtil.nullToEmpty(given);
        List<String> keywords = q.getKeywords() == null ? List.of() : q.getKeywords();
        long hit = keywords.stream().filter(text::contains).count();
        double ratio = keywords.isEmpty() ? 0 : Math.min(1.0, (double) hit / Math.min(4, keywords.size()));
        int qScore = (int) Math.round(q.getScore() * ratio);
        boolean correct = ratio >= 0.75;
        String feedback;
        if (correct) {
            feedback = "理由完整，计算过程正确，表述清晰。";
        } else if (hit > 0) {
            feedback = "提到了关键概念，但缺少具体计算过程或结论不完整，建议对照参考答案补全步骤。";
        } else {
            feedback = "未给出有效理由，请对照参考答案先写出完整的代入过程。";
        }
        return new GradeResult.PerQuestion(
                q.getId(), correct, q.getReference(), feedback,
                correct ? null : (hit > 0 ? "计算失误" : "概念混淆"));
    }
}
