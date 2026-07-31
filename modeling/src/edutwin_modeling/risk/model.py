"""Shared risk feature processing, calibration, metrics, and candidate models."""

from __future__ import annotations

import math
import time
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

import numpy as np
from catboost import CatBoostClassifier, Pool
from lightgbm import LGBMClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, brier_score_loss

from edutwin_modeling.errors import DataQualityError, ModelSelectionError

FEATURE_COLUMNS = (
    "vle_total_clicks",
    "vle_interaction_count",
    "vle_active_days",
    "vle_active_weeks",
    "vle_resource_count",
    "assessment_count",
    "assessment_scored_count",
    "assessment_mean_score",
)
FAMILIES = ("LOGISTIC_REGRESSION", "LIGHTGBM", "CATBOOST")


@dataclass(frozen=True)
class RiskPreprocessor:
    feature_names: tuple[str, ...]
    means: np.ndarray
    scales: np.ndarray
    fit_row_count: int

    @classmethod
    def fit(cls, values: np.ndarray, feature_names: Sequence[str]) -> RiskPreprocessor:
        names = tuple(feature_names)
        if names != FEATURE_COLUMNS or values.ndim != 2 or values.shape[1] != len(names):
            raise DataQualityError("risk preprocessor feature contract differs")
        if len(values) < 2 or not np.isfinite(values).all():
            raise DataQualityError("risk preprocessor fit values are invalid")
        means = values.mean(axis=0, dtype="float64")
        scales = values.std(axis=0, dtype="float64")
        scales = np.where(scales > 1e-12, scales, 1.0)
        return cls(names, means, scales, len(values))

    def transform(self, values: np.ndarray) -> np.ndarray:
        if values.ndim != 2 or values.shape[1] != len(self.feature_names):
            raise DataQualityError("risk feature matrix shape differs")
        if not np.isfinite(values).all():
            raise DataQualityError("risk feature matrix contains non-finite values")
        return (values.astype("float64") - self.means) / self.scales

    def vector(self, features: Mapping[str, float]) -> np.ndarray:
        if set(features) != set(self.feature_names):
            missing = sorted(set(self.feature_names).difference(features))
            extra = sorted(set(features).difference(self.feature_names))
            raise DataQualityError(
                f"risk feature contract mismatch: missing={missing}, extra={extra}"
            )
        raw = np.asarray([[float(features[name]) for name in self.feature_names]])
        return self.transform(raw)

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": 1,
            "type": "train-fit-zscore",
            "featureNames": list(self.feature_names),
            "means": self.means.tolist(),
            "scales": self.scales.tolist(),
            "fitRowCount": self.fit_row_count,
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> RiskPreprocessor:
        try:
            if value.get("schemaVersion") != 1 or value.get("type") != "train-fit-zscore":
                raise ValueError("unsupported preprocessor schema")
            result = cls(
                tuple(str(name) for name in value["featureNames"]),
                np.asarray(value["means"], dtype="float64"),
                np.asarray(value["scales"], dtype="float64"),
                int(value["fitRowCount"]),
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise DataQualityError("risk preprocessor artifact is invalid") from exc
        if result.feature_names != FEATURE_COLUMNS or result.fit_row_count < 2:
            raise DataQualityError("risk preprocessor artifact is invalid")
        if result.means.shape != (len(FEATURE_COLUMNS),) or result.scales.shape != (
            len(FEATURE_COLUMNS),
        ):
            raise DataQualityError("risk preprocessor artifact shape is invalid")
        if (
            not np.isfinite(result.means).all()
            or not np.isfinite(result.scales).all()
            or (result.scales <= 0.0).any()
        ):
            raise DataQualityError("risk preprocessor artifact values are invalid")
        return result


@dataclass(frozen=True)
class RiskCalibrator:
    coefficient: float
    intercept: float
    fit_row_count: int
    seed: int

    @staticmethod
    def _logits(probabilities: np.ndarray) -> np.ndarray:
        clipped = np.clip(probabilities.astype("float64"), 1e-7, 1.0 - 1e-7)
        return np.log(clipped / (1.0 - clipped))

    @classmethod
    def fit(
        cls, labels: np.ndarray, probabilities: np.ndarray, *, seed: int = 42
    ) -> RiskCalibrator:
        if seed != 42 or labels.shape != probabilities.shape or labels.ndim != 1:
            raise DataQualityError("risk calibration arrays or seed are invalid")
        if (
            len(labels) < 2
            or not np.isfinite(labels).all()
            or not np.isfinite(probabilities).all()
            or (probabilities < 0.0).any()
            or (probabilities > 1.0).any()
            or set(np.unique(labels)) != {0, 1}
        ):
            raise DataQualityError("risk calibration requires both binary classes")
        estimator = LogisticRegression(
            C=1_000_000.0,
            solver="lbfgs",
            max_iter=1_000,
            random_state=seed,
        )
        estimator.fit(cls._logits(probabilities).reshape(-1, 1), labels)
        result = cls(
            coefficient=float(estimator.coef_[0, 0]),
            intercept=float(estimator.intercept_[0]),
            fit_row_count=len(labels),
            seed=seed,
        )
        if result.coefficient <= 0.0:
            raise DataQualityError("risk calibration must preserve score direction")
        return result

    def transform(self, probabilities: np.ndarray) -> np.ndarray:
        if (
            probabilities.ndim != 1
            or len(probabilities) < 1
            or not np.isfinite(probabilities).all()
            or (probabilities < 0.0).any()
            or (probabilities > 1.0).any()
        ):
            raise DataQualityError("risk calibration probabilities are invalid")
        scores = self.coefficient * self._logits(probabilities) + self.intercept
        positive = scores >= 0.0
        result = np.empty_like(scores, dtype="float64")
        result[positive] = 1.0 / (1.0 + np.exp(-scores[positive]))
        negative_exp = np.exp(scores[~positive])
        result[~positive] = negative_exp / (1.0 + negative_exp)
        return np.clip(result, 1e-7, 1.0 - 1e-7)

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": 1,
            "type": "platt-logit",
            "fitSplit": "train-calibration",
            "fitRowCount": self.fit_row_count,
            "seed": self.seed,
            "coefficient": self.coefficient,
            "intercept": self.intercept,
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> RiskCalibrator:
        try:
            if (
                value.get("schemaVersion") != 1
                or value.get("type") != "platt-logit"
                or value.get("fitSplit") != "train-calibration"
            ):
                raise ValueError("unsupported calibrator schema")
            result = cls(
                float(value["coefficient"]),
                float(value["intercept"]),
                int(value["fitRowCount"]),
                int(value["seed"]),
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise DataQualityError("risk calibrator artifact is invalid") from exc
        if (
            result.seed != 42
            or result.fit_row_count < 2
            or result.coefficient <= 0.0
            or not np.isfinite([result.coefficient, result.intercept]).all()
        ):
            raise DataQualityError("risk calibrator artifact is invalid")
        return result


@dataclass(frozen=True)
class RiskMetrics:
    pr_auc: float
    brier_score: float
    cpu_p95_ms: float
    row_count: int

    def validate(self) -> None:
        numeric = (self.pr_auc, self.brier_score, self.cpu_p95_ms)
        if not np.isfinite(numeric).all() or self.row_count < 1:
            raise DataQualityError("risk metrics are invalid")
        if not 0.0 <= self.pr_auc <= 1.0 or not 0.0 <= self.brier_score <= 1.0:
            raise DataQualityError("risk probability metrics are outside [0, 1]")
        if self.cpu_p95_ms < 0.0:
            raise DataQualityError("risk latency is negative")

    def to_dict(self) -> dict[str, Any]:
        return {
            "PR_AUC": self.pr_auc,
            "BRIER_SCORE": self.brier_score,
            "CPU_P95_MS": self.cpu_p95_ms,
            "rowCount": self.row_count,
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> RiskMetrics:
        try:
            result = cls(
                pr_auc=float(value["PR_AUC"]),
                brier_score=float(value["BRIER_SCORE"]),
                cpu_p95_ms=float(value["CPU_P95_MS"]),
                row_count=int(value["rowCount"]),
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise DataQualityError("risk metrics artifact is invalid") from exc
        result.validate()
        return result


@dataclass(frozen=True)
class RiskCandidate:
    family: str
    estimator: Any

    def validate(self) -> None:
        expected_types = {
            "LOGISTIC_REGRESSION": LogisticRegression,
            "LIGHTGBM": LGBMClassifier,
            "CATBOOST": CatBoostClassifier,
        }
        expected = expected_types.get(self.family)
        if expected is None or not isinstance(self.estimator, expected):
            raise DataQualityError("risk candidate family and estimator type differ")
        classes = np.asarray(getattr(self.estimator, "classes_", ()))
        if classes.shape != (2,) or not np.array_equal(classes, np.asarray([0, 1])):
            raise DataQualityError("risk candidate class order differs")
        if self.family == "CATBOOST":
            feature_count = len(
                np.asarray(self.estimator.feature_importances_, dtype="float64")
            )
        else:
            feature_count = int(getattr(self.estimator, "n_features_in_", -1))
        if feature_count != len(FEATURE_COLUMNS):
            raise DataQualityError("risk candidate feature count differs")

    def raw_probabilities(self, values: np.ndarray) -> np.ndarray:
        self.validate()
        if (
            values.ndim != 2
            or values.shape[1] != len(FEATURE_COLUMNS)
            or not np.isfinite(values).all()
        ):
            raise DataQualityError("risk candidate feature matrix is invalid")
        if self.family == "LIGHTGBM":
            positive = np.asarray(
                self.estimator.booster_.predict(values), dtype="float64"
            )
            result = np.column_stack((1.0 - positive, positive))
        else:
            result = np.asarray(self.estimator.predict_proba(values), dtype="float64")
        if result.shape != (len(values), 2) or not np.isfinite(result).all():
            raise DataQualityError(f"risk candidate {self.family} returned invalid probabilities")
        return np.clip(result[:, 1], 1e-7, 1.0 - 1e-7)

    def shap_values(self, values: np.ndarray) -> tuple[float, np.ndarray]:
        self.validate()
        if values.shape != (1, len(FEATURE_COLUMNS)) or not np.isfinite(values).all():
            raise DataQualityError("risk SHAP requires one complete feature row")
        if self.family == "LOGISTIC_REGRESSION":
            coefficients = np.asarray(self.estimator.coef_[0], dtype="float64")
            base = float(self.estimator.intercept_[0])
            contributions = coefficients * values[0]
            raw_output = float(self.estimator.decision_function(values)[0])
        elif self.family == "LIGHTGBM":
            contributions = np.asarray(
                self.estimator.booster_.predict(values, pred_contrib=True),
                dtype="float64",
            )[0]
            base = float(contributions[-1])
            contributions = contributions[:-1]
            raw_output = float(self.estimator.booster_.predict(values, raw_score=True)[0])
        elif self.family == "CATBOOST":
            pool = Pool(values)
            contributions = np.asarray(
                self.estimator.get_feature_importance(
                    pool, type="ShapValues"
                ),
                dtype="float64",
            )[0]
            base = float(contributions[-1])
            contributions = contributions[:-1]
            raw_output = float(
                np.asarray(
                    self.estimator.predict(pool, prediction_type="RawFormulaVal"),
                    dtype="float64",
                )[0]
            )
        else:  # validate() already rejects this branch.
            raise DataQualityError(f"unsupported risk candidate family: {self.family}")
        if (
            contributions.shape != (len(FEATURE_COLUMNS),)
            or not np.isfinite(contributions).all()
            or not np.isfinite([base, raw_output]).all()
            or not np.isclose(base + contributions.sum(), raw_output, rtol=1e-6, atol=1e-7)
        ):
            raise DataQualityError("risk SHAP output is non-additive or invalid")
        return base, contributions


def build_candidate(family: str, config: Mapping[str, Any], *, seed: int) -> RiskCandidate:
    if seed != 42 or family not in FAMILIES:
        raise DataQualityError("risk family or seed is invalid")
    if family == "LOGISTIC_REGRESSION":
        estimator = LogisticRegression(
            C=float(config.get("c", 1.0)),
            max_iter=int(config.get("max_iter", 2_000)),
            class_weight=str(config.get("class_weight", "balanced")),
            random_state=seed,
            solver="lbfgs",
        )
    elif family == "LIGHTGBM":
        estimator = LGBMClassifier(
            n_estimators=int(config.get("n_estimators", 300)),
            learning_rate=float(config.get("learning_rate", 0.03)),
            num_leaves=int(config.get("num_leaves", 31)),
            max_depth=int(config.get("max_depth", -1)),
            min_child_samples=int(config.get("min_child_samples", 20)),
            subsample=float(config.get("subsample", 1.0)),
            colsample_bytree=float(config.get("colsample_bytree", 1.0)),
            reg_lambda=float(config.get("reg_lambda", 0.1)),
            random_state=seed,
            deterministic=True,
            force_col_wise=True,
            n_jobs=2,
            verbosity=-1,
        )
    else:
        estimator = CatBoostClassifier(
            iterations=int(config.get("iterations", 300)),
            learning_rate=float(config.get("learning_rate", 0.03)),
            depth=int(config.get("depth", 6)),
            l2_leaf_reg=float(config.get("l2_leaf_reg", 3.0)),
            loss_function=str(config.get("loss_function", "Logloss")),
            random_seed=seed,
            thread_count=2,
            verbose=False,
            allow_writing_files=False,
        )
    return RiskCandidate(family, estimator)


def evaluate_candidate(
    candidate: RiskCandidate,
    calibrator: RiskCalibrator,
    values: np.ndarray,
    labels: np.ndarray,
    *,
    latency_sample_size: int,
    warmup_repeats: int,
    timed_repeats: int,
) -> RiskMetrics:
    calibrated = calibrator.transform(candidate.raw_probabilities(values))
    if set(np.unique(labels)) != {0, 1}:
        raise ModelSelectionError("risk evaluation split must contain both classes")
    sample = values[: min(latency_sample_size, len(values))]
    for _ in range(warmup_repeats):
        for row in sample:
            calibrator.transform(candidate.raw_probabilities(row.reshape(1, -1)))
    latencies: list[float] = []
    for _ in range(timed_repeats):
        for row in sample:
            started = time.perf_counter_ns()
            calibrator.transform(candidate.raw_probabilities(row.reshape(1, -1)))
            latencies.append((time.perf_counter_ns() - started) / 1_000_000.0)
    result = RiskMetrics(
        pr_auc=float(average_precision_score(labels, calibrated)),
        brier_score=float(brier_score_loss(labels, calibrated)),
        cpu_p95_ms=float(np.percentile(np.asarray(latencies), 95, method="linear")),
        row_count=len(labels),
    )
    result.validate()
    return result


def select_candidate(
    metrics: Mapping[str, RiskMetrics], *, tolerance: float = 0.005
) -> dict[str, Any]:
    if tolerance != 0.005 or set(metrics) != set(FAMILIES):
        raise ModelSelectionError("risk selection contract differs")
    for value in metrics.values():
        value.validate()
    best = max(value.pr_auc for value in metrics.values())
    contenders = {
        family: value
        for family, value in metrics.items()
        if best - value.pr_auc < tolerance
        or math.isclose(
            best - value.pr_auc,
            tolerance,
            rel_tol=0.0,
            abs_tol=1e-12,
        )
    }
    winner = min(
        contenders,
        key=lambda family: (
            contenders[family].brier_score,
            contenders[family].cpu_p95_ms,
            family,
        ),
    )
    return {
        "winner": winner,
        "primaryMetric": "PR_AUC",
        "primaryValue": metrics[winner].pr_auc,
        "prAucTolerance": tolerance,
        "contenders": sorted(contenders),
        "tieBreakOrder": ["BRIER_SCORE", "CPU_P95_MS", "FAMILY_NAME"],
    }
