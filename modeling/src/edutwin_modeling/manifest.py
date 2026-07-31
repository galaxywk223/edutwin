"""Portable, deterministic artifact manifests."""

from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path
from typing import Any

from edutwin_modeling.config import ProjectPaths
from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.hashing import canonical_json_bytes, sha256_file


def repository_relative(path: Path, paths: ProjectPaths) -> str:
    resolved = path.resolve()
    try:
        return resolved.relative_to(paths.repository_root).as_posix()
    except ValueError as exc:
        raise DataQualityError(f"artifact is outside the repository: {resolved}") from exc


def file_entry(path: Path, paths: ProjectPaths) -> dict[str, Any]:
    if not path.is_file():
        raise DataQualityError(f"artifact does not exist: {path}")
    return {
        "path": repository_relative(path, paths),
        "bytes": path.stat().st_size,
        "sha256": sha256_file(path),
    }


def write_canonical_json(value: Mapping[str, Any], destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.tmp")
    if temporary.exists():
        temporary.unlink()
    try:
        temporary.write_bytes(canonical_json_bytes(value) + b"\n")
        temporary.replace(destination)
    finally:
        if temporary.exists():
            temporary.unlink()
