from __future__ import annotations

import json
from uuid import UUID, uuid5

import pandas as pd
import pytest

from edutwin_modeling.data.synthetic import (
    _build_assessment_submissions,
    build_synthetic_frames,
)
from edutwin_modeling.errors import DataQualityError

NAMESPACE = UUID("a3e0f31f-54f6-4f99-82e8-bf5e6fdd55e4")
RAW_USER_A = "RAW-ASSIST-USER-ALPHA-DO-NOT-EXPORT"
RAW_USER_B = "RAW-ASSIST-USER-BETA-DO-NOT-EXPORT"
RAW_OULAD_A = "RAW-OULAD-STUDENT-ALPHA-DO-NOT-EXPORT"
RAW_OULAD_B = "RAW-OULAD-STUDENT-BETA-DO-NOT-EXPORT"


def _matches() -> pd.DataFrame:
    return pd.DataFrame(
        {
            "match_order": [1, 2],
            "split": ["train", "validation"],
            "assistments_user_key": [RAW_USER_A, RAW_USER_B],
            "oulad_student_key": [RAW_OULAD_A, RAW_OULAD_B],
            "distance": [0.1, 0.2],
            "assist_performance": [0.45, 0.70],
            "oulad_performance": [0.55, 0.80],
            "assist_activity": [2.0, 3.0],
            "oulad_activity": [2.1, 3.1],
            "assist_persistence": [0.2, 0.4],
            "oulad_persistence": [0.3, 0.5],
            "oulad_code_module": ["RAW-MODULE-ALPHA", "RAW-MODULE-BETA"],
            "oulad_code_presentation": ["RAW-PRESENTATION-A", "RAW-PRESENTATION-B"],
        }
    )


def _events(events_per_student: int = 50) -> pd.DataFrame:
    records: list[dict[str, object]] = []
    for user_number, (user_id, split) in enumerate(
        ((RAW_USER_A, "train"), (RAW_USER_B, "validation")), start=1
    ):
        for sequence in range(1, events_per_student + 1):
            records.append(
                {
                    "order_id": f"RAW-ORDER-{user_number}-{sequence:03d}",
                    "user_id": user_id,
                    "problem_id": f"RAW-PROBLEM-{user_number}-{sequence:03d}",
                    "assignment_id": f"RAW-ASSIGNMENT-{user_number}",
                    "correct": sequence % 2,
                    "attempt_count": 111_000_000 + sequence,
                    "ms_first_response": 222_000_000 + sequence,
                    "hint_count": 333_000_000 + sequence,
                    "skill_count": 1,
                    "event_sequence": 444_000_000 + sequence,
                    "split": split,
                    "answer_text": f"RAW-ANSWER-TEXT-{user_number}-{sequence:03d}",
                }
            )
    return pd.DataFrame.from_records(records)


def _skills(events_per_student: int = 50) -> pd.DataFrame:
    records: list[dict[str, object]] = []
    for user_number in (1, 2):
        for sequence in range(1, events_per_student + 1):
            records.append(
                {
                    "order_id": f"RAW-ORDER-{user_number}-{sequence:03d}",
                    "skill_id": f"RAW-SKILL-ID-{user_number}-{sequence:03d}",
                    "skill_name": f"RAW-SKILL-NAME-{user_number}-{sequence:03d}",
                    "opportunity": 555_000_000 + sequence,
                    "opportunity_original": 666_000_000 + sequence,
                    "ordinal": 1,
                }
            )
    return pd.DataFrame.from_records(records)


def _build() -> dict[str, pd.DataFrame]:
    return build_synthetic_frames(
        _matches(),
        _events(),
        _skills(),
        namespace=NAMESPACE,
        expected_students=2,
        seed=42,
    )


