"""Reusable data quality assertions for non-skippable pipeline gates."""

from __future__ import annotations

from collections.abc import Iterable, Sequence

import pandas as pd

from edutwin_modeling.errors import DataQualityError, LeakageError


def require_columns(frame: pd.DataFrame, columns: Sequence[str], dataset: str) -> None:
    missing = sorted(set(columns).difference(frame.columns))
    if missing:
        raise DataQualityError(f"{dataset} is missing required columns: {missing}")


def require_exact_column_count(frame: pd.DataFrame, expected: int, dataset: str) -> None:
    if len(frame.columns) != expected:
        raise DataQualityError(
            f"{dataset} column count is {len(frame.columns)}; expected {expected}: "
            f"{frame.columns.tolist()}"
        )


def require_no_unnamed_columns(frame: pd.DataFrame, dataset: str) -> None:
    invalid = [column for column in frame.columns if str(column).lower().startswith("unnamed")]
    if invalid:
        raise DataQualityError(f"{dataset} contains index-like columns: {invalid}")


def require_binary(frame: pd.DataFrame, column: str, dataset: str) -> None:
    values = set(pd.to_numeric(frame[column], errors="coerce").dropna().unique().tolist())
    if not values.issubset({0, 1}):
        raise DataQualityError(f"{dataset}.{column} is not binary: {sorted(values)}")


def require_unique(frame: pd.DataFrame, columns: Sequence[str], dataset: str) -> None:
    duplicate_mask = frame.duplicated(list(columns), keep=False)
    if duplicate_mask.any():
        sample = frame.loc[duplicate_mask, list(columns)].head(10).to_dict("records")
        raise DataQualityError(f"{dataset} duplicate key {list(columns)}: {sample}")


def reject_forbidden_features(feature_names: Iterable[str], forbidden: Iterable[str]) -> None:
    normalized = {name.lower() for name in feature_names}
    violations = sorted({name for name in forbidden if name.lower() in normalized})
    if violations:
        raise LeakageError(f"forbidden future features present: {violations}")
