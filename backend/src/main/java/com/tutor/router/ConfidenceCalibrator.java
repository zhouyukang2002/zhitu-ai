package com.tutor.router;

import cn.hutool.core.io.FileUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 意图置信度校准 + 低置信迟滞（P1）。
 *
 * <p><b>校准</b>：LLM 自报 confidence 普遍虚高且未经校准，直接拿一个拍脑袋阈值比较并不可靠。
 * 本组件支持按「置信度分桶 → 经验准确率」做等距校准（reliability 映射）：
 * 校准表来自 data/confidence-calibration.json（由离线评测生成，见 {@link #ece}）；
 * 未配置或开关关闭时 calibrate 恒等返回，<b>默认不改变任何线上行为</b>。
 *
 * <p><b>迟滞（hysteresis）</b>：单次 LLM 置信度在阈值附近抖动会导致"动不动就澄清追问"。
 * 改为会话级连续计数：连续 N 次（阈值可配，默认 1 = 与旧行为一致）业务意图低置信且无规则互证，
 * 才降级 CLARIFY；规则快路径与 LLM 互证、或恢复高置信时立即清零。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfidenceCalibrator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppProperties props;

    /** 校准桶（按 lo 升序），空表示不校准（恒等） */
    private volatile List<Bucket> buckets = List.of();

    /** 会话 → 连续低置信次数（无规则互证） */
    private final ConcurrentHashMap<String, Integer> lowConfStreak = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        reload();
    }

    /** 从文件加载校准表（文件缺失/解析失败则保持恒等，不影响启动） */
    public synchronized void reload() {
        try {
            AppProperties.Route route = props.getAi().getRoute();
            if (!route.isCalibrationEnabled()) {
                this.buckets = List.of();
                return;
            }
            File file = new File(route.getCalibrationFile());
            if (!file.exists()) {
                log.info("置信度校准表不存在，按恒等处理: {}", file.getAbsolutePath());
                this.buckets = List.of();
                return;
            }
            JsonNode root = MAPPER.readTree(FileUtil.readUtf8String(file));
            java.util.List<Bucket> parsed = new java.util.ArrayList<>();
            for (JsonNode b : root.path("buckets")) {
                parsed.add(new Bucket(b.path("lo").asDouble(), b.path("hi").asDouble(), b.path("acc").asDouble()));
            }
            parsed.sort(java.util.Comparator.comparingDouble(Bucket::lo));
            this.buckets = List.copyOf(parsed);
            log.info("置信度校准表加载完成：{} 个分桶", buckets.size());
        } catch (Exception e) {
            this.buckets = List.of();
            log.warn("置信度校准表加载失败，按恒等处理: {}", e.getMessage());
        }
    }

    /** 校准：有表则映射到所在桶经验准确率，无表恒等返回 */
    public double calibrate(double raw) {
        List<Bucket> snapshot = buckets;
        if (snapshot.isEmpty()) {
            return raw;
        }
        for (Bucket b : snapshot) {
            if (raw >= b.lo() && raw < b.hi()) {
                return b.acc();
            }
        }
        return raw;
    }

    public boolean calibrationActive() {
        return !buckets.isEmpty();
    }

    /**
     * 迟滞裁决：本次业务意图低置信，是否应降级为 CLARIFY。
     *
     * @param sessionId      会话（跨轮计数）
     * @param lowConfidence  校准后是否仍低于阈值
     * @param ruleReinforced 规则快路径是否与 LLM 意图一致（互证，互证则不算低置信）
     * @return true=本次应澄清；false=本次放行
     */
    public boolean shouldClarify(String sessionId, boolean lowConfidence, boolean ruleReinforced) {
        if (!lowConfidence || ruleReinforced) {
            lowConfStreak.remove(sessionId);
            return false;
        }
        int streak = lowConfStreak.merge(sessionId, 1, Integer::sum);
        int threshold = Math.max(1, props.getAi().getRoute().getLowConfStreakThreshold());
        boolean clarify = streak >= threshold;
        if (clarify) {
            // 已触发澄清，清零避免下一轮被历史计数放大
            lowConfStreak.remove(sessionId);
        }
        return clarify;
    }

    /** 当前会话连续低置信计数（决策日志用） */
    public int currentStreak(String sessionId) {
        return lowConfStreak.getOrDefault(sessionId, 0);
    }

    /** 会话结束可清理，避免 Map 无限增长 */
    public void clearSession(String sessionId) {
        if (sessionId != null) {
            lowConfStreak.remove(sessionId);
        }
    }

    /**
     * Expected Calibration Error（静态评测工具）：把样本按置信度分桶，
     * ECE = Σ_b (|B|/N)·|acc(B) − conf(B)|，越小越校准。供离线评测/单测调用。
     *
     * @param confidences 每条样本的预测置信度（0~1）
     * @param correct     对应样本是否预测正确
     * @param binCount    分桶数（常用 10）
     */
    public static double ece(double[] confidences, boolean[] correct, int binCount) {
        if (confidences == null || correct == null || confidences.length != correct.length
                || confidences.length == 0 || binCount <= 0) {
            return 0.0;
        }
        int n = confidences.length;
        double ece = 0.0;
        for (int b = 0; b < binCount; b++) {
            double lo = (double) b / binCount;
            double hi = (double) (b + 1) / binCount;
            int cnt = 0;
            double confSum = 0.0;
            int correctCnt = 0;
            for (int i = 0; i < n; i++) {
                double c = confidences[i];
                boolean inBin = b == binCount - 1 ? (c >= lo && c <= hi) : (c >= lo && c < hi);
                if (inBin) {
                    cnt++;
                    confSum += c;
                    if (correct[i]) {
                        correctCnt++;
                    }
                }
            }
            if (cnt > 0) {
                double avgConf = confSum / cnt;
                double acc = (double) correctCnt / cnt;
                ece += ((double) cnt / n) * Math.abs(acc - avgConf);
            }
        }
        return ece;
    }

    private record Bucket(double lo, double hi, double acc) {
    }
}