def test_outputs_are_deterministic_varied_and_unique() -> None:
    first = _build()
    second = _build()
    expected_frames = {
        "students",
        "organization_units",
        "academic_terms",
        "courses",
        "teachers",
        "teaching_assignments",
        "enrollments",
        "knowledge_skills",
        "questions",
        "course_questions",
        "lms_sections",
        "lms_lessons",
        "lms_assessments",
        "lms_assessment_questions",
        "lms_lesson_progress",
        "lms_submissions",
        "lms_submission_answers",
        "learning_activity_events",
        "answer_events",
        "answer_event_skills",
        "matches",
    }
    assert set(first) == expected_frames
    for name in first:
        pd.testing.assert_frame_equal(first[name], second[name])
    assert len(first["students"]) == 2
    assert len(first["courses"]) == 20
    assert len(first["teachers"]) == 12
    assert len(first["teaching_assignments"]) == 20
    assert len(first["enrollments"]) == 8
    assert len(first["knowledge_skills"]) == 60
    assert len(first["questions"]) == 600
    assert len(first["lms_assessments"]) == 100
    assert len(first["lms_assessment_questions"]) == 500
    assert len(first["organization_units"]) == 9
    assert len(first["academic_terms"]) == 1
    assert len(first["lms_lesson_progress"]) == 104
    assert len(first["lms_submissions"]) == 8
    assert len(first["lms_submission_answers"]) == 40
    answer_count = len(first["answer_events"])
    activity_count = len(first["learning_activity_events"])
    assert 8 * 18 <= answer_count <= 8 * 42
    assert len(first["answer_event_skills"]) == answer_count
    assert 8 * 56 <= activity_count <= 8 * 176
    assert first["answer_events"]["answer_event_id"].nunique() == answer_count
    assert first["answer_events"]["event_key"].nunique() == answer_count
    assert first["questions"]["question_id"].nunique() == 600
    assert first["knowledge_skills"]["name"].nunique() == 60
    assert first["knowledge_skills"]["content_origin"].eq("CURATED_SYNTHETIC").all()
    assert first["questions"]["content_origin"].eq("TEACHER_AUTHORED").all()
    assert first["questions"]["knowledge_model_mode"].eq("ONLINE_BKT").all()
    assert first["questions"]["prompt_text"].nunique() == 600
    assert not first["questions"]["prompt_text"].str.contains(
        "练习题|请选择最符合课程知识要求|忽略题目条件|只记录最终答案"
    ).any()
    assert first["questions"]["options_json"].map(
        lambda value: len(json.loads(value)) == 4
    ).all()
    assert first["questions"].groupby("knowledge_skill_id").size().eq(10).all()
    assert first["students"].iloc[0]["student_id"] == str(uuid5(NAMESPACE, "student|train|0001"))
    answer_counts = first["answer_events"].groupby(["course_id", "student_id"])[
        "event_sequence"
    ].count()
    activity_counts = first["learning_activity_events"].groupby(
        ["course_id", "student_id"]
    ).size()
    assert answer_counts.between(18, 42).all()
    assert activity_counts.between(56, 176).all()
    assert answer_counts.nunique() > 1
    assert activity_counts.nunique() > 1
    for _, sequences in first["answer_events"].groupby(["course_id", "student_id"]):
        assert sequences["event_sequence"].tolist() == list(range(1, len(sequences) + 1))
    activity_metadata = first["learning_activity_events"]["metadata_json"].map(json.loads)
    assert activity_metadata.map(lambda value: bool(value.get("behaviorProfile"))).all()
    session_ids = activity_metadata.map(lambda value: value.get("sessionId"))
    paired = first["learning_activity_events"].assign(session_id=session_ids).loc[
        lambda frame: frame["event_type"].isin(
            {"PRACTICE_START", "PRACTICE_SUBMIT", "ASSESSMENT_OPEN", "ASSESSMENT_SUBMIT"}
        )
    ]
    session_types = paired.groupby("session_id")["event_type"].agg(set)
    assert session_types.map(
        lambda values: values
        in (
            {"PRACTICE_START", "PRACTICE_SUBMIT"},
            {"ASSESSMENT_OPEN", "ASSESSMENT_SUBMIT"},
        )
    ).all()
    assert first["courses"]["title"].tolist()[0] == "高等数学"
    assert first["students"]["display_name"].str.contains("Synthetic").sum() == 0
    assert first["answer_events"]["response_time_ms"].max() <= 120_000
    assert first["answer_events"]["attempt_number"].max() <= 2
    assert first["answer_events"]["hint_count"].max() <= 1
    assert set(first["organization_units"]["unit_type"]) == {
        "COLLEGE",
        "DEPARTMENT",
        "MAJOR",
        "CLASS",
    }
    assert first["lms_submissions"]["submitted_at"].str.startswith("2026-05-").all()
    answer_scores = first["lms_submission_answers"].groupby("submission_id")[
        "points_awarded"
    ].sum()
    submission_scores = first["lms_submissions"].set_index("submission_id")["score"]
    pd.testing.assert_series_equal(
        submission_scores.sort_index(), answer_scores.sort_index(), check_names=False
    )
    enrolled_courses = set(first["enrollments"]["course_id"])
    submitted = set(first["lms_submissions"]["assessment_id"])
    for course_id, assessments in first["lms_assessments"].groupby("course_id"):
        if course_id not in enrolled_courses:
            continue
        assert assessments["status"].eq("PUBLISHED").any()
        assert bool(set(assessments["assessment_id"]) & submitted)
        assert bool(
            set(assessments.loc[assessments["status"].eq("CLOSED"), "assessment_id"])
            - submitted
        )


