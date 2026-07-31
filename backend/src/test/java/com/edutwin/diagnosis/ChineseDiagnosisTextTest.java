package com.edutwin.diagnosis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChineseDiagnosisTextTest {

    @Test
    void recognizesChineseNarrativeContent() {
        assertTrue(ChineseDiagnosisText.containsChinese("当前风险为低风险。"));
        assertTrue(ChineseDiagnosisText.allChinese(List.of("学习记录完整。", "继续完成练习。")));
        assertFalse(ChineseDiagnosisText.containsChinese("Current risk is low."));
        assertFalse(ChineseDiagnosisText.allChinese(List.of("学习记录完整。", "Complete the task.")));
    }

    @Test
    void mapsRiskFeaturesAndProbabilityToChinese() {
        assertEquals("低风险", ChineseDiagnosisText.riskLabel("LOW"));
        assertEquals("考核平均成绩", ChineseDiagnosisText.featureLabel("assessment_mean_score"));
        assertEquals("其他学习行为指标", ChineseDiagnosisText.featureLabel("unknown_feature"));
        assertEquals("增加风险", ChineseDiagnosisText.directionLabel("INCREASES_RISK"));
        assertEquals("11.15%", ChineseDiagnosisText.percent(new BigDecimal("0.11146623")));
    }
}
