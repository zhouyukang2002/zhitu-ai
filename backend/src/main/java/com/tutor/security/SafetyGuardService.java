package com.tutor.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 内容合规与 Prompt 防注入安全防护服务（AI Safety Guard）：
 * 1. 毫秒级防御越狱注入（Jailbreak / Prompt Injection）；
 * 2. 拦截系统提示词窃取（System Prompt Leakage）；
 * 3. 过滤涉政、涉黄暴及非学科恶意违规内容。
 */
@Slf4j
@Service
public class SafetyGuardService {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)(ignore|disregard|forget).*?(previous|above|system|all).*?(instructions|prompts|rules)"),
            Pattern.compile("(?i)(output|print|display|show|reveal|tell).*?(system\\s+prompt|instructions|internal\\s+rules)"),
            Pattern.compile("(?i)(you\\s+are\\s+now|act\\s+as).*?(DAN|unfiltered|jailbreak|developer\\s+mode)"),
            Pattern.compile("(?i)(忽略|忘掉|无视|不遵循).*?(之前|上面|所有|全部|系统).*?(指令|提示词|规则|设定|prompt)"),
            Pattern.compile("(?i)(输出|打印|显示|背诵|透露|复述|告诉我).*?(系统提示词|prompt|内部设定|隐藏规则|前置指令)"),
            Pattern.compile("(?i)你现在是一个.*?(没有任何限制|解除了道德限制|无所不能|DAN).*?AI"),
            Pattern.compile("(?i)(越狱模式|DAN模式|开发者模式|无审核模式)")
    );

    /** 学术诚信类（对齐 diet-agent RiskGuard 守卫语义）：拒绝话术给建设性转化，不留协助路径 */
    private static final List<Pattern> ACADEMIC_INTEGRITY_PATTERNS = List.of(
            Pattern.compile("(?i)(代写|代考|替考|替写|枪手|帮我写论文|帮我把作业做了|帮我做作业|帮我完成作业)"),
            // 论文/作业"直接给全文/成稿"类请求：即便不含"代写"字样也是代写变体（安全评测实证漏拦）
            Pattern.compile("(?i)((写|来)(一篇|一份|个)?(毕业论文|结课论文|课程论文|论文).{0,14}(直接给|全文|成稿)|论文.{0,8}直接给全文|直接给全文|只要交上去|不需要会做)"),
            Pattern.compile("(?i)(作弊|小抄|泄题|透题|买答案|卖答案|答案多少钱|篡改成绩|改成绩|改分数)"),
            Pattern.compile("(?i)(保过|包过|免试拿证|不用考试直接拿|免考拿证)")
    );

    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            Pattern.compile("(?i)(办假证|作弊器)"),
            Pattern.compile("(?i)(赌博|博彩|百家乐|六合彩)"),
            Pattern.compile("(?i)(色情|自残|自杀|暴力袭击|制作炸药|合成毒品)")
    );

    /**
     * 检查文本安全性
     */
    public SafetyResult check(String text) {
        if (text == null || text.isBlank()) {
            return SafetyResult.ok();
        }

        String trimmed = text.trim();

        // 1. 检查 Prompt 注入与越狱攻击
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(trimmed).find()) {
                log.warn("🚨 [AI安全网关] 拦截到 Prompt 注入与越狱尝试: {}", trimmed);
                return SafetyResult.blocked("PROMPT_INJECTION",
                        "🛡️ **安全提示**：检测到包含试图修改或窃取系统设定的指令。\n\n"
                                + "我是你的职业技术与技能提升智能导师，请专注于 IT 编程、职业技能答疑、技能诊断与转码路线规划提问哦～");
            }
        }

        // 2. 学术诚信守卫（代写/代考/作弊/保过类）：拒绝并给建设性转化——引导回"带你学会"的正道
        for (Pattern p : ACADEMIC_INTEGRITY_PATTERNS) {
            if (p.matcher(trimmed).find()) {
                log.warn("🚨 [AI安全网关] 拦截到学术诚信风险请求: {}", trimmed);
                return SafetyResult.blocked("ACADEMIC_INTEGRITY",
                        "这个忙我帮不了。代写、代考、作弊等行为违反学术诚信，也可能涉及违纪违法，我不能提供任何协助。\n\n"
                                + "我可以换一种方式帮你：把题目或知识点发给我，我带你一步步**理解**它——"
                                + "真正学会才是考试成绩和职业能力最可靠的来源。");
            }
        }

        // 3. 检查违规敏感与作弊内容
        for (Pattern p : SENSITIVE_PATTERNS) {
            if (p.matcher(trimmed).find()) {
                log.warn("🚨 [AI安全网关] 拦截到违规敏感或作弊内容: {}", trimmed);
                return SafetyResult.blocked("SENSITIVE_CONTENT",
                        "🛡️ **合规提醒**：输入内容包含违禁或不适宜的关键词。\n\n"
                                + "请遵守绿色健康的交流规范，我们可以一起探讨具体的 IT 编程技术、职业转型路线或在线课程疑问～");
            }
        }

        return SafetyResult.ok();
    }

    public record SafetyResult(boolean safe, String blockType, String safeReply) {
        public static SafetyResult ok() {
            return new SafetyResult(true, null, null);
        }

        public static SafetyResult blocked(String blockType, String safeReply) {
            return new SafetyResult(false, blockType, safeReply);
        }
    }
}
