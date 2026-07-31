"""Leakage-safe event sequences and train-fitted knowledge vocabularies."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, cast

import numpy as np
import pandas as pd

from edutwin_modeling.data.quality import require_columns, require_unique
from edutwin_modeling.data.splitting import split_name
from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.hashing import stable_rank

PAD_TOKEN = "<PAD>"
UNKNOWN_TOKEN = "<UNKNOWN>"
ALLOWED_SPLITS = frozenset({"train", "validation", "test"})
TRAIN_ROLE_NAMESPACE = "edutwin-knowledge-calibration-students-v1"
STUDENT_SPLIT_NAMESPACE = "edutwin-student-split-v1"
STUDENT_SPLIT_TRAIN_UPPER = 7_000
STUDENT_SPLIT_VALIDATION_UPPER = 8_500
STUDENT_SPLIT_TEST_UPPER = 10_000


def _source_key(value: object, *, field: str) -> str:
    if pd.isna(cast(Any, value)):
        raise DataQualityError(f"{field} contains null source keys")
    if isinstance(value, int | np.integer):
        result = str(int(value))
    elif isinstance(value, float | np.floating) and float(value).is_integer():
        result = str(int(value))
    else:
        result = str(value).strip()
    if not result:
        raise DataQualityError(f"{field} contains blank source keys")
    return result


@dataclass(frozen=True)
class KnowledgeVocabulary:
    """Train-side problem and skill mappings with fixed padding and unknown entries."""

    problem_to_index: dict[str, int]
    skill_to_index: dict[str, int]

    @property
    def problem_count(self) -> int:
        return len(self.problem_to_index)

    @property
    def skill_count(self) -> int:
        return len(self.skill_to_index)

    def problem_index(self, source_key: str) -> int:
        return self.problem_to_index.get(source_key, self.problem_to_index[UNKNOWN_TOKEN])

    def skill_index(self, source_key: str) -> int:
        return self.skill_to_index.get(source_key, self.skill_to_index[UNKNOWN_TOKEN])

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": 1,
            "fitSplit": "train",
            "paddingToken": PAD_TOKEN,
            "unknownToken": UNKNOWN_TOKEN,
            "problems": self.problem_to_index,
            "skills": self.skill_to_index,
        }

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> KnowledgeVocabulary:
        problems = value.get("problems")
        skills = value.get("skills")
        if not isinstance(problems, dict) or not isinstance(skills, dict):
            raise DataQualityError("knowledge vocabulary mappings are missing")
        problem_mapping = {str(key): int(index) for key, index in problems.items()}
        skill_mapping = {str(key): int(index) for key, index in skills.items()}
        for name, mapping in (("problems", problem_mapping), ("skills", skill_mapping)):
            if mapping.get(PAD_TOKEN) != 0 or mapping.get(UNKNOWN_TOKEN) != 1:
                raise DataQualityError(f"knowledge vocabulary {name} reserved indices changed")
            if sorted(mapping.values()) != list(range(len(mapping))):
                raise DataQualityError(f"knowledge vocabulary {name} indices are not contiguous")
        return cls(problem_to_index=problem_mapping, skill_to_index=skill_mapping)


@dataclass(frozen=True)
class KnowledgeSequence:
    """One student's ordered interactions for a single immutable data split and role."""

    student_key: str
    split: str
    role: str
    event_keys: tuple[str, ...]
    problem_indices: tuple[int, ...]
    skill_indices: tuple[tuple[int, ...], ...]
    correct: tuple[int, ...]

    def __post_init__(self) -> None:
        lengths = {
            len(self.event_keys),
            len(self.problem_indices),
            len(self.skill_indices),
            len(self.correct),
        }
        if len(lengths) != 1:
            raise DataQualityError("knowledge sequence arrays have different lengths")
        if any(not skills for skills in self.skill_indices):
            raise DataQualityError("knowledge sequence event has no skills")


