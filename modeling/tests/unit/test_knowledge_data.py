from __future__ import annotations

import pandas as pd
import pytest

from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.knowledge.data import (
    UNKNOWN_TOKEN,
    expected_prediction_keys,
    prepare_knowledge_data,
)


def _frames() -> tuple[pd.DataFrame, pd.DataFrame]:
    event_rows: list[dict[str, object]] = []
    skill_rows: list[dict[str, object]] = []
    students = [
        ("train-a", "train"),
        ("train-b", "train"),
        ("train-c", "train"),
        ("train-d", "train"),
        ("validation-a", "validation"),
        ("test-a", "test"),
    ]
    for student, split in students:
        for sequence in range(1, 4):
            order_id = f"{student}-{sequence}"
            problem = (
                "validation-only-problem"
                if split == "validation"
                else f"problem-{sequence % 2}"
            )
            event_rows.append(
                {
                    "order_id": order_id,
                    "user_id": student,
                    "problem_id": problem,
                    "correct": sequence % 2,
                    "event_sequence": sequence,
                    "split": split,
                }
            )
            skill_rows.append(
                {
                    "order_id": order_id,
                    "skill_id": (
                        "validation-only-skill"
                        if split == "validation"
                        else f"skill-{sequence % 2}"
                    ),
                    "ordinal": 1,
                }
            )
            if student == "train-a" and sequence == 1:
                skill_rows.append(
                    {"order_id": order_id, "skill_id": "skill-extra", "ordinal": 2}
                )
    return pd.DataFrame(event_rows), pd.DataFrame(skill_rows)


def test_vocabulary_and_calibration_roles_are_train_side_and_stable() -> None:
    events, skills = _frames()
    prepared = prepare_knowledge_data(
        events, skills, calibration_fraction=0.25, seed=42
    )
    reversed_prepared = prepare_knowledge_data(
        events.iloc[::-1].reset_index(drop=True),
        skills.iloc[::-1].reset_index(drop=True),
        calibration_fraction=0.25,
        seed=42,
    )

    assert prepared.vocabulary == reversed_prepared.vocabulary
    assert "validation-only-problem" not in prepared.vocabulary.problem_to_index
    assert "validation-only-skill" not in prepared.vocabulary.skill_to_index
    assert prepared.vocabulary.problem_index("validation-only-problem") == (
        prepared.vocabulary.problem_to_index[UNKNOWN_TOKEN]
    )
    assert len(prepared.for_role("calibration")) == 1
    assert prepared.for_role("calibration") == reversed_prepared.for_role("calibration")
    train_a = next(
        sequence for sequence in prepared.sequences if sequence.student_key == "train-a"
    )
    assert len(train_a.skill_indices[0]) == 2
    assert expected_prediction_keys(prepared.for_role("validation")) == (
        "validation-a-2",
        "validation-a-3",
    )


def test_student_split_leakage_is_rejected() -> None:
    events, skills = _frames()
    events.loc[events["order_id"].eq("train-a-3"), "split"] = "test"
    with pytest.raises(DataQualityError, match="span data splits"):
        prepare_knowledge_data(events, skills, calibration_fraction=0.25, seed=42)


def test_fixed_hash_split_is_verified_when_requested() -> None:
    events, skills = _frames()
    with pytest.raises(DataQualityError, match="fixed SHA-256 contract"):
        prepare_knowledge_data(
            events,
            skills,
            calibration_fraction=0.25,
            seed=42,
            verify_student_split=True,
        )


def test_skill_ordinals_must_be_unique_and_contiguous() -> None:
    events, skills = _frames()
    skills.loc[
        skills["order_id"].eq("train-a-1") & skills["skill_id"].eq("skill-extra"),
        "ordinal",
    ] = 3
    with pytest.raises(DataQualityError, match="contiguous from one"):
        prepare_knowledge_data(events, skills, calibration_fraction=0.25, seed=42)
