from __future__ import annotations

import json
from pathlib import Path

import pandas as pd
import pytest

from edutwin_modeling.config import ProjectPaths
from edutwin_modeling.data.splitting import split_name
from edutwin_modeling.errors import ModelSelectionError
from edutwin_modeling.hashing import sha256_file
from edutwin_modeling.knowledge.data import STUDENT_SPLIT_NAMESPACE
from edutwin_modeling.knowledge.pipeline import (
    finalize_knowledge_test,
    train_knowledge_models,
    verify_knowledge_delivery,
)
from edutwin_modeling.knowledge.runtime import load_frozen_knowledge_runtime


def _frames() -> tuple[pd.DataFrame, pd.DataFrame]:
    event_rows: list[dict[str, object]] = []
    skill_rows: list[dict[str, object]] = []
    students: list[tuple[str, str]] = []
    for required_split, count in (("train", 12), ("validation", 4), ("test", 4)):
        index = 0
        while sum(split == required_split for _, split in students) < count:
            student = f"candidate-{required_split}-{index}"
            assigned = split_name(
                student,
                namespace=STUDENT_SPLIT_NAMESPACE,
                seed=42,
            )
            if assigned == required_split:
                students.append((student, assigned))
            index += 1
    for student_index, (student, split) in enumerate(students):
        for sequence in range(1, 6):
            order_id = f"{student}-{sequence}"
            event_rows.append(
                {
                    "order_id": order_id,
                    "user_id": student,
                    "problem_id": f"problem-{sequence % 4}",
                    "correct": (student_index + sequence) % 2,
                    "event_sequence": sequence,
                    "split": split,
                }
            )
            skill_rows.append(
                {
                    "order_id": order_id,
                    "skill_id": f"skill-{sequence % 2}",
                    "ordinal": 1,
                }
            )
            if sequence == 3:
                skill_rows.append(
                    {"order_id": order_id, "skill_id": "skill-extra", "ordinal": 2}
                )
    return pd.DataFrame(event_rows), pd.DataFrame(skill_rows)


def _config() -> dict[str, object]:
    return {
        "schema_version": 1,
        "seed": 42,
        "feature_contract_version": "assistments-knowledge-events-v1",
        "paths": {
            "answer_events": "data/answer_events.parquet",
            "answer_event_skills": "data/answer_event_skills.parquet",
            "assistments_quality_report": "data/quality_report.json",
            "artifacts_root": "artifacts/knowledge",
            "freeze_manifest": "artifacts/manifests/knowledge-freeze.json",
            "test_state": "artifacts/manifests/knowledge-test-state.json",
            "test_metrics": "artifacts/manifests/knowledge-test-metrics.json",
            "test_lock": "artifacts/manifests/knowledge-test-evaluation.lock",
        },
        "data": {
            "fit_split": "train",
            "selection_split": "validation",
            "final_split": "test",
            "split_algorithm": "sha256-u64-mod-10000-v1",
            "split_namespace": "edutwin-student-split-v1",
            "train_upper_exclusive": 7000,
            "validation_upper_exclusive": 8500,
            "test_upper_exclusive": 10000,
            "calibration_fraction": 0.25,
            "calibration_namespace": "edutwin-knowledge-calibration-students-v1",
            "max_sequence_length": 8,
            "evaluation_target": "post_initial_events",
        },
        "training": {"device": "cpu"},
        "evaluation": {
            "ece_bins": 15,
            "auc_tolerance": 0.005,
            "latency_warmup_repeats": 0,
            "latency_timed_repeats": 1,
            "latency_sample_events": 2048,
            "batch_size": 64,
            "device": "cpu",
        },
        "models": {
            "IRT": {
                "epochs": 2,
                "batch_size": 32,
                "learning_rate": 0.02,
                "regularization": 0.0001,
                "ability_prior_variance": 1.0,
            },
            "BKT": {
                "em_iterations": 3,
                "tolerance": 1e-8,
                "minimum_skill_observations": 1,
                "shrinkage": 1.0,
            },
            "DKT": {
                "epochs": 1,
                "batch_size": 4,
                "learning_rate": 0.005,
                "weight_decay": 0.0,
                "gradient_clip": 5.0,
                "embedding_size": 8,
                "hidden_size": 8,
                "layers": 1,
                "dropout": 0.0,
            },
            "AKT": {
                "epochs": 1,
                "batch_size": 4,
                "learning_rate": 0.005,
                "weight_decay": 0.0,
                "gradient_clip": 5.0,
                "embedding_size": 8,
                "hidden_size": 8,
                "heads": 2,
                "layers": 1,
                "feedforward_size": 16,
                "dropout": 0.0,
            },
        },
    }


