from __future__ import annotations

import pandas as pd
import pytest

from edutwin_modeling.data.matching import (
    fit_source_transform,
    greedy_match_split,
    match_students,
    select_one_oulad_unit_per_person,
)
from edutwin_modeling.errors import DataQualityError


def _vector_rows(key_name: str, keys: list[str], values: list[float]) -> pd.DataFrame:
    result = pd.DataFrame(
        {
            key_name: keys,
            "split": ["train"] * len(keys),
            "performance": values,
            "activity": values,
            "persistence": values,
            "performance_z": values,
            "activity_z": values,
            "persistence_z": values,
        }
    )
    if key_name == "oulad_student_key":
        result["oulad_person_key"] = [f"person-{key}" for key in keys]
    return result


def test_winsor_and_zscore_statistics_use_train_only() -> None:
    frame = pd.DataFrame(
        {
            "source_key": ["train-a", "train-b", "validation-outlier"],
            "split": ["train", "train", "validation"],
            "performance": [0.0, 10.0, 10_000.0],
            "activity": [0.0, 10.0, 10_000.0],
            "persistence": [0.0, 10.0, 10_000.0],
        }
    )
    transformed, metadata = fit_source_transform(
        frame,
        key_column="source_key",
    )
    assert metadata["fit_row_count"] == 2
    assert metadata["dimensions"]["performance"]["winsor_lower"] == pytest.approx(0.1)
    assert metadata["dimensions"]["performance"]["winsor_upper"] == pytest.approx(9.9)
    assert metadata["dimensions"]["performance"]["mean"] == pytest.approx(5.0)
    outlier_z = transformed.loc[
        transformed["source_key"].eq("validation-outlier"), "performance_z"
    ].item()
    assert outlier_z == pytest.approx(1.0)


def test_exact_nearest_neighbor_is_without_replacement_and_has_stable_ties() -> None:
    assist = _vector_rows("assistments_user_key", ["assist-a", "assist-b"], [0.0, 0.0])
    oulad = _vector_rows("oulad_student_key", ["oulad-b", "oulad-a"], [0.0, 0.0])
    matches = greedy_match_split(assist, oulad, split="train", count=2, seed=42)
    assert matches["assistments_user_key"].nunique() == 2
    assert matches["oulad_person_key"].nunique() == 2
    assert matches["oulad_student_key"].nunique() == 2
    assert matches.iloc[0]["oulad_student_key"] == "oulad-a"
    assert set(matches["distance"]) == {0.0}


def test_one_oulad_unit_per_person_is_deterministic_across_input_order() -> None:
    frame = pd.DataFrame(
        {
            "oulad_person_key": ["person-1", "person-1", "person-2"],
            "oulad_student_key": ["unit-b", "unit-a", "unit-c"],
            "split": ["train", "train", "validation"],
        }
    )
    selected = select_one_oulad_unit_per_person(frame)
    selected_from_reverse = select_one_oulad_unit_per_person(
        frame.iloc[::-1].reset_index(drop=True)
    )

    assert len(selected) == 2
    assert selected["oulad_person_key"].is_unique
    assert selected.loc[selected["oulad_person_key"].eq("person-1")].shape[0] == 1
    pd.testing.assert_frame_equal(selected, selected_from_reverse)


def test_oulad_person_cannot_span_matching_splits() -> None:
    frame = pd.DataFrame(
        {
            "oulad_person_key": ["person-1", "person-1"],
            "oulad_student_key": ["train-unit", "test-unit"],
            "split": ["train", "test"],
        }
    )
    with pytest.raises(DataQualityError, match="multiple splits"):
        select_one_oulad_unit_per_person(frame)


def test_assistments_students_below_20_events_cannot_fill_fixed_quota() -> None:
    rows: list[dict[str, object]] = []
    oulad_rows: list[dict[str, object]] = []
    counts = {"train": 1400, "validation": 300, "test": 300}
    for split, count in counts.items():
        for index in range(count):
            value = float(index % 11)
            rows.append(
                {
                    "assistments_user_key": f"assist-{split}-{index}",
                    "split": split,
                    "performance": value,
                    "activity": value,
                    "persistence": value,
                    "event_count": 19 if split == "train" and index == 0 else 20,
                }
            )
            oulad_rows.append(
                {
                    "oulad_person_key": f"person-{split}-{index}",
                    "oulad_student_key": f"oulad-{split}-{index}",
                    "split": split,
                    "performance": value,
                    "activity": value,
                    "persistence": value,
                }
            )
    config = {
        "seed": 42,
        "matching": {
            "dimensions": ["performance", "activity", "persistence"],
            "winsor_lower_quantile": 0.01,
            "winsor_upper_quantile": 0.99,
            "fit_split": "train",
            "target_counts": counts,
            "assistments_min_events": 20,
        },
    }
    with pytest.raises(DataQualityError, match="requires 1400 rows"):
        match_students(pd.DataFrame(rows), pd.DataFrame(oulad_rows), config)
