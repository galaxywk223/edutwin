from __future__ import annotations

import json
from pathlib import Path

import pytest

from edutwin_modeling.config import ProjectPaths
from edutwin_modeling.data.provenance import verify_source_lock
from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.hashing import sha256_file


def test_modified_locked_file_is_rejected(tmp_path: Path) -> None:
    modeling_root = tmp_path / "modeling"
    modeling_root.mkdir()
    (modeling_root / "pyproject.toml").write_text("[project]\nname='fixture'\n")
    source = tmp_path / "source.bin"
    source.write_bytes(b"locked")
    lock_path = tmp_path / "source-lock.json"
    entry = {
        "path": "source.bin",
        "bytes": source.stat().st_size,
        "sha256": sha256_file(source),
    }
    lock = {
        "schema_version": 2,
        "source_manifest": entry,
        "generator": entry,
        "assistments_2009_2010_skill_builder": {
            "official_raw_nonfolded": {
                "source_landing_url": "https://example.test/dataset",
                "download_url_env": "EDUTWIN_ASSISTMENTS_DOWNLOAD_URL",
                "file": entry,
            },
            "corrected_nonfolded_academic_mirror": {
                "archive": entry,
                "extracted": entry,
            },
            "canonical_corrected_nonfolded": {
                "file": entry,
                "official_rows": 525534,
                "official_repeated_lineage_rows": 123778,
                "official_opportunity_variant_rows": 123778,
                "rows": 401756,
                "mirror_rows": 401756,
                "unique_order_ids": 346860,
                "mirror_answer_text_mismatches": 83,
                "mirror_retained_opportunity_mismatches": 0,
                "canonical_semantic_sha256": "0" * 64,
                "semantic_hash_excludes": ["answer_text"],
                "lineage_key_excludes": [
                    "answer_text",
                    "opportunity",
                    "opportunity_original",
                ],
                "answer_text_source": "official_raw_nonfolded",
                "official_mirror_lineage_match": True,
                "retained_training_fields_match": True,
            },
        },
        "oulad": {
            "archive": {**entry, "md5": "7412686fd77cf0e0ee1e8c3e9b354308"},
            "license": "CC-BY-4.0",
            "dataset_doi": "10.6084/m9.figshare.5081998.v1",
            "extracted_files": [entry] * 7,
        },
        "deployment_boundary": {
            "raw_data_in_git": False,
            "raw_data_in_images": False,
            "raw_data_on_server": False,
        },
    }
    lock_path.write_text(json.dumps(lock), encoding="utf-8")
    source.write_bytes(b"changed")
    config = {"paths": {"source_lock": "../source-lock.json"}}
    paths = ProjectPaths(repository_root=tmp_path, modeling_root=modeling_root)
    with pytest.raises(DataQualityError, match="source size mismatch"):
        verify_source_lock(config, paths)
