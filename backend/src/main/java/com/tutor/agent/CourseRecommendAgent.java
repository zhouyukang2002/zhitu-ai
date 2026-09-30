package com.tutor.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.tutor.sse.SseSession;
import com.tutor.conversation.MessageService;
import com.tutor.learning.CognitiveDiagnosis;
import com.tutor.prompt.PromptStore;
import com.tutor.client.CourseClient;
import com.tutor.client.CourseInfo;
import com.tutor.knowledge.KnowledgeBase;
import com.tutor.knowledge.QueryRewriter;
import com.tutor.llm.LlmGateway;
import com.tutor.router.Slots;
import com.tutor.common.constant.SseCardType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 课程推荐智能体（交易域，旁路星型）：
 * 结合多轮对话上下文、用户目标岗位/技术栈与最近诊断的薄弱点，
 * 实现「多轮线索保持 + 领域精准召回 + 多样性智能兜底」的课程推荐。
 * 检索前置：ES 课程语义召回统一经过 QueryRewriter 做指代消解。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseRecommendAgent {

    public static final String AGENT = "课程推荐智能体";

    private final CourseClient courseClient;
    private final KnowledgeBase knowledgeBase;
    private final QueryRewriter queryRewriter;
    private final LlmGateway gateway;
    private final PromptStore promptStore;
    private final MessageService messageService;
    private final com.tutor.knowledge.SkillDictionary skillDictionary;

    // 预置职业方向与关键词映射（严格优先级：专业技术栈 > 通用求职）
    private static final Map<String, List<String>> TRACK_KEYWORDS;
    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("data-analytics", List.of("python", "数据分析", "pandas", "numpy", "数仓", "hive", "sql分析", "商业分析", "统计学", "爬虫"));
        m.put("ai-app", List.of("ai", "大模型", "agent", "rag", "prompt", "提示词", "langchain", "spring ai", "向量数据库", "embedding"));
        m.put("frontend", List.of("前端", "vue", "react", "javascript", "typescript", "ts", "html", "css", "小程序", "vite"));
        m.put("java-backend", List.of("java", "spring", "springboot", "mysql", "redis", "mybatis", "jvm", "mq", "kafka"));
        m.put("general", List.of("求职", "面试", "简历", "谈薪", "作品集", "测试", "自动化测试", "运维", "devops", "docker", "k8s", "linux", "产品经理", "prd"));
        TRACK_KEYWORDS = Collections.unmodifiableMap(m);
    }

    // 泛查询识别正则
    private static final Pattern GENERIC_PATTERNS = Pattern.compile(
            "^(有什么课程推荐(吗|呢)?|推荐(几门|一些|下)?课程?|报什么课(好|呢)?|有哪些课|我想看(看)?课(程)?|课程列表|全部课程|所有课程)$",
            Pattern.CASE_INSENSITIVE
    );

    public void recommend(LoopContext ctx, SseSession sse,
                          List<CognitiveDiagnosis.WeakPoint> weakPoints) {
        long start = System.currentTimeMillis();

        // 1. 多轮目标感知与召回线索分析
        TargetClue clue = resolveTargetClue(ctx, weakPoints);
        log.info("课程推荐目标感知: sessionId={}, detectedTrack={}, searchKeyword={}, isGeneric={}",
                ctx.getSessionId(), clue.track(), clue.searchKeyword(), clue.isGeneric());

        // 2. 候选课程召回（多轮线索召回 + 领域匹配 + 跨赛道多样性兜底）
        List<CourseInfo> candidates = recallCandidates(clue, ctx.getSessionId());

        // 3. LLM 个性化推荐理由（失败降级数据面预置 reason）
        if (gateway.available() && !candidates.isEmpty()) {
            try {
                String weakText = weakPoints != null && !weakPoints.isEmpty()
                        ? weakPoints.stream().map(w -> w.knowledgePoint() + "(" + w.score() + "分)").collect(Collectors.joining("、"))
                        : (clue.searchKeyword() != null ? "学员当前学习目标与技术兴趣：" + clue.searchKeyword() : "职业综合技术进阶");

                String courseText = candidates.stream()
                        .map(c -> c.getCourseId() + ":" + c.getName() + ":¥" + c.getPrice())
                        .collect(Collectors.joining("\n"));

                String promptTpl = promptStore.get(PromptStore.RECOMMEND);
                if (promptTpl == null || promptTpl.isBlank()) {
                    promptTpl = """
                            你是智能助教引擎的职业课程规划与推荐智能体。结合学员目标与技术薄弱点，从候选课程中生成个性化推荐方案。
                            只输出 JSON，不要输出其他内容。
                            学员目标与基础：{weakPoints}
                            候选课程（courseId:名称:价格）：
                            {courses}
                            要求：
                            1. intro：一两句精炼说明为什么推荐这几门课，若是泛问则引导学员指出具体目标岗位；
                            2. reasons：为每门课程给出针对性的推荐理由。
                            输出格式：
                            {"intro":"...","reasons":[{"courseId":"...","reason":"..."}]}
                            """;
                }

                String system = promptTpl
                        .replace("{weakPoints}", weakText)
                        .replace("{courses}", courseText);

                JsonNode node = gateway.callJson(system, ctx.getMessage(), null, 0.3);
                String intro = node.path("intro").asText("");
                if (!intro.isBlank()) {
                    StreamingSupport.streamText(intro, sse, 24, 18);
                    messageService.append(ctx.getSessionId(), "text", "assistant", Map.of("text", intro));
                }

                var reasons = node.path("reasons");
                if (reasons.isArray()) {
                    reasons.forEach(r -> {
                        String cid = r.path("courseId").asText();
                        String reason = r.path("reason").asText("");
                        candidates.stream().filter(c -> c.getCourseId().equals(cid))
                                .findFirst()
                                .ifPresent(c -> c.setReason(reason));
                    });
                }
            } catch (Exception e) {
                log.warn("个性化推荐理由生成失败，降级预置文案: {}", e.getMessage());
            }
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("source", "recommend");
        content.put("courses", candidates);
        ctx.addTrace(new TraceStep(AGENT, "结合多轮目标与技术栈推荐课程",
                gateway.available() ? "查询改写 + 多轮语境感知 + 领域召回 + LLM 理由生成" : "目标领域智能匹配（预置理由）",
                System.currentTimeMillis() - start,
                gateway.available() ? TraceStep.SUCCESS : TraceStep.DEGRADED));
        messageService.append(ctx.getSessionId(), "course_list", "assistant", content);
        sse.card(SseCardType.COURSE_LIST, content);
    }

    /**
     * 多轮对话目标与上下文解析（解决历史语境丢失与泛问丢失目标问题）
     */
    private TargetClue resolveTargetClue(LoopContext ctx, List<CognitiveDiagnosis.WeakPoint> weakPoints) {
        String msg = ctx.getMessage() != null ? ctx.getMessage().trim() : "";
        boolean isGeneric = GENERIC_PATTERNS.matcher(msg).find() || msg.equals("课程推荐") || msg.equals("推荐课程");

        // 1. 本轮消息中是否直接包含明确的技术方向词或语料知识点/课程
        String detected = findTrackByText(msg);
        if (detected != null) {
            return new TargetClue(detected, extractKeywordFromText(msg, detected), false);
        }
        String detectedKp = skillDictionary.findKnowledgePointInText(msg);
        if (detectedKp != null) {
            return new TargetClue(findTrackByText(detectedKp), detectedKp, false);
        }

        // 2. 检查会话槽位（SlotMergeService 已经沉淀历史轮次的槽位）
        Slots slots = ctx.getDecision() != null ? ctx.getDecision().slots() : null;
        if (slots != null) {
            if (slots.courseName() != null && !slots.courseName().isBlank()) {
                String track = findTrackByText(slots.courseName());
                return new TargetClue(track, slots.courseName(), false);
            }
            if (slots.knowledgePoint() != null && !slots.knowledgePoint().isBlank()) {
                String track = findTrackByText(slots.knowledgePoint());
                return new TargetClue(track, slots.knowledgePoint(), false);
            }
        }

        // 3. 检查诊断薄弱点
        if (weakPoints != null && !weakPoints.isEmpty()) {
            String wpKp = weakPoints.get(0).knowledgePoint();
            String track = findTrackByText(wpKp);
            if (track != null) {
                return new TargetClue(track, wpKp, false);
            }
        }

        // 4. 从用户历史发言回溯（仅检索学员本人发出的消息，坚决避免助教长篇回复的词汇污染）
        try {
            List<String> userHistory = messageService.recentUserTexts(ctx.getSessionId(), 8);
            for (int i = userHistory.size() - 1; i >= 0; i--) {
                String uText = userHistory.get(i);
                if (uText.trim().equalsIgnoreCase(msg)) {
                    continue; // 跳过本轮当前消息（已在 step 1 评估过）
                }
                String hTrack = findTrackByText(uText);
                if (hTrack != null) {
                    return new TargetClue(hTrack, extractKeywordFromText(uText, hTrack), false);
                }
            }
        } catch (Exception e) {
            log.debug("用户历史消息提取目标异常: {}", e.getMessage());
        }

        return new TargetClue(null, null, isGeneric || msg.length() < 10);
    }

    /**
     * 智能候选召回：拒绝固定 all().limit(3)！
     * 检索前置：ES 语义召回前经 QueryRewriter 消解多轮指代。
     */
    private List<CourseInfo> recallCandidates(TargetClue clue, String sessionId) {
        List<CourseInfo> candidates = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        // 场景 A：明确有目标技术栈或岗位方向（如 Python / 数据分析 / 前端 / Java）
        if (clue.track() != null || clue.searchKeyword() != null) {
            String searchQ = clue.searchKeyword() != null ? clue.searchKeyword() : clue.track();
            // 查询改写：把多轮指代表述补全为独立检索查询（失败原样返回）
            String rewrittenQ = queryRewriter.rewrite(sessionId, searchQ);

            // A1. 优先从 ES course_intro 向量+全文检索
            try {
                for (KnowledgeBase.Chunk c : knowledgeBase.searchCourses(rewrittenQ, 6)) {
                    CourseInfo info = courseClient.byId(c.getId());
                    if (info != null && (clue.track() == null || matchesTrack(info, clue.track())
                            || (searchQ != null && info.getName().toLowerCase().contains(searchQ.toLowerCase())))
                            && seenIds.add(info.getCourseId())) {
                        candidates.add(info);
                    }
                    if (candidates.size() >= 3) break;
                }
            } catch (Exception e) {
                log.warn("ES 课程语义检索降级: {}", e.getMessage());
            }

            // A2. 若 ES 召回不足 3 门，从本地课程库按 tag 或关键词补充同领域的课程（绝不跳到其他不相关方向！）
            if (candidates.size() < 3) {
                List<CourseInfo> domainCourses = courseClient.all().stream()
                        .filter(c -> (clue.track() != null && matchesTrack(c, clue.track()))
                                || (searchQ != null && (c.getName().toLowerCase().contains(searchQ.toLowerCase())
                                        || searchQ.toLowerCase().contains(c.getName().toLowerCase())
                                        || (c.getTags() != null && c.getTags().stream().anyMatch(t -> searchQ.toLowerCase().contains(t.toLowerCase()))))))
                        .toList();
                for (CourseInfo c : domainCourses) {
                    if (seenIds.add(c.getCourseId())) {
                        candidates.add(c);
                    }
                    if (candidates.size() >= 3) break;
                }
            }
            if (!candidates.isEmpty()) {
                return candidates;
            }
        }

        // 场景 B：完全无方向的泛问（如新会话直接问“有什么课程推荐吗”）
        // 跨赛道多轨精选策略：按赛道/方向动态聚类选取，避免硬编码具体课程 ID
        List<CourseInfo> allCourses = courseClient.all();
        List<CourseInfo> diverse = new ArrayList<>();
        Set<String> tracks = new HashSet<>();
        for (CourseInfo c : allCourses) {
            String t = (c.getTags() != null && !c.getTags().isEmpty()) ? c.getTags().get(0) : "default";
            if (tracks.add(t)) {
                diverse.add(c);
            }
            if (diverse.size() >= 3) break;
        }
        for (CourseInfo c : allCourses) {
            if (diverse.size() >= 3) break;
            addIfPresent(diverse, c);
        }
        if (!diverse.isEmpty()) {
            return diverse;
        }
        return allCourses.stream().limit(3).toList();
    }

    private void addIfPresent(List<CourseInfo> list, CourseInfo c) {
        if (c != null && list.stream().noneMatch(x -> x.getCourseId().equals(c.getCourseId()))) {
            list.add(c);
        }
    }

    private String findTrackByText(String text) {
        if (text == null || text.isBlank()) return null;
        String lower = text.toLowerCase();
        for (Map.Entry<String, List<String>> entry : TRACK_KEYWORDS.entrySet()) {
            for (String kw : entry.getValue()) {
                if (lower.contains(kw)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private String extractKeywordFromText(String text, String track) {
        if (text == null || track == null) return null;
        String lower = text.toLowerCase();
        List<String> keywords = TRACK_KEYWORDS.get(track);
        if (keywords != null) {
            for (String kw : keywords) {
                if (lower.contains(kw)) {
                    return kw;
                }
            }
        }
        return track;
    }

    private boolean matchesTrack(CourseInfo c, String track) {
        if (track == null || c == null) return true;
        if (c.getTags() != null) {
            if (c.getTags().contains(track)) return true;
            for (String tag : c.getTags()) {
                if (findTrackByText(tag) != null && findTrackByText(tag).equals(track)) {
                    return true;
                }
            }
        }
        return false;
    }

    private record TargetClue(String track, String searchKeyword, boolean isGeneric) {
    }
}
