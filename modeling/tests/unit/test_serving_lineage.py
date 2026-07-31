"""Serving-only lineage artifact verification tests."""

from __future__ import annotations

import csv
import json
from pathlib import Path
from uuid import UUID

import pytest

from edutwin_modeling.config import ProjectPaths
from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.hashing import sha256_file
from edutwin_modeling.serving.lineage import KNOWLEDGE_HEADER, SourceLineageRegistry


def _write_lineage(root: Path) -> tuple[ProjectPaths, Path, Path]:
    generated = root / "data" / "demo" / "generated"
    database = generated / "database"
    database.mkdir(parents=True)
    knowledge_path = database / "knowledge_lineage.csv"
    with knowledge_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=KNOWLEDGE_HEADER, lineterminator="\n")
        writer.writeheader()
        for question_number in range(1, 601):
            skill_number = ((question_number - 1) // 10) + 1
            writer.writerow(
                {
                    "knowledge_skill_id": str(UUID(int=skill_number)),
                    "source_skill_key": f"source-skill-{skill_number:03d}",
                    "question_id": str(UUID(int=10_000 + question_number)),
                    "source_problem_key": f"source-problem-{question_number:04d}",
                }
            )
    manifest = {
        "schema_version": 7,
        "student_count": 2_000,
        "answer_event_count": 240_000,
        "database_import": {
            "schema_version": 3,
            "contains_source_behavior_rows": False,
            "outputs": {
                "source_lineage": {
                    "path": "database/source_lineage.csv",
                    "rows": 2_000,
                    "sha256": "a" * 64,
                },
                "knowledge_lineage": {
                    "path": "database/knowledge_lineage.csv",
                    "rows": 600,
                    "sha256": sha256_file(knowledge_path),
                },
            },
        },
    }
    manifest_path = generated / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=True, sort_keys=True),
        encoding="utf-8",
    )
    modeling_root = root / "modeling"
    modeling_root.mkdir()
    return (
        ProjectPaths(repository_root=root, modeling_root=modeling_root),
        manifest_path,
        knowledge_path,
    )


def test_serving_lineage_loads_only_verified_knowledge_mapping(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("EDUTWIN_LINEAGE_MANIFEST", raising=False)
    paths, manifest_path, knowledge_path = _write_lineage(tmp_path)

    registry = SourceLineageRegistry.load(paths)

    assert len(registry.skill_keys) == 60
    assert len(registry.problem_keys) == 600
    assert registry.manifest_sha256 == sha256_file(manifest_path)
    assert registry.knowledge_lineage_sha256 == sha256_file(knowledge_path)
    assert registry.skill_key(UUID(int=1)) == "source-skill-001"
    assert registry.problem_key(UUID(int=10_001)) == "source-problem-0001"
    assert not (manifest_path.parent / "database" / "source_lineage.csv").exists()


def test_serving_lineage_rejects_a_tampered_mapping(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("EDUTWIN_LINEAGE_MANIFEST", raising=False)
    paths, _, knowledge_path = _write_lineage(tmp_path)
    with knowledge_path.open("a", encoding="utf-8", newline="") as handle:
        handle.write("tampered\n")

    with pytest.raises(DataQualityError, match="hash differs"):
        SourceLineageRegistry.load(paths)
