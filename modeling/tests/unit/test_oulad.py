from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from edutwin_modeling.config import ProjectPaths
from edutwin_modeling.data.oulad import (
    FEATURE_COLUMNS,
    build_oulad_risk_units,
    prepare_oulad,
)
from edutwin_modeling.errors import DataQualityError


def _config() -> dict[str, object]:
    return {
        "seed": 42,
        "oulad": {
            "feature_window_start_day": 0,
            "feature_window_end_day": 29,
            "positive_final_results": ["Fail"],
            "positive_withdrawal_after_day": 29,
            "negative_final_results": ["Pass", "Distinction"],
        },
        "split": {"namespace": "edutwin-student-split-v1"},
    }


def _source_frames() -> tuple[pd.DataFrame, ...]:
    student_info = pd.DataFrame(
        [
            ["AAA", "2013J", 1, "Pass"],
            ["BBB", "2014B", 1, "Distinction"],
            ["AAA", "2013J", 2, "Fail"],
            ["AAA", "2013J", 3, "Withdrawn"],
            ["AAA", "2013J", 4, "Withdrawn"],
        ],
        columns=["code_module", "code_presentation", "id_student", "final_result"],
    )
    registration = pd.DataFrame(
        [
            ["AAA", "2013J", 1, np.nan],
            ["BBB", "2014B", 1, np.nan],
            ["AAA", "2013J", 2, np.nan],
            ["AAA", "2013J", 3, 29],
            ["AAA", "2013J", 4, 30],
        ],
        columns=["code_module", "code_presentation", "id_student", "date_unregistration"],
    )
    student_vle = pd.DataFrame(
        [
            ["AAA", "2013J", 1, 10, 0, 2],
            ["AAA", "2013J", 1, 11, 29, 3],
            ["AAA", "2013J", 1, 12, 30, 1_000_000],
            ["BBB", "2014B", 1, 20, 2, 5],
            ["AAA", "2013J", 2, 10, 1, 4],
            ["AAA", "2013J", 3, 10, 2, 5],
            ["AAA", "2013J", 4, 10, 3, 6],
        ],
        columns=[
            "code_module",
            "code_presentation",
            "id_student",
            "id_site",
            "date",
            "sum_click",
        ],
    )
    assessments = pd.DataFrame(
        [[100, "AAA", "2013J"], [200, "BBB", "2014B"]],
        columns=["id_assessment", "code_module", "code_presentation"],
    )
    student_assessment = pd.DataFrame(
        [
            [100, 1, 10, 60],
            [100, 1, 30, 100],
            [100, 2, 10, 20],
            [100, 3, 10, 30],
            [100, 4, 10, 40],
            [200, 1, 10, 80],
        ],
        columns=["id_assessment", "id_student", "date_submitted", "score"],
    )
    return student_info, registration, student_vle, assessments, student_assessment


@pytest.mark.parametrize("invalid_result", ["Unknown", "", None, np.nan])
def test_unknown_blank_or_null_final_result_is_rejected(invalid_result: object) -> None:
    frames = _source_frames()
    student_info = frames[0].copy()
    student_info.loc[0, "final_result"] = invalid_result
    with pytest.raises(DataQualityError, match="final_result"):
        build_oulad_risk_units(student_info, *frames[1:], config=_config())


def test_withdrawn_with_missing_unregistration_date_is_excluded() -> None:
    frames = _source_frames()
    registration = frames[1].copy()
    registration["date_unregistration"] = registration["date_unregistration"].astype(
        object
    )
    registration.loc[registration["id_student"].eq(4), "date_unregistration"] = np.nan

    result = build_oulad_risk_units(
        frames[0],
        registration,
        *frames[2:],
        config=_config(),
    )

    assert "AAA|2013J|4" not in set(result["oulad_student_key"])
    assert result.attrs["label_audit"]["withdrawn_missing_date_excluded"] == 1


def test_withdrawn_with_non_numeric_unregistration_date_fails() -> None:
    frames = _source_frames()
    registration = frames[1].copy()
    registration["date_unregistration"] = registration["date_unregistration"].astype(
        object
    )
    registration.loc[registration["id_student"].eq(3), "date_unregistration"] = "not-a-day"
    with pytest.raises(DataQualityError, match="date_unregistration"):
        build_oulad_risk_units(
            frames[0],
            registration,
            *frames[2:],
            config=_config(),
        )


def test_day_30_behavior_cannot_change_features() -> None:
    frames = _source_frames()
    with_future = build_oulad_risk_units(*frames, config=_config())
    without_future_vle = frames[2].loc[frames[2]["date"].le(29)].copy()
    without_future_assessments = frames[4].loc[
        frames[4]["date_submitted"].le(29)
    ].copy()
    without_future = build_oulad_risk_units(
        frames[0],
        frames[1],
        without_future_vle,
        frames[3],
        without_future_assessments,
        _config(),
    )
    columns = ["oulad_student_key", *FEATURE_COLUMNS, "performance", "activity", "persistence"]
    pd.testing.assert_frame_equal(with_future[columns], without_future[columns])
    first = with_future.loc[with_future["oulad_student_key"].eq("AAA|2013J|1")].iloc[0]
    assert first["vle_total_clicks"] == 5
    assert first["assessment_mean_score"] == 60


def test_early_withdrawal_is_excluded_and_late_withdrawal_is_positive() -> None:
    result = build_oulad_risk_units(*_source_frames(), config=_config())
    assert "AAA|2013J|3" not in set(result["oulad_student_key"])
    labels = result.set_index("oulad_student_key")["risk_label"].to_dict()
    assert labels["AAA|2013J|1"] == 0
    assert labels["AAA|2013J|2"] == 1
    assert labels["AAA|2013J|4"] == 1
    assert result.attrs["label_audit"]["withdrawn_day_0_29_excluded"] == 1
    assert "final_result" not in result.columns
    assert "date_unregistration" not in result.columns


def test_same_student_has_one_stable_split_across_courses() -> None:
    result = build_oulad_risk_units(*_source_frames(), config=_config())
    student_one = result.loc[result["id_student"].eq("1")]
    assert len(student_one) == 2
    assert student_one["split"].nunique() == 1
    assert student_one["oulad_person_key"].eq("1").all()


def test_prepare_oulad_writes_only_portable_manifest_paths(tmp_path: Path) -> None:
    modeling_root = tmp_path / "modeling"
    extracted_dir = modeling_root / "raw" / "oulad"
    extracted_dir.mkdir(parents=True)
    filenames = [
        "studentInfo.csv",
        "studentRegistration.csv",
        "studentVle.csv",
        "assessments.csv",
        "studentAssessment.csv",
    ]
    for filename, frame in zip(filenames, _source_frames(), strict=True):
        frame.to_csv(extracted_dir / filename, index=False)

    config = _config()
    config["paths"] = {
        "oulad_extracted": "raw/oulad",
        "oulad_output": "processed/oulad",
    }
    paths = ProjectPaths(repository_root=tmp_path, modeling_root=modeling_root)
    manifest = prepare_oulad(config, paths)
    manifest_path = modeling_root / "processed" / "oulad" / "manifest.json"
    serialized_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    output_paths = [
        entry["path"] for entry in serialized_manifest["outputs"].values()
    ]
    assert output_paths
    assert all(not Path(path).is_absolute() for path in output_paths)
    assert not Path(manifest["manifest_path"]).is_absolute()
