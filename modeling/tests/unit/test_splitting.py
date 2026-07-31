from __future__ import annotations

import pandas as pd

from edutwin_modeling.data.splitting import assign_student_splits, split_name


def test_split_is_stable_and_uses_fixed_vocabulary() -> None:
    first = split_name("student-42", namespace="edutwin-student-split-v1", seed=42)
    second = split_name("student-42", namespace="edutwin-student-split-v1", seed=42)
    assert first == second
    assert first in {"train", "validation", "test"}


def test_all_rows_for_student_share_split() -> None:
    frame = pd.DataFrame({"student": ["a", "a", "b", "b", "c"]})
    result = assign_student_splits(
        frame,
        student_column="student",
        namespace="edutwin-student-split-v1",
        seed=42,
    )
    assert result.groupby("student")["split"].nunique().max() == 1
