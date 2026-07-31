from __future__ import annotations

import pandas as pd
import pytest

from edutwin_modeling.data.assistments import (
    ANSWER_EVENT_COLUMNS,
    ANSWER_EVENT_SKILL_COLUMNS,
    fold_order_events,
)
from edutwin_modeling.errors import DataQualityError

MULTI_SKILL_FIELDS = ["skill_id", "skill_name", "opportunity", "opportunity_original"]
CORE_FIELDS = [
    "assignment_id",
    "user_id",
    "assistment_id",
    "problem_id",
    "original",
    "correct",
    "attempt_count",
    "ms_first_response",
    "tutor_mode",
    "answer_type",
    "sequence_id",
    "student_class_id",
    "position",
    "type",
    "base_sequence_id",
    "teacher_id",
    "school_id",
    "hint_count",
    "hint_total",
    "overlap_time",
    "template_id",
    "answer_id",
    "answer_text",
    "first_action",
    "bottom_hint",
]
CONFIG = {
    "seed": 42,
    "assistments": {
        "expected_columns": 30,
        "event_key": "order_id",
        "missing_skill_token": "__MISSING_SKILL__",
        "multi_skill_fields": MULTI_SKILL_FIELDS,
        "required_fields": ["order_id", "user_id", "problem_id", "correct"],
        "core_consistency_fields": CORE_FIELDS,
    },
    "split": {
        "namespace": "edutwin-student-split-v1",
        "train_upper_exclusive": 7000,
        "validation_upper_exclusive": 8500,
        "test_upper_exclusive": 10000,
    },
}


def _row(
    order_id: str,
    skill_id: str,
    *,
    user_id: str = "student-1",
    problem_id: str = "problem-1",
    correct: str = "1",
    opportunity: str = "1",
) -> dict[str, str]:
    return {
        "order_id": order_id,
        "assignment_id": "assignment-1",
        "user_id": user_id,
        "assistment_id": "assistment-1",
        "problem_id": problem_id,
        "original": "1",
        "correct": correct,
        "attempt_count": "1",
        "ms_first_response": "1200",
        "tutor_mode": "tutor",
        "answer_type": "algebra",
        "sequence_id": "sequence-1",
        "student_class_id": "class-1",
        "position": "1",
        "type": "MasterySection",
        "base_sequence_id": "base-sequence-1",
        "teacher_id": "teacher-1",
        "school_id": "school-1",
        "hint_count": "0",
        "hint_total": "0",
        "overlap_time": "0",
        "template_id": "template-1",
        "answer_id": "answer-1",
        "answer_text": "raw response excluded from outputs",
        "first_action": "0",
        "bottom_hint": "0",
        "skill_id": skill_id,
        "skill_name": f"skill-{skill_id}",
        "opportunity": opportunity,
        "opportunity_original": opportunity,
    }


def test_multi_skill_rows_fold_to_one_event_and_stable_associations() -> None:
    first = _row("100", "11")
    frame = pd.DataFrame(
        [
            first,
            _row("100", "10"),
            first.copy(),
            _row("101", "12", problem_id="problem-2", correct="0", opportunity="2"),
        ]
    )

    events, skills = fold_order_events(frame, CONFIG)

    assert events.columns.tolist() == ANSWER_EVENT_COLUMNS
    assert skills.columns.tolist() == ANSWER_EVENT_SKILL_COLUMNS
    assert events["order_id"].tolist() == ["100", "101"]
    assert events["skill_count"].tolist() == [2, 1]
    assert events["event_sequence"].tolist() == [1, 2]
    assert events["split"].nunique() == 1
    assert skills[["order_id", "skill_id", "ordinal"]].to_dict("records") == [
        {"order_id": "100", "skill_id": "10", "ordinal": 1},
        {"order_id": "100", "skill_id": "11", "ordinal": 2},
        {"order_id": "101", "skill_id": "12", "ordinal": 1},
    ]
    assert "answer_text" not in events.columns
    assert "answer_text" not in skills.columns


def test_conflicting_core_fields_for_one_order_fail_quality_gate() -> None:
    frame = pd.DataFrame(
        [
            _row("100", "10", problem_id="problem-1"),
            _row("100", "11", problem_id="problem-2"),
        ]
    )

    with pytest.raises(DataQualityError, match="core consistency conflict"):
        fold_order_events(frame, CONFIG)


def test_missing_skill_event_is_preserved_with_explicit_source_token() -> None:
    missing = _row("100", "")
    missing["skill_name"] = ""

    events, skills = fold_order_events(pd.DataFrame([missing]), CONFIG)

    assert events[["order_id", "skill_count"]].to_dict("records") == [
        {"order_id": "100", "skill_count": 1}
    ]
    assert skills[
        ["skill_id", "skill_name", "opportunity", "opportunity_original"]
    ].to_dict("records") == [
        {
            "skill_id": "__MISSING_SKILL__",
            "skill_name": "__MISSING_SKILL__",
            "opportunity": "1",
            "opportunity_original": "1",
        }
    ]


def test_event_cannot_mix_missing_and_identified_skill_rows() -> None:
    missing = _row("100", "")
    missing["skill_name"] = ""
    missing["opportunity"] = ""
    missing["opportunity_original"] = ""

    with pytest.raises(DataQualityError, match="cannot mix"):
        fold_order_events(pd.DataFrame([missing, _row("100", "10")]), CONFIG)
