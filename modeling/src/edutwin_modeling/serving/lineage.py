"""Verified UUID-to-source-key lineage for synthetic inference requests."""

from __future__ import annotations

import csv
import json
import os
from dataclasses import dataclass
from pathlib import Path
from uuid import UUID

from edutwin_modeling.config import ProjectPaths
from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.hashing import sha256_file

KNOWLEDGE_HEADER = (
    "knowledge_skill_id",
    "source_skill_key",
    "question_id",
    "source_problem_key",
)


@dataclass(frozen=True)
class SourceLineageRegistry:
    """Serving-safe knowledge mappings loaded from the deterministic demo manifest."""

    skill_keys: dict[str, str]
    problem_keys: dict[str, str]
    manifest_sha256: str
    knowledge_lineage_sha256: str

    @classmethod
    def load(cls, paths: ProjectPaths) -> SourceLineageRegistry:
        configured = os.environ.get("EDUTWIN_LINEAGE_MANIFEST")
        manifest_path = (
            Path(configured).resolve()
            if configured
            else (paths.repository_root / "data" / "demo" / "generated" / "manifest.json").resolve()
        )
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise DataQualityError(f"demo lineage manifest is unreadable: {manifest_path}") from exc
        database = manifest.get("database_import") if isinstance(manifest, dict) else None
        if (
            not isinstance(database, dict)
            or manifest.get("schema_version") != 7
            or manifest.get("student_count") != 2_000
            or not 150_000 <= int(manifest.get("answer_event_count", 0)) <= 360_000
            or database.get("schema_version") != 3
            or database.get("contains_source_behavior_rows") is not False
        ):
            raise DataQualityError("demo lineage manifest violates the frozen contract")
        outputs = database.get("outputs")
        if not isinstance(outputs, dict):
            raise DataQualityError("demo lineage outputs are missing")
        _output_metadata(outputs, "source_lineage", expected_rows=2_000)
        knowledge_path = _verified_output(
            manifest_path.parent, outputs, "knowledge_lineage", expected_rows=600
        )

        knowledge_rows = _read_csv(
            knowledge_path, KNOWLEDGE_HEADER, expected_rows=600
        )
        skill_keys: dict[str, str] = {}
        problem_keys: dict[str, str] = {}
        seen_source_skills: set[str] = set()
        seen_source_problems: set[str] = set()
        for row in knowledge_rows:
            skill_id = _uuid(row["knowledge_skill_id"], "knowledge skill UUID")
            question_id = _uuid(row["question_id"], "question UUID")
            source_skill = _text(row["source_skill_key"], "ASSISTments skill key")
            source_problem = _text(
                row["source_problem_key"], "ASSISTments problem key"
            )
            previous_skill = skill_keys.setdefault(skill_id, source_skill)
            if previous_skill != source_skill:
                raise DataQualityError("a knowledge skill maps to multiple source keys")
            if question_id in problem_keys or source_problem in seen_source_problems:
                raise DataQualityError("question source lineage is not one-to-one")
            problem_keys[question_id] = source_problem
            seen_source_problems.add(source_problem)
            seen_source_skills.add(source_skill)
        if len(skill_keys) != 60 or len(seen_source_skills) != 60:
            raise DataQualityError("knowledge source lineage must contain 60 unique skills")
        return cls(
            skill_keys=skill_keys,
            problem_keys=problem_keys,
            manifest_sha256=sha256_file(manifest_path),
            knowledge_lineage_sha256=sha256_file(knowledge_path),
        )

    def skill_key(self, skill_id: UUID | str) -> str:
        return self._lookup(self.skill_keys, skill_id, "skill")

    def problem_key(self, question_id: UUID | str) -> str:
        return self._lookup(self.problem_keys, question_id, "question")

    @staticmethod
    def _lookup(values: dict[str, str], identifier: UUID | str, kind: str) -> str:
        key = str(identifier)
        try:
            return values[key]
        except KeyError as exc:
            raise DataQualityError(f"synthetic {kind} UUID has no verified source lineage") from exc


def _verified_output(
    root: Path, outputs: dict[str, object], name: str, *, expected_rows: int
) -> Path:
    value, expected_sha = _output_metadata(outputs, name, expected_rows=expected_rows)
    path = (root / value).resolve()
    try:
        path.relative_to(root.resolve())
    except ValueError as exc:
        raise DataQualityError(f"demo lineage path escapes its root: {path}") from exc
    if not path.is_file() or sha256_file(path) != expected_sha:
        raise DataQualityError(f"demo lineage hash differs for {name}")
    return path


def _output_metadata(
    outputs: dict[str, object], name: str, *, expected_rows: int
) -> tuple[str, str]:
    entry = outputs.get(name)
    if not isinstance(entry, dict) or entry.get("rows") != expected_rows:
        raise DataQualityError(f"demo lineage output metadata differs for {name}")
    value = entry.get("path")
    expected_sha = entry.get("sha256")
    if (
        not isinstance(value, str)
        or not isinstance(expected_sha, str)
        or len(expected_sha) != 64
        or any(character not in "0123456789abcdef" for character in expected_sha)
    ):
        raise DataQualityError(f"demo lineage output metadata is incomplete for {name}")
    return value, expected_sha


def _read_csv(
    path: Path, expected_header: tuple[str, ...], *, expected_rows: int
) -> list[dict[str, str]]:
    try:
        with path.open("r", encoding="utf-8", newline="") as handle:
            reader = csv.DictReader(handle)
            if tuple(reader.fieldnames or ()) != expected_header:
                raise DataQualityError(f"demo lineage CSV header differs: {path}")
            rows = list(reader)
    except (OSError, csv.Error) as exc:
        raise DataQualityError(f"demo lineage CSV is unreadable: {path}") from exc
    if (
        len(rows) != expected_rows
        or any(None in row for row in rows)
        or any(value is None for row in rows for value in row.values())
    ):
        raise DataQualityError(f"demo lineage CSV row count or shape differs: {path}")
    return rows


def _uuid(value: str, context: str) -> str:
    try:
        return str(UUID(value))
    except ValueError as exc:
        raise DataQualityError(f"{context} is invalid") from exc


def _text(value: str, context: str) -> str:
    if not value or not value.strip():
        raise DataQualityError(f"{context} is blank")
    return value
