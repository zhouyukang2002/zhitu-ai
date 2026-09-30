package com.tutor.prompt;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.watch.WatchMonitor;
import com.tutor.config.AppProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 提示词热更新（方案 B：本地文件监听 + AtomicReference 刷新）。
 * 本地化配置中心：数据源为本地目录，
 * WatchService 监听变更、原子引用无锁刷新——复用原 SystemPromptConfig 的模式。
 *
 * 热更新边界（面试主动讲）：提示词与 Agent 参数可热更新；
 * @Tool 工具描述属于代码，不在热更新范围。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptStore {

    private final AppProperties props;

    /** 提示词名 -> 原子引用（线程安全读，无锁刷新） */
    private final Map<String, AtomicReference<String>> store = new LinkedHashMap<>();

    private WatchMonitor monitor;

    public static final String ROUTER = "router";
    public static final String TEACHING = "teaching";
    public static final String CHITCHAT = "chitchat";
    public static final String GRADING = "grading";
    public static final String RECOMMEND = "recommend";
    public static final String CLARIFY = "clarify";
    public static final String JUDGE = "judge";
    public static final String REWRITE = "rewrite";
    public static final String PLAN = "plan";
    public static final String QUESTION_GEN = "question_gen";
    public static final String SUMMARIZE = "summarize";
    public static final String RECAP = "recap";

    @PostConstruct
    public void init() {
        // 预置内置默认（文件缺失时兜底，保证启动即可用）
        store.put(ROUTER, new AtomicReference<>(Defaults.ROUTER));
        store.put(TEACHING, new AtomicReference<>(Defaults.TEACHING));
        store.put(CHITCHAT, new AtomicReference<>(Defaults.CHITCHAT));
        store.put(GRADING, new AtomicReference<>(Defaults.GRADING));
        store.put(RECOMMEND, new AtomicReference<>(Defaults.RECOMMEND));
        store.put(CLARIFY, new AtomicReference<>(Defaults.CLARIFY));
        store.put(JUDGE, new AtomicReference<>(Defaults.JUDGE));
        store.put(REWRITE, new AtomicReference<>(Defaults.REWRITE));
        store.put(PLAN, new AtomicReference<>(Defaults.PLAN));
        store.put(QUESTION_GEN, new AtomicReference<>(Defaults.QUESTION_GEN));
        store.put(SUMMARIZE, new AtomicReference<>(Defaults.SUMMARIZE));
        store.put(RECAP, new AtomicReference<>(Defaults.RECAP));
        loadAll();
        startWatch();
    }

    private void loadAll() {
        File dir = new File(props.getPromptDir());
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("提示词目录创建失败: {}", dir.getAbsolutePath());
            return;
        }
        store.keySet().forEach(name -> {
            File file = new File(dir, name + ".md");
            if (file.exists()) {
                store.get(name).set(FileUtil.readUtf8String(file));
                log.info("加载提示词 {}: {} 字符", name, file.length());
            }
        });
    }

    private void startWatch() {
        try {
            File dir = new File(props.getPromptDir());
            if (!dir.exists()) {
                return;
            }
            monitor = WatchMonitor.create(Paths.get(dir.getAbsolutePath()),
                    java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
                    java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
            monitor.setWatcher(new cn.hutool.core.io.watch.Watcher() {
                @Override
                public void onCreate(java.nio.file.WatchEvent<?> event, java.nio.file.Path currentPath) {
                    handle(event);
                }

                @Override
                public void onModify(java.nio.file.WatchEvent<?> event, java.nio.file.Path currentPath) {
                    handle(event);
                }

                @Override
                public void onDelete(java.nio.file.WatchEvent<?> event, java.nio.file.Path currentPath) {
                }

                @Override
                public void onOverflow(java.nio.file.WatchEvent<?> event, java.nio.file.Path currentPath) {
                }

                private void handle(java.nio.file.WatchEvent<?> event) {
                    Object ctx = event.context();
                    if (ctx == null) {
                        return;
                    }
                    String filename = ctx.toString();
                    if (!filename.endsWith(".md")) {
                        return;
                    }
                    String name = filename.substring(0, filename.length() - 3);
                    if (!store.containsKey(name)) {
                        return;
                    }
                    // 防抖 + 全量容错：编辑器保存（临时文件+改名）会触发多次事件，Windows 上
                    // 读取还可能撞上其他进程的写锁——本监听是非守护线程，这里若抛出未捕获
                    // 异常会直接带崩整个 JVM（表现为服务静默退出 exit code 1），绝不允许。
                    try {
                        Thread.sleep(300);
                        String content = readWithRetry(new File(dir, filename), 2);
                        store.get(name).set(content);
                        log.info("提示词热更新生效: {}（{} 字符）", name, content.length());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable t) {
                        log.error("提示词热更新失败（保留旧版本，下次保存自动重试）: {} -> {}", name, t.getMessage());
                    }
                }

                /** Windows 下文件可能被编辑器短暂锁定，读取失败间隔 500ms 重试 */
                private String readWithRetry(File file, int maxRetry) {
                    for (int i = 0; ; i++) {
                        try {
                            return FileUtil.readString(file, StandardCharsets.UTF_8);
                        } catch (Exception e) {
                            if (i >= maxRetry) {
                                throw e;
                            }
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw e;
                            }
                        }
                    }
                }
            }).start();
            log.info("提示词热更新监听已启动: {}", dir.getAbsolutePath());
        } catch (Exception e) {
            log.warn("提示词监听启动失败（热更新不可用，不影响启动）: {}", e.getMessage());
        }
    }

    /** 管理端手动刷新（配合 GitOps：git pull 后调 POST /api/admin/prompt/reload） */
    public void reload() {
        loadAll();
    }

    public String get(String name) {
        AtomicReference<String> ref = store.get(name);
        return ref == null ? "" : ref.get();
    }

    /**
     * 声明式提示词模板渲染：
     * 将模板中的命名占位符 {key} 安全替换为对应参数值，
     * 支持 null 安全转换，彻底消除智能体中脆弱繁琐的链式 .replace()。
     */
    public String render(String name, Map<String, Object> params) {
        String template = get(name);
        if (params == null || params.isEmpty() || template.isEmpty()) {
            return template;
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String val = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            template = template.replace(placeholder, val);
        }
        return template;
    }

    public Map<String, Integer> snapshot() {
        Map<String, Integer> snap = new LinkedHashMap<>();
        store.forEach((k, v) -> snap.put(k, v.get().length()));
        return snap;
    }

    @PreDestroy
    public void destroy() {
        if (monitor != null) {
            monitor.close();
        }
    }
}