@dataclass(frozen=True)
class SequenceWindow:
    """Contiguous sequence window whose first event is context-only."""

    event_keys: tuple[str, ...]
    problem_indices: tuple[int, ...]
    skill_indices: tuple[tuple[int, ...], ...]
    correct: tuple[int, ...]


@dataclass(frozen=True)
class PreparedKnowledgeData:
    """Validated sequences plus the vocabulary fitted exclusively on train rows."""

    vocabulary: KnowledgeVocabulary
    sequences: tuple[KnowledgeSequence, ...]
    event_count: int

    def for_role(self, role: str) -> tuple[KnowledgeSequence, ...]:
        return tuple(sequence for sequence in self.sequences if sequence.role == role)

    def role_counts(self) -> dict[str, dict[str, int]]:
        roles = ("fit", "calibration", "validation", "test")
        return {
            role: {
                "students": len(self.for_role(role)),
                "events": sum(len(sequence.event_keys) for sequence in self.for_role(role)),
                "evaluationEvents": sum(
                    max(0, len(sequence.event_keys) - 1) for sequence in self.for_role(role)
                ),
            }
            for role in roles
        }


def _validate_events(events: pd.DataFrame) -> pd.DataFrame:
    required = [
        "order_id",
        "user_id",
        "problem_id",
        "correct",
        "event_sequence",
        "split",
    ]
    require_columns(events, required, "knowledge answer events")
    result = events[required].copy()
    result["order_id"] = result["order_id"].map(
        lambda value: _source_key(value, field="knowledge answer_events.order_id")
    )
    result["user_id"] = result["user_id"].map(
        lambda value: _source_key(value, field="knowledge answer_events.user_id")
    )
    result["problem_id"] = result["problem_id"].map(
        lambda value: _source_key(value, field="knowledge answer_events.problem_id")
    )
    result["split"] = result["split"].astype(str).str.strip()
    unknown_splits = sorted(set(result["split"]).difference(ALLOWED_SPLITS))
    if unknown_splits:
        raise DataQualityError(f"knowledge answer events contain unknown splits: {unknown_splits}")

    correct = pd.to_numeric(result["correct"], errors="coerce")
    if correct.isna().any() or not correct.isin([0, 1]).all():
        raise DataQualityError("knowledge answer_events.correct must be binary")
    result["correct"] = correct.astype("int8")
    sequence = pd.to_numeric(result["event_sequence"], errors="coerce")
    if (
        sequence.isna().any()
        or not np.equal(sequence, np.floor(sequence)).all()
        or sequence.lt(1).any()
    ):
        raise DataQualityError("knowledge answer_events.event_sequence must be positive integers")
    result["event_sequence"] = sequence.astype("int64")
    require_unique(result, ["order_id"], "knowledge answer events")
    require_unique(result, ["user_id", "event_sequence"], "knowledge event sequence")
    split_counts = result.groupby("user_id", sort=True)["split"].nunique()
    leaking = split_counts.loc[split_counts.gt(1)]
    if not leaking.empty:
        raise DataQualityError(
            "knowledge students span data splits: "
            f"{leaking.index.astype(str).tolist()[:10]}"
        )
    return result.sort_values(
        ["user_id", "event_sequence", "order_id"], kind="mergesort", ignore_index=True
    )


