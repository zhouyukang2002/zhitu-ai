package com.tutor.knowledge;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * 技能词典（替代原知识图谱的词典职责）：
 * 知识点/经验水平受控词表，来源为语料同步产物 data/kp-dictionary.json
 * （由 CorpusSyncService 从课程/知识条目语料汇总生成），保证路由槽位词典与语料同源。
 * 语料未同步时使用内置兜底词表，保证引擎启动即可用。
 * 名词说明：这是"受控词表"而非图谱——没有节点/边结构，只服务于槽位词典强约束与别名归一。
 */
@Slf4j
@Component
public class SkillDictionary {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 经验水平词表（原"学段"的职教语义） */
    private static final List<String> FALLBACK_LEVELS =
            List.of("零基础", "在校生", "1-3年经验", "3年以上经验");

    private static final List<String> FALLBACK_KPS = List.of(
            "JavaSE 基础", "面向对象", "流程控制", "集合框架", "泛型", "多线程", "IO 流", "异常处理",
            "SQL 基础", "MySQL 索引", "事务与锁", "数据库设计", "查询优化",
            "JDBC", "MyBatis", "连接池", "动态 SQL",
            "HTTP 与 Servlet", "Spring MVC", "RESTful API", "会话与 Cookie", "MVC 分层",
            "面试", "学习方法");

    private final AppProperties props;

    private volatile List<String> kps = FALLBACK_KPS;
    private volatile List<String> levels = FALLBACK_LEVELS;
    private final java.util.Map<String, String> dynamicAliases = new java.util.concurrent.ConcurrentHashMap<>();

