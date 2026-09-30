package com.tutor.router;

import com.tutor.config.AppProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 低置信迟滞（hysteresis）：连续 N 次低置信且无规则互证才澄清，恢复高置信立即清零。
 * 阈值默认 1（与旧行为一致），测试用阈值 3 验证计数语义。
 */
class ConfidenceCalibratorTest {

    private AppProperties props;
    private ConfidenceCalibrator calibrator;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getAi().getRoute().setLowConfStreakThreshold(3);
        calibrator = new ConfidenceCalibrator(props);
        // 不加载校准表文件（data/confidence-calibration.json 缺失时恒等），只测迟滞
        calibrator.reload();
    }

    @Test
    void calibrate_无校准表时恒等返回() {
        Assertions.assertEquals(0.55, calibrator.calibrate(0.55), 1e-9);
        Assertions.assertEquals(0.9, calibrator.calibrate(0.9), 1e-9);
    }

    @Test
    void shouldClarify_连续达到阈值才触发() {
        Assertions.assertFalse(calibrator.shouldClarify("s1", true, false), "第1次不应触发");
        Assertions.assertFalse(calibrator.shouldClarify("s1", true, false), "第2次不应触发");
        Assertions.assertTrue(calibrator.shouldClarify("s1", true, false), "第3次（阈值=3）应触发");
    }

    @Test
    void shouldClarify_触发后计数清零需重新累计() {
        calibrator.shouldClarify("s2", true, false);
        calibrator.shouldClarify("s2", true, false);
        Assertions.assertTrue(calibrator.shouldClarify("s2", true, false));
        Assertions.assertEquals(0, calibrator.currentStreak("s2"), "触发后应清零");
        Assertions.assertFalse(calibrator.shouldClarify("s2", true, false), "清零后重新累计");
    }

    @Test
    void shouldClarify_规则互证立即清零且不澄清() {
        calibrator.shouldClarify("s3", true, false);
        calibrator.shouldClarify("s3", true, false);
        Assertions.assertFalse(calibrator.shouldClarify("s3", true, true), "规则互证时即使低置信也不澄清");
        Assertions.assertEquals(0, calibrator.currentStreak("s3"));
    }

    @Test
    void shouldClarify_恢复高置信清零() {
        calibrator.shouldClarify("s4", true, false);
        calibrator.shouldClarify("s4", true, false);
        Assertions.assertFalse(calibrator.shouldClarify("s4", false, false), "高置信放行");
        Assertions.assertEquals(0, calibrator.currentStreak("s4"), "高置信应清零连败计数");
    }

    @Test
    void shouldClarify_不同会话计数隔离() {
        calibrator.shouldClarify("a", true, false);
        calibrator.shouldClarify("a", true, false);
        Assertions.assertFalse(calibrator.shouldClarify("b", true, false), "b 会话独立计数");
        Assertions.assertTrue(calibrator.shouldClarify("a", true, false));
    }
}
