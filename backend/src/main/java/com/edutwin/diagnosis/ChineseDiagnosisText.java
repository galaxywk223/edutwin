package com.edutwin.diagnosis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

final class ChineseDiagnosisText {

    private static final Map<String, String> RISK_LABELS = Map.of(
            "LOW", "低风险",
            "MEDIUM", "中风险",
            "HIGH", "高风险");

    private static final Map<String, String> FEATURE_LABELS = Map.of(
            "vle_total_clicks", "学习平台交互总量",
            "vle_interaction_count", "学习平台交互次数",
            "vle_active_days", "活跃学习天数",
            "vle_active_weeks", "活跃学习周数",
            "vle_resource_count", "学习资源使用数",
            "assessment_count", "考核次数",
            "assessment_scored_count", "已评分考核次数",
            "assessment_mean_score", "考核平均成绩");

    private ChineseDiagnosisText() {}

    static boolean containsChinese(String value) {
        if (value == null || value.isBlank()) return false;
        return value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    static boolean allChinese(List<String> values) {
        return values != null && !values.isEmpty() && values.stream().allMatch(ChineseDiagnosisText::containsChinese);
    }

    static String riskLabel(String value) {
        return RISK_LABELS.getOrDefault(value, "未知风险");
    }

    static String featureLabel(String value) {
        return FEATURE_LABELS.getOrDefault(value, "其他学习行为指标");
    }

    static String directionLabel(String value) {
        return "INCREASES_RISK".equals(value) ? "增加风险" : "DECREASES_RISK".equals(value) ? "降低风险" : "影响风险";
    }

    static String percent(BigDecimal value) {
        return value.multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString() + "%";
    }
}