def _assert_stable_student_splits(events: pd.DataFrame, *, seed: int) -> None:
    if seed != 42:
        raise DataQualityError("knowledge student split seed must remain 42")
    students = events[["user_id", "split"]].drop_duplicates("user_id")
    expected = students["user_id"].map(
        lambda student: split_name(
            str(student),
            namespace=STUDENT_SPLIT_NAMESPACE,
            seed=seed,
            train_upper_exclusive=STUDENT_SPLIT_TRAIN_UPPER,
            validation_upper_exclusive=STUDENT_SPLIT_VALIDATION_UPPER,
            test_upper_exclusive=STUDENT_SPLIT_TEST_UPPER,
        )
    )
    mismatch = students["split"].ne(expected)
    if mismatch.any():
        sample = [
            {
                "student": str(student),
                "observed": str(observed),
                "expected": str(expected_split),
            }
            for student, observed, expected_split in zip(
                students.loc[mismatch, "user_id"].head(10),
                students.loc[mismatch, "split"].head(10),
                expected.loc[mismatch].head(10),
                strict=True,
            )
        ]
        raise DataQualityError(
            "knowledge student split does not match the fixed SHA-256 contract: "
            f"{sample}"
        )


def _validate_skills(skills: pd.DataFrame, event_keys: set[str]) -> pd.DataFrame:
    required = ["order_id", "skill_id", "ordinal"]
    require_columns(skills, required, "knowledge event skills")
    result = skills[required].copy()
    result["order_id"] = result["order_id"].map(
        lambda value: _source_key(value, field="knowledge answer_event_skills.order_id")
    )
    result["skill_id"] = result["skill_id"].map(
        lambda value: _source_key(value, field="knowledge answer_event_skills.skill_id")
    )
    ordinal = pd.to_numeric(result["ordinal"], errors="coerce")
    if (
        ordinal.isna().any()
        or not np.equal(ordinal, np.floor(ordinal)).all()
        or ordinal.lt(1).any()
    ):
        raise DataQualityError(
            "knowledge answer_event_skills.ordinal must contain positive integers"
        )
    result["ordinal"] = ordinal.astype("int64")
    require_unique(result, ["order_id", "skill_id"], "knowledge event skills")
    require_unique(result, ["order_id", "ordinal"], "knowledge event skill ordinals")
    skill_event_keys = set(result["order_id"])
    orphaned = sorted(skill_event_keys.difference(event_keys))
    missing = sorted(event_keys.difference(skill_event_keys))
    if orphaned:
        raise DataQualityError(f"knowledge skills reference unknown events: {orphaned[:10]}")
    if missing:
        raise DataQualityError(f"knowledge events have no skill association: {missing[:10]}")
    result = result.sort_values(
        ["order_id", "ordinal", "skill_id"], kind="mergesort", ignore_index=True
    )
    expected_ordinal = result.groupby("order_id", sort=False).cumcount().add(1)
    invalid_ordinal = result["ordinal"].ne(expected_ordinal)
    if invalid_ordinal.any():
        invalid_events = (
            result.loc[invalid_ordinal, "order_id"].drop_duplicates().head(10).tolist()
        )
        raise DataQualityError(
            "knowledge event skill ordinals must be contiguous from one: "
            f"{invalid_events}"
        )
    return result


def _vocabulary(events: pd.DataFrame, skills: pd.DataFrame) -> KnowledgeVocabulary:
    train_events = events.loc[events["split"].eq("train")]
    if train_events.empty:
        raise DataQualityError("knowledge data has no train events")
    train_event_keys = set(train_events["order_id"])
    problems = sorted(set(train_events["problem_id"]))
    skill_keys = sorted(
        set(skills.loc[skills["order_id"].isin(train_event_keys), "skill_id"])
    )
    if not problems or not skill_keys:
        raise DataQualityError("knowledge train vocabulary has no problems or skills")
    return KnowledgeVocabulary(
        problem_to_index={
            key: index
            for index, key in enumerate([PAD_TOKEN, UNKNOWN_TOKEN, *problems])
        },
        skill_to_index={
            key: index
            for index, key in enumerate([PAD_TOKEN, UNKNOWN_TOKEN, *skill_keys])
        },
    )


