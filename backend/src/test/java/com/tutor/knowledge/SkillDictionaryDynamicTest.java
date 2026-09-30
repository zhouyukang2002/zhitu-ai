package com.tutor.knowledge;

import cn.hutool.core.io.FileUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.client.CourseClient;
import com.tutor.config.AppProperties;
import com.tutor.router.SlotExtractor;
import com.tutor.router.Slots;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class SkillDictionaryDynamicTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testDynamicAliasesAndKpsLoadAndResolve(@TempDir Path tempDir) throws Exception {
        AppProperties props = new AppProperties();
        props.setDataDir(tempDir.toString());

        // 模拟语料同步输出的 kp-dictionary.json（新增 Go 语言与云原生知识点及动态别名）
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("kps", List.of("Go 语言入门", "Go 协程与 Channel", "Kubernetes 调度器", "JavaSE 基础"));
        dict.put("experienceLevels", List.of("零基础", "在校生", "1-3年经验"));

        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("golang", "Go 语言入门");
        aliases.put("go", "Go 语言入门");
        aliases.put("goroutine", "Go 协程与 Channel");
        aliases.put("channel", "Go 协程与 Channel");
        aliases.put("k8s调度", "Kubernetes 调度器");
        dict.put("aliases", aliases);

        File dictFile = new File(tempDir.toFile(), "kp-dictionary.json");
        MAPPER.writeValue(dictFile, dict);

        SkillDictionary skillDictionary = new SkillDictionary(props);
        skillDictionary.reload();

        // 1. 验证知识点列表包含新语料知识点
        Assertions.assertTrue(skillDictionary.allNames().contains("Go 语言入门"));
        Assertions.assertTrue(skillDictionary.allNames().contains("Go 协程与 Channel"));

        // 2. 验证动态别名解析（零代码修改直接生效）
        Assertions.assertEquals("Go 语言入门", skillDictionary.byNameOrAlias("golang"));
        Assertions.assertEquals("Go 协程与 Channel", skillDictionary.byNameOrAlias("goroutine"));
        Assertions.assertEquals("Kubernetes 调度器", skillDictionary.byNameOrAlias("k8s调度"));

        // 3. 验证长句文本中的动态别名/知识点提取
        String text1 = "老师请问一下 goroutine 和普通线程有什么底层区别？";
        Assertions.assertEquals("Go 协程与 Channel", skillDictionary.findKnowledgePointInText(text1));

        String text2 = "我想了解关于 Go 语言入门 的学习路径";
        Assertions.assertEquals("Go 语言入门", skillDictionary.findKnowledgePointInText(text2));

        // 4. 验证 SlotExtractor 在零代码变动下，对新知识点的槽位约束与校验完全通过
        CourseClient courseClient = Mockito.mock(CourseClient.class);
        Mockito.when(courseClient.all()).thenReturn(List.of());
        SlotExtractor slotExtractor = new SlotExtractor(skillDictionary, courseClient);

        Slots inputSlots = new Slots("golang", null, null, null, null, 3, null, "基础");
        Slots validated = slotExtractor.validate(inputSlots);

        Assertions.assertEquals("Go 语言入门", validated.knowledgePoint());
        Assertions.assertEquals(3, validated.questionCount());
        Assertions.assertEquals("基础", validated.difficulty());
    }
}
