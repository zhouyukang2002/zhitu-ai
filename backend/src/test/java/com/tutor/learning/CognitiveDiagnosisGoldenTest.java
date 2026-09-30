package com.tutor.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.learning.entity.LearningRecordEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 认知诊断模型金标准测试：直接以 scripts/eval-data/diagnosis-golden-40.json 为数据源，
 * 与在线评测脚本 scripts/diagnosis-eval.mjs 同源同口径（weakest 命中 / 排序 / 分数区间）。
 * 金标准期望值已按模型口径（accuracy×70 + 效率修正×30，效率=clamp(60000/avgMs, 0.6, 1.0)）预先算好。
 */
class CognitiveDiagnosisGoldenTest {

    private final CognitiveDiagnosis diagnosis = new CognitiveDiagnosis();
    private final ObjectMapper mapper = new ObjectMapper();

    private static final File GOLDEN = resolveGoldenFile();

    private static File resolveGoldenFile() {
        File f = new File("scripts/eval-data/diagnosis-golden-40.json");
        if (f.exists()) return f;
        f = new File("backend/scripts/eval-data/diagnosis-golden-40.json");
        if (f.exists()) return f;
        return new File("scripts/eval-data/diagnosis-golden-40.json");
    }

    private List<LearningRecordEntity> buildRecords(JsonNode records) {
        List<LearningRecordEntity> list = new ArrayList<>();
        for (JsonNode r : records) {
            String kp = r.get("kp").asText();
            int n = r.get("n").asInt();
            double acc = r.get("acc").asDouble();
            long avgMs = r.get("avgMs").asLong();
            int nCorrect = (int) Math.round(n * acc);
            for (int i = 0; i < n; i++) {
                LearningRecordEntity e = new LearningRecordEntity();
                e.setKnowledgePoint(kp);
                e.setCorrect(i < nCorrect);
                e.setTimeMs((int) avgMs);
                list.add(e);
            }
        }
        return list;
    }

    @Test
    void golden40_allGroups_pass() throws Exception {
        Assertions.assertTrue(GOLDEN.exists(), "金标准文件缺失: " + GOLDEN.getPath());
        JsonNode groups = mapper.readTree(GOLDEN).get("groups");

        int pass = 0;
        List<String> failures = new ArrayList<>();
        for (JsonNode g : groups) {
            String id = g.get("id").asText();
            JsonNode expect = g.get("expect");
            List<String> expectedOrder = new ArrayList<>();
            expect.get("order").forEach(n -> expectedOrder.add(n.asText()));
            boolean ties = expect.path("ties").asBoolean(false);

            List<LearningRecordEntity> records = buildRecords(g.get("records"));
            CognitiveDiagnosis.Result result = diagnosis.diagnose(records);
            List<String> gotOrder = result.weakPoints().stream()
                    .map(CognitiveDiagnosis.WeakPoint::knowledgePoint).toList();

            List<String> checks = new ArrayList<>();
            if (gotOrder.isEmpty()) {
                checks.add("结果为空");
            } else {
                boolean weakestOk = ties
                        ? expectedOrder.contains(gotOrder.get(0))
                        : gotOrder.get(0).equals(expect.get("weakest").asText());
                if (!weakestOk) checks.add("weakest=" + gotOrder.get(0));
                List<String> gotSorted = new ArrayList<>(gotOrder);
                List<String> expSorted = new ArrayList<>(expectedOrder);
                gotSorted.sort(String::compareTo);
                expSorted.sort(String::compareTo);
                if (!gotSorted.equals(expSorted)) {
                    checks.add("kp集合不符 got=" + gotOrder);
                } else if (!ties && !gotOrder.equals(expectedOrder)) {
                    checks.add("排序不符 got=" + gotOrder);
                }
                // 分数区间合理性：低掌握≤60，高掌握(不太慢)≥70；同 kp 多段记录先合并（与在线评测脚本同口径）
                Map<String, double[]> merged = new java.util.LinkedHashMap<>();
                for (JsonNode r : g.get("records")) {
                    String kp = r.get("kp").asText();
                    double n = r.get("n").asDouble(), acc = r.get("acc").asDouble(), ms = r.get("avgMs").asDouble();
                    double[] m = merged.computeIfAbsent(kp, k -> new double[3]);
                    m[0] += n; m[1] += acc * n; m[2] += ms * n;
                }
                for (CognitiveDiagnosis.WeakPoint w : result.weakPoints()) {
                    double[] m = merged.get(w.knowledgePoint());
                    if (m == null) continue;
                    double acc = m[1] / m[0], avgMs = m[2] / m[0];
                    if (acc <= 0.4 && w.score() > 60) checks.add(w.knowledgePoint() + " 低掌握但 score=" + w.score());
                    if (acc >= 0.9 && avgMs <= 20000 && w.score() < 70)
                        checks.add(w.knowledgePoint() + " 高掌握但 score=" + w.score());
                }
            }
            if (checks.isEmpty()) pass++;
            else failures.add(id + ": " + String.join("; ", checks));
        }
        Assertions.assertEquals(groups.size(), pass,
                "金标准未全通过，失败组: " + String.join(" | ", failures));
    }
}
