package com.tutor.router;

import com.tutor.config.AppProperties;
import com.tutor.learning.CognitiveDiagnosis;
import com.tutor.learning.LearningState;
import com.tutor.llm.LlmGateway;
import com.tutor.llm.LlmUsageHolder;
import com.tutor.observ.TraceRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 意图识别与规则兜底（编排层核心）：Agent 负责理解，规则负责快路径与兜底。
 *
 * <p>分层链路（P1/P2 升级后）：
 * <ol>
 *   <li><b>L0 规则快路径</b>（{@link RuleFastPath}）：强信号零歧义请求直接短路，跳过一次 LLM 调用，
 *       降 TTFB/成本；拿不准返回空，绝不猜（防误判铁律）。</li>
 *   <li><b>L2 LLM 主识别</b>：意图 + 七维槽位 + 环节提议；失败走关键词弱兜底。</li>
 *   <li><b>规则-LLM 互证</b>：规则强信号与 LLM 一致则置信增益（互证），冲突只留痕不武断覆盖。</li>
 *   <li>跨轮槽位合并 → 意图状态矫正 → 置信度校准 → 低置信迟滞降级（连续 N 次才澄清，规则互证可豁免）。</li>
 * </ol>
 * 全过程写入 decisionTrace 演变链，供 badcase 逐层归因。规则口径统一收口在 {@link IntentRuleTable}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRouter {

    private final SlotExtractor slotExtractor;
    private final LlmIntentClassifier llmClassifier;
    private final IntentLogWriter logWriter;
    private final LlmGateway gateway;
    private final TraceRecorder traceRecorder;
    private final AppProperties props;
    private final SlotMergeService slotMergeService;
    private final RuleFastPath ruleFastPath;
    private final ConfidenceCalibrator confidenceCalibrator;
    private final com.tutor.learning.SessionStateService stateService;
    private final com.tutor.conversation.MessageService messageService;
    private final com.tutor.knowledge.SkillDictionary skillDictionary;

    public RouteDecision route(String sessionId, String message, LearningState state,
                               List<CognitiveDiagnosis.WeakPoint> carryWeakPoints,
                               com.tutor.observ.TraceContext traceContext) {
        long start = System.currentTimeMillis();
        List<String> trace = new ArrayList<>();

        // 跨轮上下文：历史槽位（合并基准）+ 最近对话（路由侧指代消解）
        Slots historySlots = stateService.loadSlots(sessionId);
        List<String> recentTurns = recentTurns(sessionId, message);

        // ===== L0：规则快路径评估（不调 LLM，先看是否存在零歧义强信号） =====
        RuleFastPath.RuleHit hit = props.getAi().getRoute().isFastPathEnabled()
                ? ruleFastPath.evaluate(message) : RuleFastPath.EMPTY;
        if (hit.present()) {
            trace.add("RULE_HIT " + hit.safeSignals());
        }

        boolean shortcut = hit.present() && hit.shortCircuit()
                && props.getAi().getRoute().isFastPathShortcutEnabled();
        if (shortcut) {
            // 强信号 + 四重否决过滤后唯一命中：直接路由，跳过 route-model 调用
            IntentResult fast = new IntentResult(hit.intent(), hit.slots(), hit.ruleConfidence(), null);
            trace.add("FASTPATH_SHORTCUT " + hit.intent() + "（跳过LLM）");
            return finalizeRouting(sessionId, message, state, carryWeakPoints, fast,
                    historySlots, "RULE_FASTPATH", false, trace, start, traceContext);
        }

        // ===== L2：LLM 主路径（全量请求） =====
        IntentResult result;
        boolean fallbackUsed = false;
        try {
            var usage = new LlmUsageHolder(props.getAi().getRouteModel(), 0);
            result = llmClassifier.recognize(message, usage, recentTurns);
            traceRecorder.generation(traceContext, "LlmChat(intent)", message,
                    result.intent() + "/" + result.confidence(), usage.toUsage(), "success");
            trace.add(String.format("LLM_RAW %s/%.2f", result.intent(), result.confidence()));
        } catch (Exception e) {
            // 弱信号关键词兜底（规则收口在 IntentRuleTable），保守推断保证流程不中断
            log.warn("意图识别 Agent 失败，关键词兜底接管: {}", e.getMessage());
            Intent fb = IntentRuleTable.weakFallback(message);
            result = new IntentResult(fb, Slots.empty(), 0.2, null);
            fallbackUsed = true;
            trace.add("LLM_FAIL→WEAK_FALLBACK " + fb);
        }

        // ===== 规则-LLM 互证：规则强信号作为 LLM 的佐证/冲突检测 =====
        boolean ruleReinforced = false;
        if (!fallbackUsed && hit.present()) {
            if (hit.intent() == result.intent()) {
                double boosted = Math.min(1.0,
                        result.confidence() + props.getAi().getRoute().getMutualConfidenceBoost());
                result = new IntentResult(result.intent(), result.slots(), boosted, result.stages());
                ruleReinforced = true;
                trace.add(String.format("MUTUAL_CONFIRM 规则与LLM一致，置信→%.2f", boosted));
            } else {
                trace.add("RULE_LLM_CONFLICT 规则=" + hit.intent() + " vs LLM=" + result.intent()
                        + "（不武断覆盖，以LLM+状态矫正为准）");
            }
        }

        // ===== 来源判定 + 置信度校准 + 低置信迟滞降级 =====
        String source;
        boolean degraded;
        if (fallbackUsed) {
            source = "KEYWORD_FALLBACK";
            degraded = true;
        } else {
            double raw = result.confidence();
            double cal = confidenceCalibrator.calibrate(raw);
            if (confidenceCalibrator.calibrationActive() && Math.abs(cal - raw) > 1e-9) {
                trace.add(String.format("CALIBRATE %.2f→%.2f", raw, cal));
            }
            boolean business = result.intent() != Intent.CHITCHAT && result.intent() != Intent.CLARIFY_NEEDED;
            boolean lowConf = business && cal < props.getAi().getRoute().getLlmConfidenceThreshold();
            if (lowConf && confidenceCalibrator.shouldClarify(sessionId, true, ruleReinforced)) {
                // 迟滞阈值内连续低置信、且无规则互证 → 降级澄清
                result = IntentResult.clarify(result.slots());
                source = "LOW_CONFIDENCE_FALLBACK";
                degraded = true;
                trace.add("LOW_CONF_CLARIFY 校准置信=" + String.format("%.2f", cal)
                        + " 连续低置信计数=" + confidenceCalibrator.currentStreak(sessionId));
            } else {
                // 高置信 / 规则互证 / 迟滞放行：清零计数，正常路由
                confidenceCalibrator.shouldClarify(sessionId, false, ruleReinforced);
                source = "L2_LLM";
                degraded = false;
                if (lowConf) {
                    trace.add("LOW_CONF_HYSTERESIS_PASS 迟滞放行（不打断，继续业务）");
                }
            }
        }

        return finalizeRouting(sessionId, message, state, carryWeakPoints, result,
                historySlots, source, degraded, trace, start, traceContext);
    }

    /**
     * 公共后处理：跨轮槽位合并 → 意图状态矫正 → 环节裁决 → 组装决策 → 落库写日志。
     * 快路径与 LLM 路径在此汇合，保证两条路径走完全相同的下游安全网。
     */
    private RouteDecision finalizeRouting(String sessionId, String message, LearningState state,
                                          List<CognitiveDiagnosis.WeakPoint> carryWeakPoints,
                                          IntentResult result, Slots historySlots, String source,
                                          boolean degraded, List<String> trace, long start,
                                          com.tutor.observ.TraceContext traceContext) {
        // 跨轮槽位合并（先于矫正：澄清判定基于合并后的完整约束面）
        Slots merged = slotMergeService.merge(historySlots, result.slots());
        result = new IntentResult(result.intent(), merged, result.confidence(), result.stages());

        // 意图状态矫正（上下文二次修正，每步变更进 trace）
        result = revise(sessionId, result, message, state, carryWeakPoints, trace);

        // 执行计划裁决：仅 L2_LLM 正常路径允许开放意图扩展编排，其余（快路径/兜底/降级）一律保守
        boolean allowLlmExpansion = "L2_LLM".equals(source);
        List<Stage> stages = resolveStages(result.intent(), message, result.stages(), !allowLlmExpansion);

        long cost = System.currentTimeMillis() - start;
        RouteDecision decision = RouteDecision.builder()
                .intent(result.intent())
                .source(source)
                .confidence(result.confidence())
                .slots(result.slots())
                .degraded(degraded)
                .stages(stages)
                .decisionTrace(List.copyOf(trace))
                .build();
        stateService.saveSlots(sessionId, decision.slots());
        logWriter.write(sessionId, message, decision, gateway.available(), cost);
        log.info("路由[{}]: intent={}, source={}, slots={}, stages={}, {}ms, trace={}",
                sessionId, decision.intent(), decision.source(), decision.slots(), stages, cost, trace);
        return decision;
    }

    /** 最近对话（路由侧指代消解用）：recentTexts 已含当前消息（路由前落库），去掉避免重复 */
    private List<String> recentTurns(String sessionId, String currentMessage) {
        try {
            List<String> texts = messageService.recentTexts(sessionId, 5);
            if (!texts.isEmpty() && texts.get(texts.size() - 1).endsWith(currentMessage)) {
                return texts.subList(0, texts.size() - 1);
            }
            return texts;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 执行计划裁决（LLM 提议，代码裁决）：
     * 1. 封闭意图由查表固定为单环节，LLM 输出直接覆盖——动态性只开放给 DIAGNOSE/PLAN 两个开放意图；
     * 2. 开放意图取「LLM 提议 ∩ 允许集」；空/保守路径回退默认（宁少勿多）；
     * 3. 复合请求（如"推荐课程然后出题"）：同时安排 RECOMMEND 与 EXERCISE；
     * 4. conservative=true（快路径/关键词兜底/低置信降级）一律保守默认，不做扩展编排。
     */
    private List<Stage> resolveStages(Intent intent, String message, List<Stage> proposed, boolean conservative) {
        // 复合请求检测：同时包含课程推荐与练习出题意图（如"推荐课程然后帮我出几道题"）
        boolean hasRecommendReq = (intent == Intent.COURSE_RECOMMEND) ||
                (message != null && message.matches(".*(推荐|课程|报课|选课|有什么课).*"));
        boolean hasExerciseReq = (intent == Intent.EXERCISE) ||
                (message != null && message.matches(".*(出题|做题|考考我|做几道|出几道|来几道|测试).*"));
        if (hasRecommendReq && hasExerciseReq) {
            return List.of(Stage.RECOMMEND, Stage.EXERCISE);
        }

        List<Stage> fixed = switch (intent) {
            case TEACH -> List.of(Stage.TEACH);
            case EXERCISE -> List.of(Stage.EXERCISE);
            case REPORT -> List.of(Stage.REPORT);
            case COURSE_RECOMMEND -> List.of(Stage.RECOMMEND);
            case COURSE_BUY -> List.of(Stage.BUY);
            case CLARIFY_NEEDED -> List.of(Stage.CLARIFY);
            case CHITCHAT -> List.of(Stage.CHITCHAT);
            default -> null;
        };
        if (fixed != null) {
            if (!conservative && proposed != null && proposed.size() > 1) {
                List<Stage> allowedForCompound = switch (intent) {
                    case COURSE_RECOMMEND -> List.of(Stage.RECOMMEND, Stage.EXERCISE, Stage.TEACH);
                    case EXERCISE -> List.of(Stage.EXERCISE, Stage.RECOMMEND, Stage.TEACH);
                    default -> List.of();
                };
                if (!allowedForCompound.isEmpty()) {
                    List<Stage> resolved = proposed.stream().filter(allowedForCompound::contains).distinct().toList();
                    if (resolved.size() > 1) {
                        return resolved;
                    }
                }
            }
            return fixed;
        }
        List<Stage> allowed = switch (intent) {
            case DIAGNOSE -> List.of(Stage.DIAGNOSE, Stage.PLAN, Stage.TEACH, Stage.EXERCISE);
            case PLAN -> List.of(Stage.DIAGNOSE, Stage.PLAN, Stage.RECOMMEND, Stage.TEACH);
            default -> List.of();
        };
        List<Stage> fallback = intent == Intent.DIAGNOSE
                ? List.of(Stage.DIAGNOSE) : List.of(Stage.PLAN);
        if (conservative || proposed == null || proposed.isEmpty()) {
            return fallback;
        }
        List<Stage> resolved = proposed.stream().filter(allowed::contains).distinct().toList();
        return resolved.isEmpty() ? fallback : resolved;
    }

    /**
     * 意图状态矫正（LLM/快路径输出之后、路由之前的二次修正）。规则口径统一来自 IntentRuleTable。
     */
    private IntentResult revise(String sessionId, IntentResult result, String message, LearningState state,
                                List<CognitiveDiagnosis.WeakPoint> carryWeakPoints, List<String> trace) {
        IntentResult safe = result.safeSlots();
        Intent before = safe.intent();
        Intent intent = before;
        Slots slots = safe.slots();

        // 矫正零：政策、退款、服务权益咨询走 TEACH 触发企业私有知识库，不误判成推课
        if (IntentRuleTable.isServicePolicy(message)) {
            intent = Intent.TEACH;
        }

        // 矫正一：课程类按消费动词细分为购买/推荐（保持前端卡片契约）
        if (intent == Intent.COURSE) {
            intent = IntentRuleTable.splitCourse(message);
        }

        // 矫正一·b：LLM 直接输出 COURSE_BUY 但实为挑选语义（买什么/该买什么课，无具体课程对象）→ 改判推荐。
        // "买"字面易把建议类表述带成下单；有明确课程对象（书名号）或历史槽位时不误伤
        if (intent == Intent.COURSE_BUY && slots.courseName() == null
                && IntentRuleTable.isChoosingCourses(message)) {
            intent = Intent.COURSE_RECOMMEND;
        }

        // 矫正二：规划关键词强制矫正，避免误判成普通讲解/闲聊（DIAGNOSE 复合请求不覆盖，保编排）
        if (intent != Intent.EXERCISE && intent != Intent.REPORT && intent != Intent.DIAGNOSE
                && IntentRuleTable.isPlanKeyword(message)) {
            intent = Intent.PLAN;
        }

        // 矫正三：练习态下的调整语义（再来一组/重做/换一批）矫正为出题
        if (state == LearningState.PRACTICING && intent != Intent.REPORT
                && IntentRuleTable.isPracticeAdjust(message)) {
            intent = Intent.EXERCISE;
        }

        // 矫正三·b：探讨题目、作答或求证选项（如"我觉得选 B...对吗"）强制矫正为 TEACH（启发式答疑与苏格拉底纠偏），严禁误判为重新出题
        if (intent == Intent.EXERCISE && IntentRuleTable.isQuestionDiscussionOrAnswer(message)) {
            intent = Intent.TEACH;
        }

        if (intent != before) {
            trace.add("STATE_REVISE " + before + "→" + intent);
        }

        // 槽位补全一：缺失技术知识点（为null或为元属性词）且有最近薄弱点，则自然携带
        if ((slots.knowledgePoint() == null || skillDictionary.isMetaKp(slots.knowledgePoint()))
                && carryWeakPoints != null && !carryWeakPoints.isEmpty()) {
            String kp = slotExtractor.carryOverWeakPoint(carryWeakPoints);
            if (kp != null && !skillDictionary.isMetaKp(kp)) {
                slots = slots.withKnowledgePoint(kp);
                trace.add("SLOT_CARRY 携带最近薄弱点=" + kp);
            }
        }

        // 槽位补全二：若仍无有效技术知识点，从多轮历史对话（仅学员发言）回溯活跃技术主题
        if ((slots.knowledgePoint() == null || skillDictionary.isMetaKp(slots.knowledgePoint())) && sessionId != null) {
            try {
                List<String> userHistory = messageService.recentUserTexts(sessionId, 6);
                for (int i = userHistory.size() - 1; i >= 0; i--) {
                    String uText = userHistory.get(i);
                    if (uText.trim().equalsIgnoreCase(message.trim())) {
                        continue;
                    }
                    String matchedKp = skillDictionary.findKnowledgePointInText(uText);
                    if (matchedKp != null && !skillDictionary.isMetaKp(matchedKp)) {
                        slots = slots.withKnowledgePoint(matchedKp);
                        trace.add("CONTEXT_BACKFILL 从历史上下文回溯技术主题=" + matchedKp);
                        break;
                    }
                }
            } catch (Exception e) {
                log.debug("历史上下文知识点回溯异常: {}", e.getMessage());
            }
        }

        // 矫正四：出题但完全没有具体技术主题（槽位无有效技术考点、也无历史薄弱点/上下文可携带）→ 澄清追问
        if (intent == Intent.EXERCISE && (slots.knowledgePoint() == null || skillDictionary.isMetaKp(slots.knowledgePoint()))) {
            trace.add("REVISE→CLARIFY 出题但无知识点主题，先追问");
            return IntentResult.clarify(slots.withKnowledgePoint(null));
        }

        return new IntentResult(intent, slots, safe.confidence(), safe.stages());
    }
}