def test_four_candidates_freeze_runtime_and_one_shot_test(tmp_path: Path) -> None:
    modeling_root = tmp_path / "modeling"
    data_directory = modeling_root / "data"
    data_directory.mkdir(parents=True)
    events, skills = _frames()
    events.to_parquet(data_directory / "answer_events.parquet", index=False)
    skills.to_parquet(data_directory / "answer_event_skills.parquet", index=False)
    event_path = data_directory / "answer_events.parquet"
    skill_path = data_directory / "answer_event_skills.parquet"
    student_counts = (
        events[["user_id", "split"]]
        .drop_duplicates("user_id")["split"]
        .value_counts(sort=False)
        .sort_index()
        .to_dict()
    )
    quality_report = {
        "schemaVersion": 1,
        "dataset": "ASSISTments 2009-2010 Skill Builder corrected non-folded",
        "expected": {
            "columns": 30,
            "correctedRows": len(events),
            "uniqueOrderIds": len(events),
        },
        "observed": {
            "correctedRows": len(events),
            "uniqueOrderIds": len(events),
            "answerEvents": len(events),
            "answerEventSkills": len(skills),
        },
            "checks": {
                "exactColumnCount": True,
                "noUnnamedColumns": True,
                "officialDedupMatchesMirror": True,
                "sourceLockVerifiedBeforeProcessing": True,
                "canonicalLineageVerified": True,
                "canonicalHasNoExactDuplicates": True,
                "missingSkillsRepresentedExplicitly": True,
                "coreFieldsConsistentWithinOrder": True,
            "studentSplitLeakage": False,
            "answerTextExcludedFromArtifacts": True,
        },
        "split": {
            "seed": 42,
            "namespace": STUDENT_SPLIT_NAMESPACE,
            "counts": student_counts,
        },
        "outputs": {
            "answerEvents": {
                "file": event_path.name,
                "rows": len(events),
                "sha256": sha256_file(event_path),
            },
            "answerEventSkills": {
                "file": skill_path.name,
                "rows": len(skills),
                "sha256": sha256_file(skill_path),
            },
        },
    }
    (data_directory / "quality_report.json").write_text(
        json.dumps(quality_report, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    paths = ProjectPaths(repository_root=tmp_path, modeling_root=modeling_root)
    config = _config()

    trained = train_knowledge_models(config, paths)
    assert set(trained["knowledgeCandidates"]) == {"IRT", "BKT", "DKT", "AKT"}
    freeze_path = modeling_root / "artifacts" / "manifests" / "knowledge-freeze.json"
    freeze = json.loads(freeze_path.read_text(encoding="utf-8"))
    assert freeze["selection"]["masteryEstimator"]["family"] == "BKT"
    assert freeze["selection"]["nextCorrectPredictor"]["family"] in {"DKT", "AKT"}
    assert all(
        candidate["validationMetrics"]["eventCount"] > 0
        for candidate in freeze["candidates"].values()
    )

    runtime = load_frozen_knowledge_runtime(freeze_path, paths)
    rollback_runtime = load_frozen_knowledge_runtime(
        freeze_path, paths, deployment_mode="rollback"
    )
    assert rollback_runtime.next_reference["family"] in {"DKT", "AKT"}
    assert (
        rollback_runtime.next_reference["family"]
        != runtime.next_reference["family"]
    )
    runtime_prediction = runtime.predict(
        (
            ("problem-1", ("skill-0",), 1),
            ("problem-2", ("skill-1",), 0),
        ),
        "problem-3",
        ("skill-0",),
    )
    assert 0.0 <= runtime_prediction.mastery["skill-0"] <= 1.0
    assert 0.0 <= runtime_prediction.next_correct_probability <= 1.0

    finalized = finalize_knowledge_test(config, paths)
    assert finalized["status"] == "COMPLETED"
    assert finalized["evaluatedOnce"] is True
    with pytest.raises(ModelSelectionError, match="not sealed"):
        finalize_knowledge_test(config, paths)
    verified = verify_knowledge_delivery(config, paths)
    assert verified["status"] == "VERIFIED"
    assert verified["testEvaluation"]["attemptCount"] == 1
