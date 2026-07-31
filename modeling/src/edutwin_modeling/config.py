"""Configuration loading with repository-relative path resolution."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from edutwin_modeling.errors import DataQualityError


@dataclass(frozen=True)
class ProjectPaths:
    repository_root: Path
    modeling_root: Path

    @classmethod
    def discover(cls, start: Path | None = None) -> ProjectPaths:
        current = (start or Path.cwd()).resolve()
        for candidate in (current, *current.parents):
            if (candidate / "modeling" / "pyproject.toml").is_file():
                return cls(repository_root=candidate, modeling_root=candidate / "modeling")
        raise DataQualityError("repository root containing modeling/pyproject.toml was not found")


def load_yaml(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        value = yaml.safe_load(handle)
    if not isinstance(value, dict):
        raise DataQualityError(f"configuration root must be an object: {path}")
    return value


def resolve_repository_path(value: str, paths: ProjectPaths) -> Path:
    candidate = Path(value)
    if candidate.is_absolute():
        return candidate.resolve()
    return (paths.modeling_root / candidate).resolve()