def _calibration_students(
    train_students: list[str], *, fraction: float, seed: int
) -> set[str]:
    if seed != 42:
        raise DataQualityError("knowledge train-role seed must remain 42")
    if not 0.0 < fraction < 0.5:
        raise DataQualityError("knowledge calibration fraction must be between 0 and 0.5")
    if len(train_students) < 2:
        raise DataQualityError("knowledge training requires at least two train students")
    calibration_count = min(
        len(train_students) - 1,
        max(1, round(len(train_students) * fraction)),
    )
    ranked = sorted(
        train_students,
        key=lambda student: (
            stable_rank(student, TRAIN_ROLE_NAMESPACE, seed),
            student,
        ),
    )
    return set(ranked[:calibration_count])


def prepare_knowledge_data(
    events: pd.DataFrame,
    event_skills: pd.DataFrame,
    *,
    calibration_fraction: float = 0.15,
    seed: int = 42,
    verify_student_split: bool = False,
) -> PreparedKnowledgeData:
    """Validate processed ASSISTments frames and build leakage-safe sequences."""

    validated_events = _validate_events(events)
    if verify_student_split:
        _assert_stable_student_splits(validated_events, seed=seed)
    validated_skills = _validate_skills(
        event_skills, set(validated_events["order_id"])
    )
    vocabulary = _vocabulary(validated_events, validated_skills)
    train_students = sorted(
        validated_events.loc[validated_events["split"].eq("train"), "user_id"]
        .drop_duplicates()
        .tolist()
    )
    calibration_students = _calibration_students(
        train_students, fraction=calibration_fraction, seed=seed
    )
    skills_by_event = {
        str(order_id): tuple(
            vocabulary.skill_index(str(skill_id)) for skill_id in group["skill_id"]
        )
        for order_id, group in validated_skills.groupby("order_id", sort=False)
    }

    sequences: list[KnowledgeSequence] = []
    for student_key, group in validated_events.groupby("user_id", sort=True):
        split = str(group["split"].iloc[0])
        if split == "train":
            role = "calibration" if student_key in calibration_students else "fit"
        else:
            role = split
        event_keys = tuple(group["order_id"].astype(str))
        sequences.append(
            KnowledgeSequence(
                student_key=str(student_key),
                split=split,
                role=role,
                event_keys=event_keys,
                problem_indices=tuple(
                    vocabulary.problem_index(str(value)) for value in group["problem_id"]
                ),
                skill_indices=tuple(skills_by_event[event_key] for event_key in event_keys),
                correct=tuple(int(value) for value in group["correct"]),
            )
        )
    prepared = PreparedKnowledgeData(
        vocabulary=vocabulary,
        sequences=tuple(sequences),
        event_count=len(validated_events),
    )
    counts = prepared.role_counts()
    for role in ("fit", "calibration", "validation", "test"):
        if counts[role]["evaluationEvents"] < 1:
            raise DataQualityError(f"knowledge role {role!r} has no evaluable events")
    return prepared


def sequence_windows(
    sequences: tuple[KnowledgeSequence, ...], *, max_events: int
) -> tuple[SequenceWindow, ...]:
    """Create overlap-one windows so every post-initial event is predicted once."""

    if max_events < 2:
        raise DataQualityError("knowledge max sequence length must be at least 2")
    windows: list[SequenceWindow] = []
    step = max_events - 1
    for sequence in sequences:
        for start in range(0, max(0, len(sequence.event_keys) - 1), step):
            end = min(len(sequence.event_keys), start + max_events)
            if end - start < 2:
                continue
            windows.append(
                SequenceWindow(
                    event_keys=sequence.event_keys[start:end],
                    problem_indices=sequence.problem_indices[start:end],
                    skill_indices=sequence.skill_indices[start:end],
                    correct=sequence.correct[start:end],
                )
            )
    return tuple(windows)


def expected_prediction_keys(
    sequences: tuple[KnowledgeSequence, ...],
) -> tuple[str, ...]:
    """Return the unified event-level evaluation order for any candidate."""

    return tuple(
        event_key
        for sequence in sequences
        for event_key in sequence.event_keys[1:]
    )
