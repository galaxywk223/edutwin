from edutwin_modeling.data.bootstrap import _diagnosis


def test_bootstrap_diagnosis_uses_chinese_display_text() -> None:
    diagnosis = _diagnosis(
        risk_band="LOW",
        probability=0.11146623,
        weakest_skill_name="线性代数",
        evidence=[{"featureName": "assessment_mean_score"}],
        fallback_version="template-diagnosis-v1",
    )

    assert diagnosis["summary"] == "初始校准风险为低风险。风险概率为11.15%。"
    assert diagnosis["strengths"] == ["当前阶段已形成可追溯的答题与学习活动记录。"]
    assert diagnosis["concerns"] == ["当前影响最大的模型指标为“考核平均成绩”。"]
    assert diagnosis["recommendedActions"] == ["完成“线性代数”相关的规则生成练习任务。"]


def test_bootstrap_diagnosis_hides_unknown_internal_identifiers() -> None:
    diagnosis = _diagnosis(
        risk_band="UNRECOGNIZED",
        probability=0.5,
        weakest_skill_name="当前知识点",
        evidence=[{"featureName": "unknown_feature"}],
        fallback_version="template-diagnosis-v1",
    )

    assert "未知风险" in diagnosis["summary"]
    assert "其他学习行为指标" in diagnosis["concerns"][0]
    assert "unknown_feature" not in diagnosis["concerns"][0]
