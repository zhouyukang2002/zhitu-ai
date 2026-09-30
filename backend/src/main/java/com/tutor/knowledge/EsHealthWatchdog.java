package com.tutor.knowledge;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Elasticsearch 自动探活与守护看门狗：
 * 定时检测 9200 ES 节点健康状况；若发生无声退出或异常离线，自动尝试从本地安装目录重新拉起。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsHealthWatchdog {

    /** 探活周期：30s 一次 ping（ES 启动到分片就绪约 20-40s，周期过短会重复触发拉起） */
    private static final long PING_INTERVAL_MS = 30_000;
    private static final long PING_INITIAL_DELAY_MS = 15_000;
    /** 拉起重试节流：防止 ping 未恢复期间反复启动多个 ES 实例 */
    private static final long RESTART_THROTTLE_MS = 90_000;

    private final com.tutor.config.AppProperties props;

    private final ElasticsearchClient esClient;
    private final AtomicBoolean lastStateOnline = new AtomicBoolean(true);
    private long lastRestartAttempt = 0;

    @Scheduled(fixedDelay = PING_INTERVAL_MS, initialDelay = PING_INITIAL_DELAY_MS)
    public void checkHealth() {
        try {
            boolean ping = esClient.ping().value();
            if (ping) {
                if (!lastStateOnline.getAndSet(true)) {
                    log.info("✅ [ES探活看门狗] Elasticsearch 服务已恢复正常在线状态！");
                }
                return;
            }
        } catch (Exception e) {
            // ping 失败
        }

        boolean wasOnline = lastStateOnline.getAndSet(false);
        if (wasOnline) {
            log.warn("⚠️ [ES探活看门狗] 检测到 Elasticsearch 服务离线或无响应，准备自愈...");
        }

        attemptAutoRestart();
    }

    private synchronized void attemptAutoRestart() {
        long now = System.currentTimeMillis();
        if (now - lastRestartAttempt < RESTART_THROTTLE_MS) {
            return;
        }
        lastRestartAttempt = now;

        // ES 安装根目录走配置（默认相对路径，跨机器/开源部署不失效），仅本地开发环境在 yml 覆盖
        if (cn.hutool.core.util.StrUtil.isBlank(props.getEsHome())) {
            return;
        }
        File esBat = new File(new File(props.getEsHome()), "bin/elasticsearch.bat");
        if (!esBat.exists()) {
            log.debug("[ES探活看门狗] 未在 tutor.es-home={} 下找到 elasticsearch.bat，跳过自动进程拉起", props.getEsHome());
            return;
        }

        try {
            log.info("🚀 [ES探活看门狗] 正在后台启动 Elasticsearch: {}", esBat.getAbsolutePath());
            new ProcessBuilder("cmd.exe", "/c", esBat.getAbsolutePath())
                    .directory(esBat.getParentFile())
                    .start();
        } catch (Exception e) {
            log.error("❌ [ES探活看门狗] 自动拉起 Elasticsearch 失败: {}", e.getMessage());
        }
    }
}
