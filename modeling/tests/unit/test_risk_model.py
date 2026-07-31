from __future__ import annotations

from pathlib import Path

import joblib
import numpy as np
import pytest
from catboost import CatBoostClassifier
from sklearn.linear_model import LogisticRegression

from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.risk.model import (
    FEATURE_COLUMNS,
    RiskCalibrator,
    RiskCandidate,
    RiskMetrics,
    RiskPreprocessor,
    select_candidate,
)
from edutwin_modeling.risk.runtime import FrozenRiskRuntime


def _feature_matrix() -> np.ndarray:
    return np.asarray(
        [
            [1, 1, 1, 1, 1, 1, 1, 20],
            [2, 2, 1, 1, 2, 2, 2, 30],
            [5, 5, 3, 1, 4, 5, 5, 70],
            [8, 8, 5, 2, 6, 8, 8, 90],
        ],
        dtype="float64",
    )


def test_preprocessor_enforces_exact_ordered_feature_contract() -> None:
    preprocessor = RiskPreprocessor.fit(_feature_matrix(), FEATURE_COLUMNS)
    features = {
        name: float(value)
        for name, value in zip(FEATURE_COLUMNS, _feature_matrix()[0], strict=True)
    }

    transformed = preprocessor.vector(features)

    assert transformed.shape == (1, len(FEATURE_COLUMNS))
    with pytest.raises(DataQualityError, match="feature contract mismatch"):
        preprocessor.vector({**features, "final_result": 1.0})


def test_platt_calibrator_is_train_side_and_probability_bounded() -> None:
    labels = np.asarray([0, 0, 1, 1], dtype="int8")
    raw = np.asarray([0.1, 0.4, 0.6, 0.9], dtype="float64")

    calibrator = RiskCalibrator.fit(labels, raw, seed=42)
    transformed = calibrator.transform(raw)

    assert calibrator.fit_row_count == 4
    assert calibrator.seed == 42
    assert np.all((transformed > 0.0) & (transformed < 1.0))
    assert np.all(np.diff(transformed) > 0.0)


def test_selection_uses_pr_auc_tolerance_then_brier_and_latency() -> None:
    metrics = {
        "LOGISTIC_REGRESSION": RiskMetrics(0.800, 0.18, 0.2, 100),
        "LIGHTGBM": RiskMetrics(0.797, 0.16, 0.4, 100),
        "CATBOOST": RiskMetrics(0.790, 0.14, 0.1, 100),
    }

    decision = select_candidate(metrics, tolerance=0.005)

    assert decision["winner"] == "LIGHTGBM"
    assert decision["contenders"] == ["LIGHTGBM", "LOGISTIC_REGRESSION"]


def test_selection_includes_exact_pr_auc_tolerance_boundary() -> None:
    metrics = {
        "LOGISTIC_REGRESSION": RiskMetrics(0.800, 0.20, 0.2, 100),
        "LIGHTGBM": RiskMetrics(0.795, 0.15, 0.4, 100),
        "CATBOOST": RiskMetrics(0.790, 0.10, 0.1, 100),
    }

    decision = select_candidate(metrics, tolerance=0.005)

    assert decision["winner"] == "LIGHTGBM"
    assert decision["contenders"] == ["LIGHTGBM", "LOGISTIC_REGRESSION"]


def test_logistic_shap_factors_are_model_computed_and_additive() -> None:
    preprocessor = RiskPreprocessor.fit(_feature_matrix(), FEATURE_COLUMNS)
    transformed = preprocessor.transform(_feature_matrix())
    labels = np.asarray([1, 1, 0, 0], dtype="int8")
    estimator = LogisticRegression(random_state=42).fit(transformed, labels)
    candidate = RiskCandidate("LOGISTIC_REGRESSION", estimator)
    calibrator = RiskCalibrator.fit(
        labels,
        candidate.raw_probabilities(transformed),
        seed=42,
    )
    runtime = FrozenRiskRuntime(
        preprocessor=preprocessor,
        candidate=candidate,
        calibrator=calibrator,
        risk_reference={"modelVersion": "risk-logistic-test"},
        explanation_reference={"modelVersion": "shap-risk-logistic-test"},
        feature_contract_version="oulad-d0-29-v1",
        medium_threshold=0.35,
        high_threshold=0.65,
    )
    features = {
        name: float(value)
        for name, value in zip(FEATURE_COLUMNS, _feature_matrix()[0], strict=True)
    }

    base_value, output_value, factors = runtime.explain(features)
    scaled = preprocessor.vector(features)
    expected_output = float(estimator.decision_function(scaled)[0])

    assert output_value == pytest.approx(expected_output)
    assert len(factors) == 5
    assert [factor["rank"] for factor in factors] == [1, 2, 3, 4, 5]
    assert len({factor["featureName"] for factor in factors}) == 5
    assert all(factor["baseValue"] == pytest.approx(base_value) for factor in factors)


def test_catboost_feature_contract_survives_joblib_round_trip(tmp_path: Path) -> None:
    values = _feature_matrix()
    labels = np.asarray([0, 0, 1, 1], dtype="int8")
    estimator = CatBoostClassifier(
        iterations=2,
        depth=2,
        random_seed=42,
        verbose=False,
        allow_writing_files=False,
    ).fit(values, labels)
    path = tmp_path / "catboost.joblib"
    joblib.dump(RiskCandidate("CATBOOST", estimator), path, protocol=5)

    loaded = joblib.load(path)

    assert isinstance(loaded, RiskCandidate)
    loaded.validate()
    assert loaded.raw_probabilities(values).shape == (len(values),)
