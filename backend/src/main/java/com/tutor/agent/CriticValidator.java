package com.tutor.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 试题与代码质检智能体（Critic / Validator Agent）：
 * 对 LLM 生成的技术讲解代码、动态变式题进行前置自动化质量评估，
 * 拦截并纠偏幻觉、选项错位、语法未闭合等缺陷，保障教学内容的工业级严谨度。
 */
@Slf4j
@Component
public class CriticValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```([a-zA-Z0-9_-]*)\\s*\\n([\\s\\S]*?)```");

    /**
     * 变式题结构与一致性审查
     */
    public QuestionValidationResult validateQuestion(JsonNode qNode) {
        if (qNode == null || qNode.isNull()) {
            return QuestionValidationResult.fail("试题节点为空");
        }

        String stem = qNode.path("stem").asText("").trim();
        if (stem.isBlank()) {
            return QuestionValidationResult.fail("题干为空");
        }

        JsonNode optionsNode = qNode.path("options");
        if (!optionsNode.isArray() || optionsNode.size() < 2) {
            return QuestionValidationResult.fail("选项缺失或不足两个");
        }

        List<String> options = new ArrayList<>();
        Set<String> uniqueOptions = new HashSet<>();
        for (JsonNode opt : optionsNode) {
            String optText = opt.asText("").trim();
            if (optText.isBlank()) {
                return QuestionValidationResult.fail("存在空白选项");
            }
            options.add(optText);
            uniqueOptions.add(optText.toLowerCase());
        }

        if (uniqueOptions.size() < options.size()) {
            return QuestionValidationResult.fail("存在重复选项");
        }

        String answer = qNode.path("answer").asText("").trim().toUpperCase();
        if (answer.isBlank()) {
            return QuestionValidationResult.fail("正确答案为空");
        }

        // 校验答案标识符是否在合法范围内（如 A, B, C, D）
        boolean validKey = false;
        char[] optionLetters = new char[]{'A', 'B', 'C', 'D', 'E', 'F'};
        int maxIndex = Math.min(options.size(), optionLetters.length);
        for (int i = 0; i < maxIndex; i++) {
            if (answer.contains(String.valueOf(optionLetters[i]))) {
                validKey = true;
                break;
            }
        }
        if (!validKey) {
            return QuestionValidationResult.fail("答案标识 [" + answer + "] 与选项列表不匹配");
        }

        String analysis = qNode.path("analysis").asText("").trim();
        if (analysis.isBlank()) {
            return QuestionValidationResult.fail("题目解析为空");
        }

        return QuestionValidationResult.pass();
    }

    /**
     * 讲解文本与代码块完整性审查
     */
    public CodeValidationResult validateExplanation(String text) {
        if (text == null || text.isBlank()) {
            return new CodeValidationResult(true, 0, List.of());
        }

        // 1. 检查 Markdown 代码块开闭符号（```）是否成对闭合
        int countBackticks = 0;
        int index = 0;
        while ((index = text.indexOf("```", index)) != -1) {
            countBackticks++;
            index += 3;
        }
        if (countBackticks % 2 != 0) {
            log.warn("Critic 质检警告：讲解文本中 Markdown 代码块未闭合 (``` 数量={})", countBackticks);
            return new CodeValidationResult(false, countBackticks / 2, List.of("Markdown 代码块未闭合"));
        }

        // 2. 检查提取的代码块基础语法对称性（大括号、小括号、中括号）
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(text);
        int codeBlockCount = 0;
        List<String> issues = new ArrayList<>();
        while (matcher.find()) {
            codeBlockCount++;
            String lang = matcher.group(1);
            String code = matcher.group(2);
            if (!isBracketBalanced(code)) {
                issues.add("代码块 [" + (lang.isBlank() ? "code" : lang) + "] 括号未闭合");
            }
        }

        boolean valid = issues.isEmpty();
        return new CodeValidationResult(valid, codeBlockCount, issues);
    }

    private boolean isBracketBalanced(String code) {
        if (code == null) return true;
        Deque<Character> stack = new ArrayDeque<>();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;

        char[] chars = code.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                continue;
            }
            if (c == '/' && i + 1 < chars.length && chars[i + 1] == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '\'' && (i == 0 || chars[i - 1] != '\\')) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (c == '"' && (i == 0 || chars[i - 1] != '\\')) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (inSingleQuote || inDoubleQuote) {
                continue;
            }

            if (c == '(' || c == '[' || c == '{') {
                stack.push(c);
            } else if (c == ')' || c == ']' || c == '}') {
                if (stack.isEmpty()) return false;
                char open = stack.pop();
                if ((c == ')' && open != '(') ||
                    (c == ']' && open != '[') ||
                    (c == '}' && open != '{')) {
                    return false;
                }
            }
        }
        return stack.isEmpty();
    }

    public record QuestionValidationResult(boolean passed, String message) {
        public static QuestionValidationResult pass() {
            return new QuestionValidationResult(true, "通过");
        }
        public static QuestionValidationResult fail(String msg) {
            return new QuestionValidationResult(false, msg);
        }
    }

    public record CodeValidationResult(boolean passed, int codeBlockCount, List<String> issues) {
    }
}
