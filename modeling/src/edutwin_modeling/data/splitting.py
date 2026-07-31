"""Student-level stable hashing for leakage-safe 70/15/15 partitions."""

from __future__ import annotations

from collections.abc import Iterable

import pandas as pd

from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.hashing import stable_bucket

SPLIT_ORDER = ("train", "validation", "test")


def split_name(
    student_key: str,
    *,
    namespace: str,
    seed: int,
    train_upper_exclusive: int = 7000,
    validation_upper_exclusive: int = 8500,
    test_upper_exclusive: int = 10_000,
) -> str:
    if not (0 < train_upper_exclusive < validation_upper_exclusive < test_upper_exclusive):
        raise ValueError("split thresholds must be strictly increasing")
    bucket = stable_bucket(student_key, namespace, seed, test_upper_exclusive)
    if bucket < train_upper_exclusive:
        return "train"
    if bucket < validation_upper_exclusive:
        return "validation"
    return "test"


def assign_student_splits(
    frame: pd.DataFrame,
    *,
    student_column: str,
    namespace: str,
    seed: int,
) -> pd.DataFrame:
    if student_column not in frame.columns:
        raise DataQualityError(f"missing student key column: {student_column}")
    result = frame.copy()
    result["split"] = result[student_column].astype(str).map(
        lambda value: split_name(value, namespace=namespace, seed=seed)
    )
    assert_no_student_leakage(result, student_column=student_column)
    return result


def assert_no_student_leakage(frame: pd.DataFrame, *, student_column: str) -> None:
    counts = frame.groupby(student_column, dropna=False)["split"].nunique(dropna=False)
    leaking = counts[counts != 1]
    if not leaking.empty:
        sample: Iterable[object] = leaking.index[:10]
        raise DataQualityError(f"students assigned to multiple splits: {list(sample)}")
