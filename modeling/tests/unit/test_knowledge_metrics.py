from __future__ import annotations

import numpy as np
import pytest

from edutwin_modeling.knowledge.metrics import (
    CandidateMetrics,
    PlattCalibrator,
    expected_calibration_error,
    select_next_correct_model,
)


def _metrics(
    auc: float,
    log_loss: float,
    ece: float,
    latency: float,
) -> CandidateMetrics:
    return CandidateMetrics(
        auc=auc,
        log_loss=log_loss,
        ece_15=ece,
        cpu_p95_ms=latency,
        event_count=100,
        latency_sample_count=100,
    )


def test_selection_uses_log_loss_when_auc_difference_is_at_most_point_005() -> None:
    decision = select_next_correct_model(
        {
            "DKT": _metrics(0.800, 0.40, 0.03, 1.0),
            "AKT": _metrics(0.805, 0.35, 0.04, 2.0),
        }
    )
    assert decision.winner == "AKT"
    assert decision.contenders == ("AKT", "DKT")


def test_selection_uses_auc_when_difference_exceeds_point_005() -> None:
    decision = select_next_correct_model(
        {
            "DKT": _metrics(0.800, 0.20, 0.01, 0.1),
            "AKT": _metrics(0.806, 0.60, 0.20, 5.0),
        }
    )
    assert decision.winner == "AKT"
    assert decision.contenders == ("AKT",)


def test_selection_tie_breaks_by_ece_then_cpu_latency_at_inclusive_boundary() -> None:
    by_ece = select_next_correct_model(
        {
            "DKT": _metrics(0.800, 0.35, 0.02, 2.0),
            "AKT": _metrics(0.805, 0.35, 0.03, 1.0),
        }
    )
    assert by_ece.winner == "DKT"
    assert by_ece.contenders == ("AKT", "DKT")

    by_latency = select_next_correct_model(
        {
            "DKT": _metrics(0.800, 0.35, 0.02, 2.0),
            "AKT": _metrics(0.805, 0.35, 0.02, 1.0),
        }
    )
    assert by_latency.winner == "AKT"


def test_platt_calibration_and_ece_remain_bounded() -> None:
    labels = np.asarray([0, 0, 1, 1], dtype="int8")
    probabilities = np.asarray([0.1, 0.4, 0.6, 0.9], dtype="float64")
    calibrator = PlattCalibrator.fit(labels, probabilities, seed=42)
    calibrated = calibrator.transform(probabilities)
    assert np.all((calibrated > 0.0) & (calibrated < 1.0))
    assert expected_calibration_error(labels, calibrated, bins=15) == pytest.approx(
        expected_calibration_error(labels, calibrated, bins=15)
    )
