package com.tutor.agent;

import com.fasterxml.jackson.databind.JsonNode;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.tutor.config.AppProperties;
import com.tutor.conversation.MessageService;
import com.tutor.exercise.ExerciseService;
import com.tutor.learning.CognitiveDiagnosis;
import com.tutor.learning.LearningState;
import com.tutor.agent.LoopContext;
import com.tutor.learning.SessionStateService;
import com.tutor.agent.TraceStep;
import com.tutor.client.QuestionBankClient;
import com.tutor.llm.LlmGateway;
import com.tutor.llm.LlmUsageHolder;
import com.tutor.prompt.PromptStore;
import com.tutor.sse.SseSession;
import com.tutor.tool.BizToolBridge;
import com.tutor.common.constant.SseCardType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 练习生成智能体（v2：双轨题源）：
 * 1. 课程私有题库：学生指定已购课程时走 MCP getQuestions（业务系统层鉴权，未购课拒绝），
 *    组卷后经 getAnswerKey 取答案键随卷存储；
 * 2. LLM 现场出题（免费主路径）：按薄弱知识点现场生成，答案键随卷落库——
 *    出题与批改同一把尺子，杜绝"批改时重新生成答案导致口径漂移"；
 *    LLM 不可用/输出与知识点无关/解析失败 → 直接道歉话术 + degraded（无降级题库）。
 * 出题卡片不下发标准答案/评分关键词——答案只留在批改侧。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExerciseAgent {

    public static final String AGENT = "练习生成智能体";

    private final BizToolBridge bizToolBridge;
    private final ExerciseService exerciseService;
    private final MessageService messageService;
    private final SessionStateService stateService;
    private final com.tutor.trade.OrderService orderService;
    private final LlmGateway gateway;
    private final PromptStore promptStore;
    private final AppProperties props;

    /**
     * 组卷入口：私有题库优先（已购课程），否则 LLM 现场出题。
     * 返回练习卡片 content；生成失败（道歉话术已流式下发）返回 null，调用方跳过练习卡。
     */
    public Map<String, Object> compose(LoopContext ctx, List<CognitiveDiagnosis.WeakPoint> weakPoints, SseSession sse) {
        long start = System.currentTimeMillis();
        try {
            String kp = resolveKp(ctx, weakPoints);
            var slots = ctx.getDecision().slots();
            int count = slots != null && slots.questionCount() != null
                    ? Math.max(1, Math.min(5, slots.questionCount())) : 3;
            String type = slots == null ? null : slots.questionType();

            // 🎓 私有题库路径：指定已购课程时走 MCP 题库（业务系统层鉴权）
            String boundCourseId = resolvePaidCourseId(ctx);
            List<QuestionBankClient.Question> picked = null;
            Map<String, Map<String, Object>> keys = null;
            if (boundCourseId != null) {
                try {
                    picked = pickFromPrivateBank(ctx, boundCourseId, kp, type, count);
                    if (picked != null && !picked.isEmpty()) {
                        keys = fetchAnswerKeys(ctx, picked);
                    }
                } catch (Exception e) {
                    log.warn("私有题库抽题失败，转 LLM 现场出题: {}", e.getMessage());
                    picked = null;
                }
            }

            // LLM 现场出题（免费主路径；私有题库为空/不可用时兜底到此）
            boolean liveGenerated = picked == null || picked.isEmpty();
            if (liveGenerated) {
                LiveQuiz quiz = generateLive(ctx, kp, type, count, weakPoints);
                if (quiz == null) {
                    // 生成失败：道歉话术直接下发，环节标记 degraded，无降级题库（产品决策）
                    String sorry = "抱歉，这一轮出题服务出了点问题，没能为你生成合适的题目。请稍后再试一次，或者先把你想练习的知识点再描述具体一点。";
                    com.tutor.agent.StreamingSupport.streamText(sorry, sse, 24, 18);
                    messageService.append(ctx.getSessionId(), "text", "assistant", Map.of("text", sorry));
                    ctx.addTrace(new TraceStep(AGENT, "现场出题失败 → 道歉话术", "LLM 现场出题",
                            System.currentTimeMillis() - start, TraceStep.DEGRADED));
                    return null;
                }
                picked = quiz.questions();
                keys = quiz.keys();
            }

            List<String> usedKps = picked.stream().map(QuestionBankClient.Question::getKp)
                    .distinct().collect(Collectors.toList());
            String exerciseId = exerciseService.register(ctx.getSessionId(), picked, usedKps, keys);

            List<Map<String, Object>> questions = picked.stream().map(q -> {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("id", q.getId());
                m.put("type", q.getType());
                m.put("stem", q.getStem());
                if (q.getOptions() != null) {
                    m.put("options", q.getOptions());
                }
                return m;
            }).toList();

            stateService.set(ctx.getSessionId(), LearningState.PRACTICING);
            String tip = liveGenerated
                    ? "为你现场生成 " + picked.size() + " 道「" + kp + "」练习题（"
                      + typeDesc(picked) + "），提交后自动批改。💡 购买对应课程可解锁配套私有教研题库～"
                    : "🏆【已购课程「" + boundCourseName(ctx) + "」私有题库】共 " + picked.size()
                      + " 题（" + typeDesc(picked) + "），提交后自动批改。";
            ctx.addTrace(new TraceStep(AGENT, liveGenerated ? "LLM 现场出题" : "私有题库组卷",
                    liveGenerated ? "LLM 生成（答案键随卷存储）" : "MCP 题库工具（鉴权通过）",
                    System.currentTimeMillis() - start, TraceStep.SUCCESS));

            Map<String, Object> content = new LinkedHashMap<>();
            content.put("exerciseId", exerciseId);
            content.put("tip", tip);
            content.put("questions", questions);
            messageService.append(ctx.getSessionId(), "exercise", "assistant", content);
            return content;
        } catch (Exception e) {
            log.error("出题环节异常", e);
            ctx.addTrace(new TraceStep(AGENT, "组卷", "题源选择",
                    System.currentTimeMillis() - start, TraceStep.FAILED));
            return null;
        }
    }

    /** 摸底测试（冷启动诊断）：新用户无答题记录时现场生成摸底题，作答回流后诊断才有数据 */
    public void generatePlacement(LoopContext ctx, SseSession sse) {
        long start = System.currentTimeMillis();
        try {
            String kp = resolveKp(ctx, List.of());
            LiveQuiz quiz = generateLive(ctx, kp, null, 3, List.of());
            if (quiz == null) {
                String sorry = "抱歉，摸底出题服务暂时不可用，请稍后再试。";
                com.tutor.agent.StreamingSupport.streamText(sorry, sse, 24, 18);
                messageService.append(ctx.getSessionId(), "text", "assistant", Map.of("text", sorry));
                ctx.addTrace(new TraceStep(AGENT, "摸底出题失败 → 道歉话术", "LLM 现场出题",
                        System.currentTimeMillis() - start, TraceStep.DEGRADED));
                return;
            }
            List<String> usedKps = quiz.questions().stream().map(QuestionBankClient.Question::getKp)
                    .distinct().collect(Collectors.toList());
            String exerciseId = exerciseService.register(ctx.getSessionId(), quiz.questions(), usedKps, quiz.keys());
            List<Map<String, Object>> questions = quiz.questions().stream().map(q -> {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("id", q.getId());
                m.put("type", q.getType());
                m.put("stem", q.getStem());
                if (q.getOptions() != null) {
                    m.put("options", q.getOptions());
                }
                return m;
            }).toList();
            Map<String, Object> system = Map.of(
                    "text", "你的学习档案还是空的，先做一组摸底题测测基础，提交后我为你生成第一份学情诊断。",
                    "state", "PLACEMENT");
            messageService.append(ctx.getSessionId(), "system", "assistant", system);
            sse.card(SseCardType.SYSTEM, system);
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("exerciseId", exerciseId);
            content.put("tip", "📐 摸底测试（" + quiz.questions().size() + " 题），提交后自动批改并生成学情诊断。");
            content.put("questions", questions);
            messageService.append(ctx.getSessionId(), "exercise", "assistant", content);
            sse.card(SseCardType.EXERCISE, content);
            ctx.addTrace(new TraceStep(AGENT, "冷启动摸底出题", "LLM 生成（答案键随卷存储）",
                    System.currentTimeMillis() - start, TraceStep.SUCCESS));
        } catch (Exception e) {
            log.warn("摸底测试生成失败: {}", e.getMessage());
            ctx.addTrace(new TraceStep(AGENT, "摸底出题", "LLM 生成",
                    System.currentTimeMillis() - start, TraceStep.FAILED));
        }
    }

    /** LLM 现场出题：失败/输出不相关（解析失败、缺答案、题目为空）返回 null */
    private LiveQuiz generateLive(LoopContext ctx, String kp, String type, int count,
                                  List<CognitiveDiagnosis.WeakPoint> weakPoints) {
        if (!gateway.available()) {
            return null;
        }
        try {
            String typeDesc = "选择题 2 道 + 简答题 1 道（可按数量调整）";
            if ("选择题".equals(type)) {
                typeDesc = "全部为选择题";
            } else if ("简答题".equals(type)) {
                typeDesc = "全部为简答/面试题";
            }
            String background = weakPoints.isEmpty() ? "新学员，基础未知" :
                    "已有薄弱点：" + weakPoints.stream()
                            .map(CognitiveDiagnosis.WeakPoint::knowledgePoint)
                            .collect(Collectors.joining("、"));
            var usage = new LlmUsageHolder(props.getAi().getChatModel(), 0);
            JsonNode node = gateway.callJson(
                    promptStore.get(PromptStore.QUESTION_GEN)
                            .replace("{kp}", kp)
                            .replace("{count}", String.valueOf(count))
                            .replace("{type}", typeDesc)
                            .replace("{difficulty}", "基础为主，可含一道进阶")
                            .replace("{background}", background),
                    "请出题", null, 0.4, usage);
            JsonNode arr = node.path("questions");
            if (!arr.isArray() || arr.isEmpty()) {
                log.warn("LLM 出题输出为空: {}", arr);
                return null;
            }
            List<QuestionBankClient.Question> questions = new ArrayList<>();
            Map<String, Map<String, Object>> keys = new LinkedHashMap<>();
            int i = 0;
            for (JsonNode q : arr) {
                String stem = q.path("stem").asText("").trim();
                String qType = "short".equalsIgnoreCase(q.path("type").asText("choice")) ? "short" : "choice";
                if (stem.isBlank()) {
                    continue;
                }
                i++;
                QuestionBankClient.Question question = new QuestionBankClient.Question();
                question.setId("q_live_" + IdUtil.fastSimpleUUID().substring(0, 8));
                question.setKp(kp);
                question.setType(qType);
                question.setStem(stem);
                if (q.has("options") && q.get("options").isArray()) {
                    List<String> options = new ArrayList<>();
                    q.get("options").forEach(o -> options.add(o.asText()));
                    if (!options.isEmpty()) {
                        question.setOptions(options);
                    }
                }
                question.setScore(q.path("score").asInt("choice".equals(qType) ? 5 : 10));
                questions.add(question);
                // 答案键随卷：出题时即固定答案与评分口径
                Map<String, Object> key = new LinkedHashMap<>();
                key.put("answer", q.path("answer").asText(null));
                key.put("analysis", q.path("analysis").asText(null));
                List<String> kws = new ArrayList<>();
                q.path("keywords").forEach(k -> kws.add(k.asText()));
                key.put("keywords", kws);
                key.put("score", question.getScore());
                keys.put(question.getId(), key);
            }
            // 自查：题目为空、选择题缺答案、简答题缺评分要点 → 视为不相关/不合格输出，走道歉路径
            for (QuestionBankClient.Question q : questions) {
                Map<String, Object> key = keys.get(q.getId());
                if ("choice".equals(q.getType()) && StrUtil.isBlank((String) key.get("answer"))) {
                    log.warn("LLM 出题选择题缺答案，判定不合格");
                    return null;
                }
                if ("short".equals(q.getType())
                        && (key.get("keywords") == null || ((List<?>) key.get("keywords")).isEmpty())) {
                    log.warn("LLM 出题简答题缺评分关键词，判定不合格");
                    return null;
                }
            }
            if (questions.isEmpty()) {
                return null;
            }
            return new LiveQuiz(questions, keys);
        } catch (Exception e) {
            log.warn("LLM 现场出题失败: {}", e.getMessage());
            return null;
        }
    }

    /** 私有题库抽题（MCP，业务系统层鉴权）：指定知识点/题型/数量 */
    private List<QuestionBankClient.Question> pickFromPrivateBank(LoopContext ctx, String courseId,
                                                                  String kp, String type, int count) {
        String mcpType = null;
        if (type != null) {
            mcpType = switch (type) {
                case "选择题" -> "choice";
                case "简答题" -> "short";
                default -> type;
            };
        }
        String args = "{\"userId\":" + ctx.getUserId() + ",\"courseId\":\"" + courseId + "\""
                + (kp == null ? "" : ",\"knowledgePoint\":\"" + kp + "\"")
                + (mcpType == null ? "" : ",\"questionType\":\"" + mcpType + "\"")
                + ",\"count\":" + count + "}";
        String mcpResult = bizToolBridge.callTool("getQuestions", args);
        return mcpResult == null ? List.of() : parseMcpQuestions(mcpResult);
    }

    /** 答案键补齐（MCP getAnswerKey，同样鉴权）：组卷后立即取键，与题目一起随卷存储 */
    private Map<String, Map<String, Object>> fetchAnswerKeys(LoopContext ctx, List<QuestionBankClient.Question> picked) {
        Map<String, Map<String, Object>> keys = new LinkedHashMap<>();
        try {
            String ids = picked.stream()
                    .map(q -> "\"" + q.getId() + "\"")
                    .collect(Collectors.joining(","));
            String mcpResult = bizToolBridge.callTool("getAnswerKey",
                    "{\"userId\":" + ctx.getUserId() + ",\"questionIds\":[" + ids + "]}");
            if (mcpResult != null) {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(mcpResult);
                var content = node.path("content");
                if (content.isArray()) {
                    for (var item : content) {
                        Map<String, Object> key = new LinkedHashMap<>();
                        key.put("answer", item.path("answer").asText(null));
                        key.put("analysis", item.path("reference").asText(null));
                        List<String> kws = new ArrayList<>();
                        item.path("keywords").forEach(k -> kws.add(k.asText()));
                        key.put("keywords", kws);
                        key.put("score", item.path("score").asInt(5));
                        keys.put(item.path("id").asText(), key);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("答案键获取失败（批改将依赖题面数据）: {}", e.getMessage());
        }
        return keys;
    }

    /** 知识点解析：槽位 > 薄弱点 > 用户原话 */
    private String resolveKp(LoopContext ctx, List<CognitiveDiagnosis.WeakPoint> weakPoints) {
        var slots = ctx.getDecision().slots();
        if (slots != null && slots.knowledgePoint() != null) {
            return slots.knowledgePoint();
        }
        if (!weakPoints.isEmpty()) {
            return weakPoints.get(0).knowledgePoint();
        }
        return ctx.getMessage();
    }

    /** 已购课程解析：槽位课程名匹配已购订单 > 第一门已购课程（私有题库入口） */
    private String resolvePaidCourseId(LoopContext ctx) {
        var slots = ctx.getDecision().slots();
        String wanted = slots == null ? null : slots.courseName();
        var paidOrders = orderService.listPaidOrders(ctx.getUserId());
        if (paidOrders.isEmpty()) {
            return null;
        }
        if (wanted != null) {
            for (var order : paidOrders) {
                if (order.getCourseName() != null && order.getCourseName().contains(wanted)) {
                    return order.getCourseId();
                }
            }
            return null; // 指定了未购课程 → 不走私有题库（LLM 现场出题 + 提示）
        }
        return paidOrders.get(0).getCourseId();
    }

    private String boundCourseName(LoopContext ctx) {
        var paidOrders = orderService.listPaidOrders(ctx.getUserId());
        return paidOrders.isEmpty() ? "" : paidOrders.get(0).getCourseName();
    }

    private String typeDesc(List<QuestionBankClient.Question> picked) {
        long nChoice = picked.stream().filter(q -> "choice".equals(q.getType())).count();
        long nShort = picked.size() - nChoice;
        return nShort == 0 ? nChoice + " 道选择题"
                : nChoice == 0 ? nShort + " 道简答题" : nChoice + " 道选择题 + " + nShort + " 道简答题";
    }

    private List<QuestionBankClient.Question> parseMcpQuestions(String json) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            var content = node.path("content");
            if (!content.isArray() || content.isEmpty()) return List.of();
            List<QuestionBankClient.Question> result = new ArrayList<>();
            for (var item : content) {
                var q = new QuestionBankClient.Question();
                q.setId(item.path("id").asText());
                q.setKp(item.path("kp").asText());
                q.setType(item.path("type").asText());
                q.setStem(item.path("stem").asText());
                if (item.has("options") && item.get("options").isArray()) {
                    q.setOptions(new java.util.ArrayList<>());
                    item.get("options").forEach(o -> q.getOptions().add(o.asText()));
                }
                q.setScore(item.path("score").asInt(0));
                result.add(q);
            }
            return result;
        } catch (Exception e) {
            log.warn("MCP 题目解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private record LiveQuiz(List<QuestionBankClient.Question> questions,
                            Map<String, Map<String, Object>> keys) {
    }
}