def test_no_source_identifier_name_or_behavior_value_reaches_outputs() -> None:
    frames = _build()
    assert list(frames["matches"].columns) == [
        "student_id",
        "match_order",
        "split",
        "distance",
        "performance",
        "activity",
        "persistence",
    ]
    serialized_outputs = "\n".join(
        frame.astype(str).to_csv(index=False) for frame in frames.values()
    )
    forbidden_values = {
        RAW_USER_A,
        RAW_USER_B,
        RAW_OULAD_A,
        RAW_OULAD_B,
        "RAW-MODULE-ALPHA",
        "RAW-MODULE-BETA",
        "RAW-PRESENTATION-A",
        "RAW-PRESENTATION-B",
        "RAW-ORDER-1-001",
        "RAW-PROBLEM-1-001",
        "RAW-ASSIGNMENT-1",
        "RAW-SKILL-ID-1-001",
        "RAW-SKILL-NAME-1-001",
        "RAW-ANSWER-TEXT-1-001",
    }
    for forbidden in forbidden_values:
        assert forbidden not in serialized_outputs
    forbidden_columns = {
        "order_id",
        "problem_id",
        "assignment_id",
        "skill_id",
        "skill_name",
        "answer_text",
        "assistments_user_key",
        "oulad_student_key",
        "source_hash",
    }
    for frame in frames.values():
        assert forbidden_columns.isdisjoint(frame.columns)


def test_assessment_attempts_include_deterministic_retake() -> None:
    frames = _build()
    students = frames["students"]
    course_ids = frames["courses"]["course_id"].head(5).tolist()
    enrollments = pd.DataFrame.from_records(
        [
            {"course_id": course_id, "student_id": student_id, "status": "ACTIVE"}
            for student_id in students["student_id"]
            for course_id in course_ids
        ]
    )
    content = {
        name: frames[name]
        for name in (
            "lms_sections",
            "lms_lessons",
            "lms_assessments",
            "lms_assessment_questions",
        )
    }

    submissions, answers = _build_assessment_submissions(
        students, enrollments, content, namespace=NAMESPACE, seed=42
    )

    assert len(submissions) == 11
    assert submissions["attempt_number"].eq(2).sum() == 1
    assert submissions.loc[submissions["attempt_number"].eq(2), "attempt_type"].eq(
        "RETAKE"
    ).all()
    assert len(answers) == 55


def test_source_quality_gate_rejects_fewer_than_20_events() -> None:
    with pytest.raises(DataQualityError, match="at least 20 source events"):
        build_synthetic_frames(
            _matches(),
            _events(events_per_student=19),
            _skills(events_per_student=19),
            namespace=NAMESPACE,
            expected_students=2,
            seed=42,
        )


def test_source_quality_gate_rejects_duplicate_event_keys() -> None:
    events = pd.concat([_events(), _events().iloc[[0]]], ignore_index=True)
    with pytest.raises(DataQualityError, match="duplicate key"):
        build_synthetic_frames(
            _matches(),
            events,
            _skills(),
            namespace=NAMESPACE,
            expected_students=2,
            seed=42,
        )
