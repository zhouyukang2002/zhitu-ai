package com.tutor.knowledge;

import com.tutor.config.AppProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

class DocumentParserAndManageTest {

    private DocumentParserService parserService;
    private AppProperties props;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        parserService = new DocumentParserService();
    }

    @Test
    void testParseMarkdownWithBreadcrumbs() throws Exception {
        String md = """
                ## 装饰器原理
                装饰器本质是一个高阶函数，接收函数并返回包装后的新函数。
                
                ## 闭包机制
                内部函数引用了外部非全局变量，该变量被绑定保存在 __closure__ 中。
                """;
        var parsed = parserService.parse(
                new ByteArrayInputStream(md.getBytes(StandardCharsets.UTF_8)),
                "Python高级语法.md", "SKILL", "Python 基础", "《Python实战》"
        );

        Assertions.assertEquals("SKILL", parsed.category());
        Assertions.assertEquals("Python 基础", parsed.kp());
        Assertions.assertEquals(2, parsed.chunks().size());

        var c1 = parsed.chunks().get(0);
        Assertions.assertEquals("装饰器原理", c1.title());
        Assertions.assertTrue(c1.breadcrumb().contains("Python高级语法 > 装饰器原理"));
        Assertions.assertTrue(c1.text().contains("装饰器本质是一个高阶函数"));

        var c2 = parsed.chunks().get(1);
        Assertions.assertEquals("闭包机制", c2.title());
        Assertions.assertTrue(c2.breadcrumb().contains("Python高级语法 > 闭包机制"));
    }

    @Test
    void testParsePlainTextGeneratesHeading() throws Exception {
        String text = "这是没有任何标题的通用政策说明正文，退款需在7天内申请。";
        var parsed = parserService.parse(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                "售后服务条款.txt", "POLICY", "服务政策", "官方售后部"
        );

        Assertions.assertEquals("POLICY", parsed.category());
        Assertions.assertEquals(1, parsed.chunks().size());
        Assertions.assertTrue(parsed.chunks().get(0).title().contains("售后服务条款"));
        Assertions.assertTrue(parsed.markdownContent().contains("## 售后服务条款"));
    }

    @Test
    void testSemanticCacheService_HitAndMiss() {
        var redisTemplate = Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        var valueOps = Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        var setOps = Mockito.mock(org.springframework.data.redis.core.SetOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOps);
        Mockito.when(redisTemplate.opsForSet()).thenReturn(setOps);

        var embeddingService = Mockito.mock(EmbeddingService.class);
        Mockito.when(embeddingService.available()).thenReturn(false);

        SemanticCacheService cache = new SemanticCacheService(redisTemplate, embeddingService);
        var res = cache.get("测试问题");
        Assertions.assertTrue(res.isEmpty());

        var stats = cache.getStats();
        Assertions.assertNotNull(stats.get("hits"));
        Assertions.assertNotNull(stats.get("misses"));
    }
}
