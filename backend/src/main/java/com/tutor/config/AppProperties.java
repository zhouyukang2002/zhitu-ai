package com.tutor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * tutor.* 应用属性（提示词目录/数据目录/模型选择/路由阈值）。
 */
@Data
@ConfigurationProperties(prefix = "tutor")
public class AppProperties {

    private Observ observ = new Observ();
    private String promptDir = "./config/prompts";
    private String dataDir = "./data";
    private String logDir = "./logs";
    /** 知识库语料目录（MD 语料源：课程/题库/知识条目），摄入管线的数据源 */
    private String corpusDir = "./corpus";
    /** 本机 Elasticsearch 安装根目录（探活看门狗自动拉起用；相对路径默认值便于跨机器部署） */
    private String esHome = "../elasticsearch-8.15.0";

    public String getPromptDir() {
        if (new java.io.File(promptDir).exists()) {
            return promptDir;
        }
        if (new java.io.File("./backend/config/prompts").exists()) {
            return "./backend/config/prompts";
        }
        return promptDir;
    }

    public String getDataDir() {
        if (new java.io.File(dataDir).exists()) {
            return dataDir;
        }
        if (new java.io.File("./backend/data").exists()) {
            return "./backend/data";
        }
        return dataDir;
    }

    public String getCorpusDir() {
        if (new java.io.File(corpusDir).exists()) {
            return corpusDir;
        }
        if (new java.io.File("./corpus").exists()) {
            return "./corpus";
        }
        if (new java.io.File("../corpus").exists()) {
            return "../corpus";
        }
        return corpusDir;
    }

    private Ai ai = new Ai();

    @Data
    public static class Observ {
        /** 成本核算单价（元/百万 token），按提供方实际价格配置 */
        private double inputPricePerMToken = 2.0;
        private double outputPricePerMToken = 8.0;
    }

    @Data
    public static class Ai {
        /** L2 意图识别用轻量模型：成本与延迟在这里拉开差距 */
        private String routeModel = "qwen-turbo";
        /** 主线模型（讲解/批改/闲聊） */
        private String chatModel = "qwen-plus";
        /** 向量模型配置（支持 DashScope/OpenAI/Ollama/硅基流动等任意 OpenAI 兼容的 Embedding 接口） */
        private String embeddingBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String embeddingApiKey = "";
        private String embeddingModel = "text-embedding-v3";
        private int embeddingDimensions = 1024;
        /** Rerank 重排模型配置（OpenAI 兼容 /rerank 端点，如 DashScope gte-rerank-v2；apiKey 留空则跳过重排按原序截取） */
        private String rerankBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String rerankApiKey = "";
        private String rerankModel = "gte-rerank-v2";
        /** 混合检索宽召回候选数（RRF 融合后进入重排的候选池大小） */
        private int recallCandidates = 15;
        /** 重排后精取给生成模型的知识块数量 */
        private int rerankTopN = 4;
        private Route route = new Route();
        /** 舱壁隔离：并发 LLM 操作上限，饱和走降级链 */
        private int bulkheadPermits = 4;
    }

    @Data
    public static class Route {
        /** L1 语义向量路由的置信阈值，低于则进入 L2 */
        private double vectorThreshold = 0.55;
        /** L2 LLM 意图置信阈值，低于则降级（澄清/闲聊兜底） */
        private double llmConfidenceThreshold = 0.4;

        /** L0 规则快路径总开关（强信号零歧义请求免 LLM 直路由） */
        private boolean fastPathEnabled = true;
        /** 是否允许快路径直接短路跳过 LLM；false 时规则只做 LLM 互证不短路（用于 A/B 对比） */
        private boolean fastPathShortcutEnabled = true;
        /** 规则强信号与 LLM 意图一致时的置信度互证增益 */
        private double mutualConfidenceBoost = 0.15;
        /** 低置信迟滞阈值：会话内连续 N 次业务意图低置信且无规则互证才降级澄清；1=与旧行为一致（一次即澄清） */
        private int lowConfStreakThreshold = 1;
        /** 是否启用置信度校准（需配合 calibrationFile 的分桶表，无表时恒等不改变行为） */
        private boolean calibrationEnabled = false;
        /** 置信度校准分桶表路径（离线评测生成：buckets:[{lo,hi,acc}]） */
        private String calibrationFile = "./data/confidence-calibration.json";
    }
}