    public SkillDictionary(AppProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void load() {
        reload();
    }

    /** 语料同步后调用：刷新受控词表（文件不存在时保留当前词表） */
    public synchronized void reload() {
        try {
            File file = new File(props.getDataDir(), "kp-dictionary.json");
            if (!file.exists()) {
                return;
            }
            var dict = MAPPER.readValue(FileUtil.readUtf8String(file), new TypeReference<java.util.Map<String, Object>>() {
            });
            Object loadedKps = dict.get("kps");
            Object loadedLevels = dict.get("experienceLevels");
            Object loadedAliases = dict.get("aliases");
            if (loadedKps instanceof List<?> list && !list.isEmpty()) {
                kps = list.stream().map(String::valueOf).toList();
            }
            if (loadedLevels instanceof List<?> list && !list.isEmpty()) {
                levels = list.stream().map(String::valueOf).toList();
            }
            if (loadedAliases instanceof java.util.Map<?, ?> map) {
                dynamicAliases.clear();
                map.forEach((k, v) -> {
                    if (k != null && v != null) {
                        dynamicAliases.put(String.valueOf(k).toLowerCase().trim(), String.valueOf(v).trim());
                    }
                });
            }
            log.info("技能词典加载完成：{} 个知识点 / {} 个经验档位 / {} 个动态别名", kps.size(), levels.size(), dynamicAliases.size());
        } catch (Exception e) {
            log.warn("技能词典加载失败（沿用当前词表）: {}", e.getMessage());
        }
    }

    public List<String> allNames() {
        return kps;
    }

    public List<String> experienceLevels() {
        return levels;
    }

    private static final java.util.Map<String, String> ALIAS_MAP = new java.util.LinkedHashMap<>();
    static {
        ALIAS_MAP.put("python", "Python 基础");
        ALIAS_MAP.put("java", "JavaSE 基础");
        ALIAS_MAP.put("javase", "JavaSE 基础");
        ALIAS_MAP.put("sql", "SQL 基础");
        ALIAS_MAP.put("mysql", "MySQL 索引");
        ALIAS_MAP.put("vue", "Vue3 响应式");
        ALIAS_MAP.put("vue3", "Vue3 响应式");
        ALIAS_MAP.put("react", "React 组件与 JSX");
        ALIAS_MAP.put("spring", "Spring IoC");
        ALIAS_MAP.put("springboot", "SpringBoot 自动配置");
        ALIAS_MAP.put("redis", "Redis 数据结构");
        ALIAS_MAP.put("git", "Git 基础");
        ALIAS_MAP.put("linux", "Linux 常用命令");
        ALIAS_MAP.put("docker", "Docker 容器化");
        ALIAS_MAP.put("k8s", "Kubernetes 基础");
        ALIAS_MAP.put("kubernetes", "Kubernetes 基础");
        ALIAS_MAP.put("ai", "大模型 API");
        ALIAS_MAP.put("大模型", "大模型 API");
        ALIAS_MAP.put("rag", "RAG 管线");
        ALIAS_MAP.put("agent", "Agent 编排");
        ALIAS_MAP.put("mcp", "MCP 协议");
        ALIAS_MAP.put("集合", "集合框架");
        ALIAS_MAP.put("多线程", "多线程");
        ALIAS_MAP.put("并发", "多线程");
        ALIAS_MAP.put("java并发", "多线程");
        ALIAS_MAP.put("hashmap", "集合框架");
        ALIAS_MAP.put("concurrenthashmap", "多线程");
        ALIAS_MAP.put("红黑树", "集合框架");
        ALIAS_MAP.put("线程安全", "多线程");
        ALIAS_MAP.put("锁", "事务与锁");
        ALIAS_MAP.put("分段锁", "多线程");
        ALIAS_MAP.put("两数之和", "集合框架");
    }

    /** 非技术实体的修饰性元属性词（在存在真实技术关键词时严禁抢占主题） */
    private static final java.util.Set<String> META_KPS = java.util.Set.of("面试", "学习方法");

    /** 别名归一：完全匹配优先，其次常见裸词标准化，最后大小写不敏感包含匹配，无命中返回原值 */
    public String byNameOrAlias(String name) {
        if (StrUtil.isBlank(name)) {
            return name;
        }
        String clean = name.trim();
        // 1. 完全精确匹配（优先原样）
        for (String kp : kps) {
            if (kp.equalsIgnoreCase(clean)) {
                return kp;
            }
        }
        // 2. 语料动态别名（优先）与静态常见裸词标准化映射
        String lower = clean.toLowerCase();
        if (dynamicAliases.containsKey(lower)) {
            return dynamicAliases.get(lower);
        }
        if (ALIAS_MAP.containsKey(lower)) {
            return ALIAS_MAP.get(lower);
        }
        // 3. 大小写不敏感包含匹配
        for (String kp : kps) {
            String kpLower = kp.toLowerCase();
            if (kpLower.contains(lower) || lower.contains(kpLower)) {
                return kp;
            }
        }
        return name;
    }

    /**
     * 从整句文本（如多轮历史发言）中抽取识别知识点：
     * 1. 优先最长匹配已知技术受控词表（排除元属性词如"面试"）；
     * 2. 其次匹配技术别名/裸词（语料动态别名优先，按关键词长度最长匹配）；
     * 3. 若无任何具体技术实体，最后才兜底匹配元属性词（如"面试"、"学习方法"）。
     */
    public String findKnowledgePointInText(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        String lower = text.toLowerCase();
        // 1. 最长技术受控词匹配（排除通用元属性词）
        String bestKp = null;
        for (String kp : kps) {
            if (META_KPS.contains(kp)) {
                continue;
            }
            String kpLower = kp.toLowerCase();
            if (lower.contains(kpLower)) {
                if (bestKp == null || kp.length() > bestKp.length()) {
                    bestKp = kp;
                }
            }
        }
        if (bestKp != null) {
            return bestKp;
        }

        // 2. 别名/裸词技术匹配（动态语料别名优先，其次静态兜底，按关键词长度最长匹配）
        String bestAliasKp = null;
        int maxLen = 0;
        for (java.util.Map.Entry<String, String> entry : dynamicAliases.entrySet()) {
            String key = entry.getKey();
            if (lower.contains(key) && key.length() > maxLen) {
                bestAliasKp = entry.getValue();
                maxLen = key.length();
            }
        }
        for (java.util.Map.Entry<String, String> entry : ALIAS_MAP.entrySet()) {
            String key = entry.getKey();
            if (lower.contains(key) && key.length() > maxLen) {
                bestAliasKp = entry.getValue();
                maxLen = key.length();
            }
        }
        if (bestAliasKp != null) {
            return bestAliasKp;
        }

        // 3. 无任何技术实体时，才匹配元属性词
        for (String metaKp : META_KPS) {
            if (lower.contains(metaKp.toLowerCase())) {
                return metaKp;
            }
        }
        return null;
    }
}
