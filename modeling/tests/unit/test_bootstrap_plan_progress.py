import pytest

from edutwin_modeling.data.bootstrap import (
    _plan_progress_fraction,
    _plan_task_completed_count,
)


def test_current_plan_progress_reflects_behavior_and_risk() -> None:
    disengaging = _plan_progress_fraction(6, engagement=0.2, persistence=0.2, risk_probability=0.8)
    steady = _plan_progress_fraction(6, engagement=0.6, persistence=0.6, risk_probability=0.4)
    improving = _plan_progress_fraction(6, engagement=0.9, persistence=0.9, risk_probability=0.1)

    assert disengaging < steady < improving
    assert (disengaging, steady, improving) == pytest.approx((0.02, 0.35, 0.725))


def test_current_plan_keeps_an_actionable_task_at_high_progress() -> None:
    counts = [_plan_task_completed_count(0.9, ordinal) for ordinal in range(1, 4)]

    assert counts == [3, 2, 3]
    assert any(completed < 3 for completed in counts)


def test_historical_plan_progress_stays_version_driven() -> None:
    assert _plan_progress_fraction(1, 1.0, 1.0, 0.0) == 0.0
    assert _plan_progress_fraction(5, 0.0, 0.0, 1.0) == pytest.approx(4 / 6)
