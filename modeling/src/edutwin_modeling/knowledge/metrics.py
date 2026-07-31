"""Unified calibration, event-level metrics, and frozen model selection."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import log_loss, roc_auc_score

from edutwin_modeling.errors import DataQualityError, ModelSelectionError

_METRIC_COMPARISON_DECIMALS = 12


@dataclass(frozen=True)
class PredictionResult:
    """Raw event-level predictions in the shared deterministic event order."""

    event_keys: tuple[str, ...]
    labels: np.ndarray
    probabilities: np.ndarray
    latency_ms: np.ndarray

    def validate(self) -> None:
        size = len(self.event_keys)
        if self.labels.shape != (size,) or self.probabilities.shape != (size,):
            raise DataQualityError("knowledge prediction arrays do not match event keys")
        if (
            self.latency_ms.ndim != 1
            or len(self.latency_ms) < 1
            or len(self.latency_ms) > size
        ):
            raise DataQualityError("knowledge prediction latency sample is invalid")
        if len(set(self.event_keys)) != size:
            raise DataQualityError("knowledge prediction event keys are not unique")
        if not np.isin(self.labels, [0, 1]).all():
            raise DataQualityError("knowledge prediction labels are not binary")
        if not np.isfinite(self.probabilities).all() or (
            (self.probabilities < 0.0) | (self.probabilities > 1.0)
        ).any():
            raise DataQualityError("knowledge predictions are outside [0, 1]")
        if not np.isfinite(self.latency_ms).all() or (self.latency_ms < 0.0).any():
            raise DataQualityError("knowledge prediction latency is invalid")


@dataclass(frozen=True)
class PlattCalibrator:
    """One-dimensional sigmoid calibrator fitted on train-side students only."""

    coefficient: float
    intercept: float
    fit_event_count: int
    seed: int

    def validate(self) -> None:
        if self.seed != 42 or self.fit_event_count < 2:
            raise DataQualityError("knowledge calibrator metadata is invalid")
        if not np.isfinite((self.coefficient, self.intercept)).all():
            raise DataQualityError("knowledge calibrator parameters are non-finite")

    @staticmethod
    def _logits(probabilities: np.ndarray) -> np.ndarray:
        clipped = np.clip(probabilities.astype("float64"), 1e-7, 1.0 - 1e-7)
        return np.log(clipped / (1.0 - clipped))

    @classmethod
    def fit(
        cls,
        labels: np.ndarray,
        probabilities: np.ndarray,
        *,
        seed: int = 42,
    ) -> PlattCalibrator:
        if seed != 42:
            raise DataQualityError("knowledge calibrator seed must remain 42")
        if labels.shape != probabilities.shape or labels.ndim != 1:
            raise DataQualityError("knowledge calibration arrays must be aligned vectors")
        if len(labels) < 2 or len(np.unique(labels)) != 2:
            raise DataQualityError("knowledge calibration requires both binary classes")
        if not np.isin(labels, [0, 1]).all():
            raise DataQualityError("knowledge calibration labels must be binary")
        if not np.isfinite(probabilities).all() or (
            (probabilities < 0.0) | (probabilities > 1.0)
        ).any():
            raise DataQualityError("knowledge calibration probabilities are invalid")
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
            fit_event_count=len(labels),
            seed=seed,
        )
        result.validate()
        return result

    def transform(self, probabilities: np.ndarray) -> np.ndarray:
        self.validate()
        if probabilities.ndim != 1 or not np.isfinite(probabilities).all() or (
            (probabilities < 0.0) | (probabilities > 1.0)
        ).any():
            raise DataQualityError("knowledge calibration input probabilities are invalid")
        scores = self.coefficient * self._logits(probabilities) + self.intercept
        calibrated = np.empty_like(scores, dtype="float64")
        positive = scores >= 0.0
        calibrated[positive] = 1.0 / (1.0 + np.exp(-scores[positive]))
        negative_exp = np.exp(scores[~positive])
        calibrated[~positive] = negative_exp / (1.0 + negative_exp)
        return np.clip(calibrated, 1e-7, 1.0 - 1e-7)

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": 1,
            "type": "platt-logit",
            "fitSplit": "train-calibration",
            "fitEventCount": self.fit_event_count,
            "seed": self.seed,
            "coefficient": self.coefficient,
            "intercept": self.intercept,
        }

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> PlattCalibrator:
        if value.get("type") != "platt-logit":
            raise DataQualityError("knowledge calibrator type is unsupported")
        result = cls(
            coefficient=float(value["coefficient"]),
            intercept=float(value["intercept"]),
            fit_event_count=int(value["fitEventCount"]),
            seed=int(value["seed"]),
        )
        result.validate()
        return result


def expected_calibration_error(
    labels: np.ndarray, probabilities: np.ndarray, *, bins: int = 15
) -> float:
    """Calculate equal-width expected calibration error with exactly 15 bins."""

    if bins != 15:
        raise DataQualityError("knowledge ECE bin count must remain 15")
    if labels.shape != probabilities.shape or labels.ndim != 1 or len(labels) == 0:
        raise DataQualityError("knowledge ECE arrays must be aligned non-empty vectors")
    if not np.isin(labels, [0, 1]).all():
        raise DataQualityError("knowledge ECE labels must be binary")
    if not np.isfinite(probabilities).all() or (
        (probabilities < 0.0) | (probabilities > 1.0)
    ).any():
        raise DataQualityError("knowledge ECE probabilities are invalid")
    edges = np.linspace(0.0, 1.0, bins + 1)
    assignments = np.minimum(np.searchsorted(edges, probabilities, side="right") - 1, bins - 1)
    assignments = np.maximum(assignments, 0)
    error = 0.0
    for bin_index in range(bins):
        mask = assignments == bin_index
        if not mask.any():
            continue
        confidence = float(probabilities[mask].mean())
        accuracy = float(labels[mask].mean())
        error += float(mask.mean()) * abs(accuracy - confidence)
    return float(error)


@dataclass(frozen=True)
class CandidateMetrics:
    """Frozen unified metrics for one knowledge candidate and one split."""

    auc: float
    log_loss: float
    ece_15: float
    cpu_p95_ms: float
    event_count: int
    latency_sample_count: int

    def validate(self) -> None:
        numeric = (self.auc, self.log_loss, self.ece_15, self.cpu_p95_ms)
        if (
            not np.isfinite(numeric).all()
            or self.event_count < 1
            or not 1 <= self.latency_sample_count <= self.event_count
        ):
            raise DataQualityError("knowledge candidate metrics are invalid")
        if not 0.0 <= self.auc <= 1.0:
            raise DataQualityError("knowledge candidate AUC is outside [0, 1]")
        if self.log_loss < 0.0 or not 0.0 <= self.ece_15 <= 1.0:
            raise DataQualityError("knowledge candidate loss or ECE is invalid")
        if self.cpu_p95_ms < 0.0:
            raise DataQualityError("knowledge candidate CPU latency is invalid")

    def to_dict(self) -> dict[str, Any]:
        return {
            "AUC": self.auc,
            "LOG_LOSS": self.log_loss,
            "ECE_15": self.ece_15,
            "CPU_P95_MS": self.cpu_p95_ms,
            "eventCount": self.event_count,
            "latencySampleCount": self.latency_sample_count,
        }

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> CandidateMetrics:
        result = cls(
            auc=float(value["AUC"]),
            log_loss=float(value["LOG_LOSS"]),
            ece_15=float(value["ECE_15"]),
            cpu_p95_ms=float(value["CPU_P95_MS"]),
            event_count=int(value["eventCount"]),
            latency_sample_count=int(
                value.get("latencySampleCount", value["eventCount"])
            ),
        )
        result.validate()
        return result


def evaluate_predictions(
    prediction: PredictionResult,
    calibrator: PlattCalibrator,
    *,
    ece_bins: int = 15,
) -> tuple[CandidateMetrics, np.ndarray]:
    """Apply the train-side calibrator and compute the common event-level metrics."""

    prediction.validate()
    if len(np.unique(prediction.labels)) != 2:
        raise ModelSelectionError("knowledge evaluation split must contain both classes")
    calibrated = calibrator.transform(prediction.probabilities)
    metrics = CandidateMetrics(
        auc=float(roc_auc_score(prediction.labels, calibrated)),
        log_loss=float(log_loss(prediction.labels, calibrated, labels=[0, 1])),
        ece_15=expected_calibration_error(
            prediction.labels, calibrated, bins=ece_bins
        ),
        cpu_p95_ms=float(np.percentile(prediction.latency_ms, 95, method="linear")),
        event_count=len(prediction.labels),
        latency_sample_count=len(prediction.latency_ms),
    )
    metrics.validate()
    return metrics, calibrated


@dataclass(frozen=True)
class SelectionDecision:
    """Validation-only next-correct selection result."""

    winner: str
    primary_auc: float
    auc_tolerance: float
    contenders: tuple[str, ...]
    tie_break_order: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "winner": self.winner,
            "primaryMetric": "AUC",
            "primaryValue": self.primary_auc,
            "aucTolerance": self.auc_tolerance,
            "contenders": list(self.contenders),
            "tieBreakOrder": list(self.tie_break_order),
        }


def select_next_correct_model(
    metrics: dict[str, CandidateMetrics],
    *,
    auc_tolerance: float = 0.005,
) -> SelectionDecision:
    """Select between DKT and AKT using the frozen validation policy."""

    if auc_tolerance != 0.005:
        raise ModelSelectionError("knowledge AUC tie tolerance must remain 0.005")
    eligible = {family: metrics[family] for family in ("DKT", "AKT") if family in metrics}
    if set(eligible) != {"DKT", "AKT"}:
        raise ModelSelectionError("knowledge selection requires both DKT and AKT metrics")
    for family, values in eligible.items():
        values.validate()
        numeric = (values.auc, values.log_loss, values.ece_15, values.cpu_p95_ms)
        if not np.isfinite(numeric).all():
            raise ModelSelectionError(f"knowledge candidate {family} has non-finite metrics")
    best_auc = max(values.auc for values in eligible.values())
    contenders = {
        family: values
        for family, values in eligible.items()
        if round(best_auc - values.auc, _METRIC_COMPARISON_DECIMALS)
        <= auc_tolerance
    }
    winner = min(
        contenders,
        key=lambda family: (
            contenders[family].log_loss,
            contenders[family].ece_15,
            contenders[family].cpu_p95_ms,
            family,
        ),
    )
    return SelectionDecision(
        winner=winner,
        primary_auc=eligible[winner].auc,
        auc_tolerance=auc_tolerance,
        contenders=tuple(sorted(contenders)),
        tie_break_order=("LOG_LOSS", "ECE_15", "CPU_P95_MS", "FAMILY_NAME"),
    )


def assert_unified_predictions(predictions: dict[str, PredictionResult]) -> None:
    """Prove every candidate evaluated the same event keys and labels in the same order."""

    if set(predictions) != {"IRT", "BKT", "DKT", "AKT"}:
        raise DataQualityError("unified knowledge evaluation requires four candidates")
    reference = predictions["IRT"]
    reference.validate()
    for family, prediction in predictions.items():
        prediction.validate()
        if prediction.event_keys != reference.event_keys or not np.array_equal(
            prediction.labels, reference.labels
        ):
            raise DataQualityError(
                f"knowledge candidate {family} evaluated a different event set"
            )
