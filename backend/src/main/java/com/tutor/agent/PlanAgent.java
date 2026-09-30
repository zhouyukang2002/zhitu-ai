package com.tutor.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.tutor.knowledge.KnowledgeBase;
import com.tutor.knowledge.QueryRewriter;
import com.tutor.learning.CognitiveDiagnosis;
import com.tutor.learning.DiagnosisPlanStore;
import com.tutor.learning.LearningState;
import com.tutor.learning.SessionStateService;
import com.tutor.agent.LoopContext;
import com.tutor.agent.TraceStep;
import com.tutor.config.AppProperties;
import com.tutor.llm.LlmGateway;
import com.tutor.llm.LlmUsageHolder;
import com.tutor.prompt.PromptStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 学习规划智能体（v2：LLM 组织课程序列，替代原知识图谱 DAG 路径算法）：
 * 双路召回的课程候选（ES course_intro 语义召回）+ 诊断薄弱点 → LLM 结合自身知识
 * 与课程元信息（层级/价格/岗位）组织成有先后顺序的转型/学习路径 → 学习计划卡片。
 * 容错：LLM 失败或课程召回为空时退化为基于薄弱点的模板路径（degraded 标记，不中断流水线）。
 * 说明：课程顺序由 LLM 的领域知识给出（主流技术路线是稳定知识），课程元数据仅作展示与召回过滤。
 * 检索前置：课程语义召回统一经过 QueryRewriter 做指代消解（多轮"那需要学什么"也能命中）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanAgent {

    public static final String AGENT = "学习规划智能体";
    private static final int MAX_COURSES = 6;

    private final com.tutor.observ.TraceRecorder traceRecorder;
    private final KnowledgeBase knowledgeBase;
    private final QueryRewriter queryRewriter;
    private final LlmGateway gateway;
    private final PromptStore promptStore;
    private final DiagnosisPlanStore diagnosisPlanStore;
    private final SessionStateService stateService;
    private final AppProperties props;

    public Map<String, Object> plan(LoopContext ctx, List<CognitiveDiagnosis.WeakPoint> weakPoints) {
        long start = System.currentTimeMillis();
        // 课程候选：语义召回前先做查询改写（消解多轮指代，与讲解 RAG 同一前置环节）
        String courseQuery = queryRewriter.rewrite(ctx.getSessionId(), ctx.getMessage());
        List<KnowledgeBase.Chunk> candidates = knowledgeBase.searchCourses(courseQuery, MAX_COURSES);
        String courseMenu = candidates.stream()
                .map(c -> "- [" + c.getId() + "] " + c.getTitle() + "：" + brief(c.getText()))
                .collect(Collectors.joining("\n"));
        String weakText = weakPoints.isEmpty() ? "（暂无摸底数据，按用户目标整体规划）"
                : weakPoints.stream()
                        .map(w -> w.knowledgePoint() + "（掌握度 " + w.score() + "）")
                        .collect(Collectors.joining("、"));
        List<Step> path = new ArrayList<>();
        String status = TraceStep.SUCCESS;
        String tool;
        if (gateway.available()) {
            try {
                var usage = new LlmUsageHolder(props.getAi().getRouteModel(), 0);
                JsonNode node = gateway.callJson(
                        promptStore.get(PromptStore.PLAN)
                                .replace("{goal}", ctx.getMessage())
                                .replace("{weakPoints}", weakText)
                                .replace("{courses}", courseMenu.isEmpty() ? "（课程库暂无匹配）" : courseMenu),
                        "请生成学习计划", props.getAi().getRouteModel(), 0.3, usage);
                for (JsonNode n : node.path("steps")) {
                    String title = n.path("title").asText("");
                    if (!title.isBlank()) {
                        path.add(new Step(path.size() + 1, title, path.isEmpty() ? "current" : "pending"));
                    }
                }
                traceRecorder.generation(ctx.getTraceContext(), "LlmChat(plan)",
                        ctx.getMessage(), "steps=" + path.size(), usage.toUsage(), status);
            } catch (Exception e) {
                log.warn("LLM 规划失败，模板路径接管: {}", e.getMessage());
                status = TraceStep.DEGRADED;
            }
        } else {
            status = TraceStep.DEGRADED;
        }
        if (path.isEmpty()) {
            // 保守兜底：基于薄弱点的模板路径（宁少勿多，保证计划卡不缺位）
            path = templatePath(weakPoints);
            status = TraceStep.DEGRADED;
        }
        double progress = 0.0; // 刚生成的学习计划初始进度应为 0.0（0%）
        diagnosisPlanStore.savePlan(ctx.getSessionId(),
                path.stream().map(s -> new DiagnosisPlanStore.PathStep(s.step(), s.title(), s.status())).toList(),
                progress);
        stateService.set(ctx.getSessionId(), LearningState.PLANNED);
        tool = "查询改写 + 课程语义召回 + LLM 组织路径" + (candidates.isEmpty() ? "（召回为空）" : "（候选 " + candidates.size() + " 门）");
        ctx.addTrace(new TraceStep(AGENT, "生成个性化学习路径", tool,
                System.currentTimeMillis() - start, status));
        return Map.of(
                "path", path.stream()
                        .map(s -> Map.<String, Object>of("step", s.step(), "title", s.title(), "status", s.status()))
                        .toList(),
                "progress", progress);
    }

    /** LLM 失败时的模板路径：诊断薄弱点驱动的三步走 */
    private List<Step> templatePath(List<CognitiveDiagnosis.WeakPoint> weakPoints) {
        List<Step> path = new ArrayList<>();
        if (weakPoints.isEmpty()) {
            path.add(new Step(1, "明确目标岗位与基础：先做一轮摸底测试", "current"));
            path.add(new Step(2, "按岗位技能主线开始第一阶段学习", "pending"));
        } else {
            String first = weakPoints.get(0).knowledgePoint();
            path.add(new Step(1, "优先补齐：「" + first + "」（当前最薄弱）", "current"));
            if (weakPoints.size() > 1) {
                path.add(new Step(2, "巩固：「" + weakPoints.get(1).knowledgePoint() + "」", "pending"));
            }
            path.add(new Step(path.size() + 1, "做一组练习检验学习效果", "pending"));
        }
        return path;
    }

    /** 课程介绍摘要（截断到元信息行之前，控制 prompt 体积） */
    private String brief(String text) {
        int meta = text.indexOf("[元信息]");
        String body = meta > 0 ? text.substring(0, meta) : text;
        body = body.replaceAll("\n+", " ");
        return body.length() > 80 ? body.substring(0, 80) + "…" : body;
    }

    public record Step(int step, String title, String status) {
    }
}
