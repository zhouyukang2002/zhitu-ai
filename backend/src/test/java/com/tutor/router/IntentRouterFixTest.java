package com.tutor.router;

import com.tutor.config.AppProperties;
import com.tutor.conversation.MessageService;
import com.tutor.knowledge.SkillDictionary;
import com.tutor.learning.LearningState;
import com.tutor.learning.SessionStateService;
import com.tutor.observ.TraceContext;
import com.tutor.observ.TraceRecorder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class IntentRouterFixTest {

    private SkillDictionary skillDictionary;
    private SlotExtractor slotExtractor;
    private LlmIntentClassifier llmClassifier;
    private IntentLogWriter logWriter;
    private com.tutor.llm.LlmGateway gateway;
    private TraceRecorder traceRecorder;
    private AppProperties props;
    private SlotMergeService slotMergeService;
    private RuleFastPath ruleFastPath;
    private ConfidenceCalibrator confidenceCalibrator;
    private SessionStateService stateService;
    private MessageService messageService;

    private IntentRouter router;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        skillDictionary = new SkillDictionary(props);

        slotExtractor = Mockito.mock(SlotExtractor.class);
        llmClassifier = Mockito.mock(LlmIntentClassifier.class);
        logWriter = Mockito.mock(IntentLogWriter.class);
        gateway = Mockito.mock(com.tutor.llm.LlmGateway.class);
        traceRecorder = Mockito.mock(TraceRecorder.class);
        slotMergeService = Mockito.mock(SlotMergeService.class);
        ruleFastPath = Mockito.mock(RuleFastPath.class);
        confidenceCalibrator = Mockito.mock(ConfidenceCalibrator.class);
        stateService = Mockito.mock(SessionStateService.class);
        messageService = Mockito.mock(MessageService.class);

        when(slotMergeService.merge(any(), any())).thenAnswer(i -> i.getArgument(1));
        when(ruleFastPath.evaluate(any())).thenReturn(RuleFastPath.EMPTY);

        router = new IntentRouter(
                slotExtractor,
                llmClassifier,
                logWriter,
                gateway,
                traceRecorder,
                props,
                slotMergeService,
                ruleFastPath,
                confidenceCalibrator,
                stateService,
                messageService,
                skillDictionary
        );
    }

    @Test
    void testSkillDictionary_findKnowledgePointInText() {
        Assertions.assertEquals("Python 基础", skillDictionary.findKnowledgePointInText("你知道python吗"));
        Assertions.assertEquals("Python 基础", skillDictionary.findKnowledgePointInText("我想学Python，怎么规划？"));
        Assertions.assertEquals("MySQL 索引", skillDictionary.findKnowledgePointInText("考考我mysql索引"));
        Assertions.assertEquals("Vue3 响应式", skillDictionary.findKnowledgePointInText("学vue3有什么课程推荐"));
        Assertions.assertNull(skillDictionary.findKnowledgePointInText("帮我出几道题呢"));
    }

    @Test
    void testCompoundIntent_RecommendAndExercise() {
        String msg = "可以推荐课程然后帮我出几道题吗";
        when(llmClassifier.recognize(eq(msg), any(), any()))
                .thenReturn(new IntentResult(Intent.COURSE_RECOMMEND, Slots.empty(), 0.9, List.of(Stage.RECOMMEND, Stage.EXERCISE)));

        TraceContext tc = new TraceContext("test-trace", "s1", 1L, msg);
        RouteDecision decision = router.route("s1", msg, LearningState.LEARNING, List.of(), tc);

        Assertions.assertTrue(decision.stages().contains(Stage.RECOMMEND));
        Assertions.assertTrue(decision.stages().contains(Stage.EXERCISE));
        Assertions.assertEquals(2, decision.stages().size());
    }

    @Test
    void testContextAmnesia_BackfillTopicFromHistory() {
        String msg = "帮我出几道题呢";
        when(llmClassifier.recognize(eq(msg), any(), any()))
                .thenReturn(new IntentResult(Intent.EXERCISE, Slots.empty(), 0.85, null));
        when(messageService.recentUserTexts(eq("s1"), anyInt()))
                .thenReturn(List.of("你知道python吗", "可以推荐课程然后帮我出几道题吗", msg));

        TraceContext tc = new TraceContext("test-trace", "s1", 1L, msg);
        RouteDecision decision = router.route("s1", msg, LearningState.LEARNING, List.of(), tc);

        // 验证：不会被降级成 CLARIFY_NEEDED，而是成功回溯出 Python 基础，并保持 EXERCISE
        Assertions.assertEquals(Intent.EXERCISE, decision.intent());
        Assertions.assertEquals("Python 基础", decision.slots().knowledgePoint());
        Assertions.assertTrue(decision.decisionTrace().stream().anyMatch(t -> t.contains("CONTEXT_BACKFILL")));
    }

    @Test
    void testTechKnowledgePointPrecedenceOverInterview() {
        String msg = "原来会死循环或者数据丢失啊。那老师你能不能出一道经典的Java并发或集合面试真题考考我？带代码片段或者场景题的那种，别太难也别太水。";
        String kp = skillDictionary.findKnowledgePointInText(msg);
        Assertions.assertNotNull(kp);
        Assertions.assertTrue("多线程".equals(kp) || "集合框架".equals(kp), "知识点抽取应为技术实体（多线程/集合框架），而非通用面试: " + kp);
    }

    @Test
    void testAnswerAndDiscussionRevisedToTeach() {
        String msg = "这道题我觉得应该选 B。因为 ConcurrentHashMap 在 JDK8 里面是靠 Segment 分段锁来保证并发安全的，每个段独立加锁，这样对吗？";
        when(llmClassifier.recognize(eq(msg), any(), any()))
                .thenReturn(new IntentResult(Intent.EXERCISE, new Slots("多线程", null, null, null, null, null, null, null), 0.88, null));

        TraceContext tc = new TraceContext("test-trace", "s1", 1L, msg);
        RouteDecision decision = router.route("s1", msg, LearningState.PRACTICING, List.of(), tc);

        // 验证：做题态下作答与概念求证必须矫正为 TEACH，绝不能再次出题
        Assertions.assertEquals(Intent.TEACH, decision.intent());
        Assertions.assertTrue(decision.decisionTrace().stream().anyMatch(t -> t.contains("STATE_REVISE")));
    }
}
