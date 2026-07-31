"""Deterministic demo source-lineage generation tests."""

from __future__ import annotations

import hashlib
from uuid import UUID

import pandas as pd

from edutwin_modeling.data.synthetic import (
    QUESTION_BANK_DEFAULT,
    _build_knowledge_lineage,
    _build_question_bank,
    _build_source_lineage,
    _load_question_bank,
)

NAMESPACE = UUID("a3e0f31f-54f6-4f99-82e8-bf5e6fdd55e4")


def test_student_lineage_hashes_source_identifiers_with_separate_domains() -> None:
    matches = pd.DataFrame(
        {
            "match_order": [1, 2],
            "split": ["train", "test"],
            "assistments_user_key": ["shared-source-key", "assist-b"],
            "oulad_student_key": ["shared-source-key", "oulad-b"],
            "distance": [0.1, 0.2],
            "assist_performance": [0.4, 0.5],
            "oulad_performance": [0.6, 0.7],
            "assist_activity": [1.0, 2.0],
            "oulad_activity": [1.2, 2.2],
            "assist_persistence": [0.3, 0.4],
            "oulad_persistence": [0.5, 0.6],
            "assist_performance_z": [-1.0, 0.5],
            "oulad_performance_z": [-0.5, 1.0],
            "assist_activity_z": [-0.8, 0.4],
            "oulad_activity_z": [-0.4, 0.8],
            "assist_persistence_z": [-0.6, 0.2],
            "oulad_persistence_z": [-0.2, 0.6],
        }
    )

    lineage = _build_source_lineage(matches, namespace=NAMESPACE, seed=42)

    assert list(lineage.columns) == [
        "student_id",
        "assistments_user_sha256",
        "oulad_student_sha256",
        "split",
        "performance_z",
        "activity_z",
        "persistence_z",
        "match_distance",
        "random_seed",
    ]
    expected_assistments = hashlib.sha256(
        b"edutwin-source-lineage-v1\x00assistments-user\x00shared-source-key"
    ).hexdigest()
    expected_oulad = hashlib.sha256(
        b"edutwin-source-lineage-v1\x00oulad-student\x00shared-source-key"
    ).hexdigest()
    assert lineage.iloc[0]["assistments_user_sha256"] == expected_assistments
    assert lineage.iloc[0]["oulad_student_sha256"] == expected_oulad
    assert expected_assistments != expected_oulad
    assert lineage["assistments_user_sha256"].str.fullmatch(r"[a-f0-9]{64}").all()
    assert lineage["oulad_student_sha256"].str.fullmatch(r"[a-f0-9]{64}").all()
    assert "shared-source-key" not in lineage.astype(str).to_csv(index=False)
    assert lineage["performance_z"].tolist() == [-0.75, 0.75]
    assert lineage["random_seed"].eq(42).all()


def test_knowledge_lineage_is_unique_and_train_bounded() -> None:
    question_bank = _load_question_bank(QUESTION_BANK_DEFAULT)
    skills, questions, _ = _build_question_bank(NAMESPACE, question_bank)
    event_rows: list[dict[str, object]] = []
    association_rows: list[dict[str, object]] = []
    for skill_number in range(1, 61):
        for problem_number in range(1, 11):
            order = (skill_number - 1) * 10 + problem_number
            event_rows.append(
                {
                    "order_id": order,
                    "problem_id": 100_000 + order,
                    "split": "train",
                }
            )
            association_rows.append(
                {"order_id": order, "skill_id": 10_000 + skill_number}
            )
    lineage = _build_knowledge_lineage(
        pd.DataFrame.from_records(event_rows),
        pd.DataFrame.from_records(association_rows),
        skills,
        questions,
        seed=42,
    )

    assert len(lineage) == 600
    assert lineage["knowledge_skill_id"].nunique() == 60
    assert lineage["source_skill_key"].nunique() == 60
    assert lineage["question_id"].is_unique
    assert lineage["source_problem_key"].is_unique
