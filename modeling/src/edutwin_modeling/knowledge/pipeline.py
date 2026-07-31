"""Knowledge candidate training, validation selection, and one-shot test finalization."""

from __future__ import annotations

import json
import math
import platform
from collections.abc import Mapping
from dataclasses import dataclass
from importlib import metadata
from pathlib import Path
from typing import Any

import pandas as pd

from edutwin_modeling.config import ProjectPaths, resolve_repository_path
from edutwin_modeling.errors import DataQualityError, ModelSelectionError
from edutwin_modeling.hashing import sha256_file, sha256_object
from edutwin_modeling.knowledge.data import (
    STUDENT_SPLIT_NAMESPACE,
    STUDENT_SPLIT_TEST_UPPER,
    STUDENT_SPLIT_TRAIN_UPPER,
    STUDENT_SPLIT_VALIDATION_UPPER,
    TRAIN_ROLE_NAMESPACE,
    KnowledgeVocabulary,
    PreparedKnowledgeData,
    expected_prediction_keys,
    prepare_knowledge_data,
)
from edutwin_modeling.knowledge.metrics import (
    CandidateMetrics,
    PlattCalibrator,
    PredictionResult,
    assert_unified_predictions,
    evaluate_predictions,
    select_next_correct_model,
)
from edutwin_modeling.knowledge.neural import NeuralKnowledgeModel
from edutwin_modeling.knowledge.statistical import BKTModel, IRTModel
from edutwin_modeling.manifest import (
    file_entry,
    repository_relative,
    write_canonical_json,
)

FAMILIES = ("IRT", "BKT", "DKT", "AKT")
DEPENDENCIES = (
    "numpy",
    "pandas",
    "pyarrow",
    "scikit-learn",
    "scipy",
    "torch",
)


def _section(config: Mapping[str, Any], name: str) -> Mapping[str, Any]:
    value = config.get(name)
    if not isinstance(value, Mapping):
        raise DataQualityError(f"knowledge configuration section {name!r} must be an object")
    return value


def _path(
    config: Mapping[str, Any], name: str, paths: ProjectPaths
) -> Path:
    value = config.get(name)
    if not isinstance(value, str) or not value:
        raise DataQualityError(f"knowledge paths.{name} must be a non-empty string")
    return resolve_repository_path(value, paths)


@dataclass(frozen=True)
class KnowledgeSettings:
    seed: int
    feature_contract_version: str
    answer_events: Path
    answer_event_skills: Path
    quality_report: Path
    artifacts_root: Path
    freeze_manifest: Path
    test_state: Path
    test_metrics: Path
    test_lock: Path
    calibration_fraction: float
    max_sequence_length: int
    training_device: str
    ece_bins: int
    auc_tolerance: float
    latency_warmup_repeats: int
    latency_timed_repeats: int
    latency_sample_events: int
    evaluation_batch_size: int
    model_configs: dict[str, dict[str, Any]]


def _settings(
    config: Mapping[str, Any], paths: ProjectPaths
) -> KnowledgeSettings:
    if int(config.get("schema_version", -1)) != 1:
        raise DataQualityError("knowledge configuration schema_version must be 1")
    seed = int(config.get("seed", -1))
    if seed != 42:
        raise DataQualityError("knowledge training seed must remain 42")
    feature_contract = config.get("feature_contract_version")
    if not isinstance(feature_contract, str) or not feature_contract:
        raise DataQualityError("knowledge feature_contract_version is required")
    paths_config = _section(config, "paths")
    data_config = _section(config, "data")
    training_config = _section(config, "training")
    evaluation_config = _section(config, "evaluation")
    models_config = _section(config, "models")
    fixed_data_contract = {
        "fit_split": "train",
        "selection_split": "validation",
        "final_split": "test",
        "split_algorithm": "sha256-u64-mod-10000-v1",
        "split_namespace": STUDENT_SPLIT_NAMESPACE,
        "train_upper_exclusive": STUDENT_SPLIT_TRAIN_UPPER,
        "validation_upper_exclusive": STUDENT_SPLIT_VALIDATION_UPPER,
        "test_upper_exclusive": STUDENT_SPLIT_TEST_UPPER,
        "calibration_namespace": TRAIN_ROLE_NAMESPACE,
        "evaluation_target": "post_initial_events",
    }
    for key, expected in fixed_data_contract.items():
        if data_config.get(key) != expected:
            raise DataQualityError(f"knowledge data.{key} must remain {expected!r}")
    calibration_fraction = float(data_config.get("calibration_fraction", -1.0))
    max_sequence_length = int(data_config.get("max_sequence_length", -1))
    training_device = str(training_config.get("device", ""))
    ece_bins = int(evaluation_config.get("ece_bins", -1))
    auc_tolerance = float(evaluation_config.get("auc_tolerance", -1.0))
    warmup = int(evaluation_config.get("latency_warmup_repeats", -1))
    timed = int(evaluation_config.get("latency_timed_repeats", -1))
    latency_sample_events = int(evaluation_config.get("latency_sample_events", -1))
    evaluation_batch_size = int(evaluation_config.get("batch_size", -1))
    if ece_bins != 15 or auc_tolerance != 0.005:
        raise DataQualityError("knowledge evaluation must use ECE-15 and AUC tolerance 0.005")
    if evaluation_config.get("device") != "cpu":
        raise DataQualityError("knowledge evaluation device must remain CPU")
    if training_device not in {"cpu", "cuda"}:
        raise DataQualityError("knowledge training device must be cpu or cuda")
    if (
        max_sequence_length < 2
        or warmup < 0
        or timed < 1
        or latency_sample_events != 2048
        or evaluation_batch_size != 64
    ):
        raise DataQualityError("knowledge sequence or latency configuration is invalid")
    model_configs: dict[str, dict[str, Any]] = {}
    if set(models_config) != set(FAMILIES):
        raise DataQualityError(f"knowledge models must be exactly {list(FAMILIES)}")
    for family in FAMILIES:
        value = models_config[family]
        if not isinstance(value, Mapping):
            raise DataQualityError(f"knowledge models.{family} must be an object")
        model_configs[family] = dict(value)
    return KnowledgeSettings(
        seed=seed,
        feature_contract_version=feature_contract,
        answer_events=_path(paths_config, "answer_events", paths),
        answer_event_skills=_path(paths_config, "answer_event_skills", paths),
        quality_report=_path(paths_config, "assistments_quality_report", paths),
        artifacts_root=_path(paths_config, "artifacts_root", paths),
        freeze_manifest=_path(paths_config, "freeze_manifest", paths),
        test_state=_path(paths_config, "test_state", paths),
        test_metrics=_path(paths_config, "test_metrics", paths),
        test_lock=_path(paths_config, "test_lock", paths),
        calibration_fraction=calibration_fraction,
        max_sequence_length=max_sequence_length,
        training_device=training_device,
        ece_bins=ece_bins,
        auc_tolerance=auc_tolerance,
        latency_warmup_repeats=warmup,
        latency_timed_repeats=timed,
        latency_sample_events=latency_sample_events,
        evaluation_batch_size=evaluation_batch_size,
        model_configs=model_configs,
    )


def _load_json(path: Path, *, context: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DataQualityError(f"failed to read {context}: {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise DataQualityError(f"{context} must contain a JSON object")
    return value


def _required_source_entries(
    settings: KnowledgeSettings, paths: ProjectPaths
) -> dict[str, dict[str, Any]]:
    sources = {
        "answerEvents": settings.answer_events,
        "answerEventSkills": settings.answer_event_skills,
        "assistmentsQualityReport": settings.quality_report,
    }
    missing = [str(path) for path in sources.values() if not path.is_file()]
    if missing:
        raise DataQualityError(f"knowledge training inputs are missing: {missing}")
    _validate_assistments_quality_report(settings)
    return {name: file_entry(path, paths) for name, path in sources.items()}


def _quality_mapping(
    value: Mapping[str, Any], name: str, *, context: str
) -> Mapping[str, Any]:
    nested = value.get(name)
    if not isinstance(nested, Mapping):
        raise DataQualityError(f"{context}.{name} must be an object")
    return nested


def _validate_assistments_quality_report(
    settings: KnowledgeSettings,
) -> dict[str, Any]:
    report = _load_json(
        settings.quality_report, context="ASSISTments knowledge quality report"
    )
    if report.get("schemaVersion") != 1 or report.get("dataset") != (
        "ASSISTments 2009-2010 Skill Builder corrected non-folded"
    ):
        raise DataQualityError("knowledge quality report dataset contract is invalid")
    expected = _quality_mapping(report, "expected", context="knowledge quality report")
    observed = _quality_mapping(report, "observed", context="knowledge quality report")
    checks = _quality_mapping(report, "checks", context="knowledge quality report")
    split = _quality_mapping(report, "split", context="knowledge quality report")
    outputs = _quality_mapping(report, "outputs", context="knowledge quality report")

    required_true_checks = (
        "exactColumnCount",
        "noUnnamedColumns",
        "sourceLockVerifiedBeforeProcessing",
            "canonicalLineageVerified",
            "canonicalHasNoExactDuplicates",
            "missingSkillsRepresentedExplicitly",
            "coreFieldsConsistentWithinOrder",
        "answerTextExcludedFromArtifacts",
    )
    failed = [name for name in required_true_checks if checks.get(name) is not True]
    if failed or checks.get("studentSplitLeakage") is not False:
        raise DataQualityError(
            "knowledge quality report contains failed checks: "
            f"{failed or ['studentSplitLeakage']}"
        )
    if int(split.get("seed", -1)) != 42 or split.get("namespace") != (
        STUDENT_SPLIT_NAMESPACE
    ):
        raise DataQualityError("knowledge quality report split contract changed")

    try:
        corrected_rows = int(expected["correctedRows"])
        expected_events = int(expected["uniqueOrderIds"])
        consistent_counts = (
            int(observed["correctedRows"]) == corrected_rows
            and int(observed["uniqueOrderIds"]) == expected_events
            and int(observed["answerEvents"]) == expected_events
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise DataQualityError(
            "knowledge quality report row counts are incomplete"
        ) from exc
    if corrected_rows < expected_events or expected_events < 1 or not consistent_counts:
        raise DataQualityError("knowledge quality report row counts are inconsistent")

    expected_output = {
        "answerEvents": settings.answer_events,
        "answerEventSkills": settings.answer_event_skills,
    }
    for name, path in expected_output.items():
        entry = _quality_mapping(outputs, name, context="knowledge quality report.outputs")
        if entry.get("file") != path.name or entry.get("sha256") != sha256_file(path):
            raise DataQualityError(
                f"knowledge quality report output hash changed: {name}"
            )
    return report


def _load_prepared_data(settings: KnowledgeSettings) -> PreparedKnowledgeData:
    report = _validate_assistments_quality_report(settings)
    events = pd.read_parquet(settings.answer_events)
    event_skills = pd.read_parquet(settings.answer_event_skills)
    observed = _quality_mapping(report, "observed", context="knowledge quality report")
    if len(events) != int(observed["answerEvents"]) or len(event_skills) != int(
        observed["answerEventSkills"]
    ):
        raise DataQualityError(
            "knowledge Parquet row counts do not match the quality report"
        )
    split = _quality_mapping(report, "split", context="knowledge quality report")
    split_counts = _quality_mapping(split, "counts", context="knowledge quality report.split")
    observed_split_counts = (
        events[["user_id", "split"]]
        .drop_duplicates("user_id")["split"]
        .value_counts(sort=False)
        .to_dict()
    )
    reported_split_counts = {str(key): int(value) for key, value in split_counts.items()}
    if observed_split_counts != reported_split_counts:
        raise DataQualityError(
            "knowledge student split counts do not match the quality report"
        )
    return prepare_knowledge_data(
        events,
        event_skills,
        calibration_fraction=settings.calibration_fraction,
        seed=settings.seed,
        verify_student_split=True,
    )


def _dependency_versions() -> dict[str, str]:
    versions = {"python": platform.python_version()}
    for distribution in DEPENDENCIES:
        try:
            versions[distribution] = metadata.version(distribution)
        except metadata.PackageNotFoundError as exc:
            raise DataQualityError(
                f"required knowledge dependency is not installed: {distribution}"
            ) from exc
    return versions


def _fit_candidate(
    family: str,
    data: PreparedKnowledgeData,
    settings: KnowledgeSettings,
) -> IRTModel | BKTModel | NeuralKnowledgeModel:
    fit_sequences = data.for_role("fit")
    model_config = settings.model_configs[family]
    if family == "IRT":
        return IRTModel.fit(
            fit_sequences, data.vocabulary, model_config, seed=settings.seed
        )
    if family == "BKT":
        return BKTModel.fit(
            fit_sequences, data.vocabulary, model_config, seed=settings.seed
        )
    return NeuralKnowledgeModel.fit(
        family,
        fit_sequences,
        data.vocabulary,
        model_config,
        max_sequence_length=settings.max_sequence_length,
        seed=settings.seed,
        training_device=settings.training_device,
    )


def _predict_candidate(
    model: IRTModel | BKTModel | NeuralKnowledgeModel,
    sequences: Any,
    settings: KnowledgeSettings,
) -> PredictionResult:
    common = {
        "warmup_repeats": settings.latency_warmup_repeats,
        "timed_repeats": settings.latency_timed_repeats,
    }
    if isinstance(model, NeuralKnowledgeModel):
        return model.predict(
            sequences,
            max_sequence_length=settings.max_sequence_length,
            evaluation_batch_size=settings.evaluation_batch_size,
            latency_sample_events=settings.latency_sample_events,
            **common,
        )
    return model.predict(sequences, **common)


def _model_configuration(
    model: IRTModel | BKTModel | NeuralKnowledgeModel,
    data: PreparedKnowledgeData,
) -> dict[str, Any]:
    if isinstance(model, NeuralKnowledgeModel):
        return model.model_config
    if isinstance(model, IRTModel):
        return {
            "problem_count": data.vocabulary.problem_count,
            "ability_prior_variance": model.ability_prior_variance,
        }
    return {"skill_count": data.vocabulary.skill_count, "masteryEstimator": True}


def _save_model(
    model: IRTModel | BKTModel | NeuralKnowledgeModel,
    destination: Path,
) -> None:
    model.save(destination)


def _training_log(
    model: IRTModel | BKTModel | NeuralKnowledgeModel,
) -> list[dict[str, float]]:
    return model.training_log


def _assert_prediction_contract(
    predictions: dict[str, PredictionResult],
    expected_keys: tuple[str, ...],
) -> None:
    assert_unified_predictions(predictions)
    if predictions["IRT"].event_keys != expected_keys:
        raise DataQualityError("knowledge prediction output violates event-order contract")


def _event_set_sha256(prediction: PredictionResult) -> str:
    prediction.validate()
    return sha256_object(
        {
            "schemaVersion": 1,
            "eventKeys": list(prediction.event_keys),
            "labels": [int(value) for value in prediction.labels],
        }
    )


@dataclass(frozen=True)
class _CandidateFiles:
    model: Path
    calibrator: Path
    training_log: Path
    manifest: Path


def train_knowledge_models(
    config: Mapping[str, Any], paths: ProjectPaths
) -> dict[str, Any]:
    """Train four candidates, select on validation, and seal test evaluation."""

    settings = _settings(config, paths)
    source_entries = _required_source_entries(settings, paths)
    config_sha256 = sha256_object(config)
    run_id = sha256_object(
        {
            "kind": "knowledge-training-run-v1",
            "seed": settings.seed,
            "configSha256": config_sha256,
            "sources": {
                name: entry["sha256"] for name, entry in sorted(source_entries.items())
            },
        }
    )[:16]
    run_directory = settings.artifacts_root / f"run-{run_id}"
    reserved = (
        run_directory,
        settings.freeze_manifest,
        settings.test_state,
        settings.test_metrics,
        settings.test_lock,
    )
    existing = [str(path) for path in reserved if path.exists()]
    if existing:
        raise DataQualityError(
            "knowledge training refuses to overwrite frozen or partial artifacts: "
            f"{existing}"
        )
    run_directory.mkdir(parents=True, exist_ok=False)
    data = _load_prepared_data(settings)
    config_artifact = run_directory / "training-config.json"
    vocabulary_artifact = run_directory / "vocabulary.json"
    write_canonical_json(dict(config), config_artifact)
    write_canonical_json(data.vocabulary.to_dict(), vocabulary_artifact)

    models: dict[str, IRTModel | BKTModel | NeuralKnowledgeModel] = {}
    files: dict[str, _CandidateFiles] = {}
    calibration_predictions: dict[str, PredictionResult] = {}
    validation_predictions: dict[str, PredictionResult] = {}
    for family in FAMILIES:
        candidate_directory = run_directory / family.lower()
        candidate_directory.mkdir()
        extension = "pt" if family in {"DKT", "AKT"} else "json"
        candidate_files = _CandidateFiles(
            model=candidate_directory / f"model.{extension}",
            calibrator=candidate_directory / "calibrator.json",
            training_log=candidate_directory / "training-log.json",
            manifest=candidate_directory / "manifest.json",
        )
        model = _fit_candidate(family, data, settings)
        _save_model(model, candidate_files.model)
        write_canonical_json(
            {
                "schemaVersion": 1,
                "family": family,
                "fitRole": "train-fit",
                "entries": _training_log(model),
            },
            candidate_files.training_log,
        )
        models[family] = model
        files[family] = candidate_files
        calibration_predictions[family] = _predict_candidate(
            model, data.for_role("calibration"), settings
        )
        validation_predictions[family] = _predict_candidate(
            model, data.for_role("validation"), settings
        )

    _assert_prediction_contract(
        calibration_predictions,
        expected_prediction_keys(data.for_role("calibration")),
    )
    _assert_prediction_contract(
        validation_predictions,
        expected_prediction_keys(data.for_role("validation")),
    )
    calibration_event_set_sha256 = _event_set_sha256(
        calibration_predictions["IRT"]
    )
    validation_event_set_sha256 = _event_set_sha256(validation_predictions["IRT"])
    calibrators: dict[str, PlattCalibrator] = {}
    validation_metrics: dict[str, CandidateMetrics] = {}
    for family in FAMILIES:
        calibration_prediction = calibration_predictions[family]
        calibrator = PlattCalibrator.fit(
            calibration_prediction.labels,
            calibration_prediction.probabilities,
            seed=settings.seed,
        )
        write_canonical_json(calibrator.to_dict(), files[family].calibrator)
        calibrators[family] = calibrator
        validation_metrics[family], _ = evaluate_predictions(
            validation_predictions[family],
            calibrator,
            ece_bins=settings.ece_bins,
        )

    decision = select_next_correct_model(
        validation_metrics, auc_tolerance=settings.auc_tolerance
    )
    candidate_references: dict[str, dict[str, Any]] = {}
    data_version = (
        "assist09-corrected-"
        f"{source_entries['assistmentsQualityReport']['sha256'][:12]}"
    )
    for family in FAMILIES:
        candidate_manifest: dict[str, Any] = {
            "schemaVersion": 1,
            "kind": "knowledge-candidate",
            "family": family,
            "seed": settings.seed,
            "status": "FROZEN_VALIDATION",
            "dataVersion": data_version,
            "featureContractVersion": settings.feature_contract_version,
            "trainingConfigSha256": config_sha256,
            "trainingRole": "train-fit",
            "calibrationRole": "train-calibration",
            "selectionSplit": "validation",
            "calibrationEventSetSha256": calibration_event_set_sha256,
            "validationEventSetSha256": validation_event_set_sha256,
            "testMetrics": None,
            "evaluationTarget": "post-initial-event",
            "modelConfig": _model_configuration(models[family], data),
            "validationMetrics": validation_metrics[family].to_dict(),
            "artifacts": {
                "model": file_entry(files[family].model, paths),
                "calibrator": file_entry(files[family].calibrator, paths),
                "trainingLog": file_entry(files[family].training_log, paths),
                "vocabulary": file_entry(vocabulary_artifact, paths),
            },
            "calibration": {
                "type": "platt-logit",
                "fitEventCount": calibrators[family].fit_event_count,
                "artifactSha256": sha256_file(files[family].calibrator),
            },
        }
        write_canonical_json(candidate_manifest, files[family].manifest)
        candidate_manifest_entry = file_entry(files[family].manifest, paths)
        stable_version_digest = sha256_object(
            {
                "schemaVersion": 1,
                "family": family,
                "seed": settings.seed,
                "dataVersion": data_version,
                "featureContractVersion": settings.feature_contract_version,
                "trainingConfigSha256": config_sha256,
                "modelArtifactSha256": sha256_file(files[family].model),
                "calibratorArtifactSha256": sha256_file(files[family].calibrator),
                "vocabularyArtifactSha256": sha256_file(vocabulary_artifact),
            }
        )
        candidate_version = f"{family.lower()}-assist09-seed42-{stable_version_digest[:12]}"
        candidate_references[family] = {
            "family": family,
            "modelVersion": candidate_version,
            "manifest": candidate_manifest_entry,
            "modelArtifactSha256": sha256_file(files[family].model),
            "calibratorVersion": f"sha256:{sha256_file(files[family].calibrator)}",
            "validationMetrics": validation_metrics[family].to_dict(),
        }

    freeze_manifest: dict[str, Any] = {
        "schemaVersion": 1,
        "kind": "knowledge-model-freeze",
        "status": "FROZEN",
        "seed": settings.seed,
        "runId": run_id,
        "dataVersion": data_version,
        "featureContractVersion": settings.feature_contract_version,
        "sources": source_entries,
        "trainingConfig": file_entry(config_artifact, paths),
        "trainingConfigSha256": config_sha256,
        "vocabulary": file_entry(vocabulary_artifact, paths),
        "dependencies": _dependency_versions(),
        "roles": data.role_counts(),
        "evaluationContract": {
            "level": "event",
            "target": "all-post-initial-events",
            "metrics": ["AUC", "LOG_LOSS", "ECE_15", "CPU_P95_MS"],
            "eceBins": settings.ece_bins,
            "cpuOnly": True,
            "latencySampling": {
                "algorithm": "sha256-event-key-lowest-v1",
                "maximumEvents": settings.latency_sample_events,
                "warmupRepeats": settings.latency_warmup_repeats,
                "timedRepeats": settings.latency_timed_repeats,
            },
            "validationEventSetSha256": validation_event_set_sha256,
        },
        "candidates": candidate_references,
        "selection": {
            "masteryEstimator": {
                **candidate_references["BKT"],
                "fixedByContract": True,
            },
            "nextCorrectPredictor": {
                **candidate_references[decision.winner],
                "selectedByValidation": True,
            },
            "nextCorrectDecision": decision.to_dict(),
        },
        "testEvaluation": {
            "statusAtFreeze": "SEALED",
            "statePath": repository_relative(settings.test_state, paths),
            "metricsPath": repository_relative(settings.test_metrics, paths),
            "finalizationCommand": (
                "python -m edutwin_modeling.knowledge finalize-test "
                "--config configs/knowledge/training.yaml"
            ),
        },
    }
    write_canonical_json(freeze_manifest, settings.freeze_manifest)
    freeze_entry = file_entry(settings.freeze_manifest, paths)
    write_canonical_json(
        {
            "schemaVersion": 1,
            "status": "SEALED",
            "freezeManifest": freeze_entry,
            "testMetricsPath": repository_relative(settings.test_metrics, paths),
            "testLockPath": repository_relative(settings.test_lock, paths),
            "attemptCount": 0,
        },
        settings.test_state,
    )
    return {
        "runId": run_id,
        "status": "FROZEN",
        "freezeManifest": freeze_entry,
        "testState": file_entry(settings.test_state, paths),
        "masteryModelVersion": candidate_references["BKT"]["modelVersion"],
        "nextCorrectModelVersion": candidate_references[decision.winner]["modelVersion"],
        "knowledgeCandidates": candidate_references,
    }


def _resolve_entry(entry: Mapping[str, Any], paths: ProjectPaths, *, context: str) -> Path:
    path_value = entry.get("path")
    expected_sha = entry.get("sha256")
    expected_bytes = entry.get("bytes")
    if not isinstance(path_value, str) or not isinstance(expected_sha, str):
        raise DataQualityError(f"{context} file entry is incomplete")
    resolved = (paths.repository_root / path_value).resolve()
    try:
        resolved.relative_to(paths.repository_root.resolve())
    except ValueError as exc:
        raise DataQualityError(f"{context} path escapes the repository") from exc
    if not resolved.is_file():
        raise DataQualityError(f"{context} artifact is missing: {resolved}")
    if expected_bytes is not None and resolved.stat().st_size != int(expected_bytes):
        raise DataQualityError(f"{context} artifact size changed: {resolved}")
    if sha256_file(resolved) != expected_sha:
        raise DataQualityError(f"{context} artifact hash changed: {resolved}")
    return resolved


def _load_frozen_candidate(
    family: str,
    reference: Mapping[str, Any],
    paths: ProjectPaths,
) -> tuple[IRTModel | BKTModel | NeuralKnowledgeModel, PlattCalibrator]:
    manifest_value = reference.get("manifest")
    if not isinstance(manifest_value, Mapping):
        raise DataQualityError(f"knowledge {family} manifest reference is missing")
    manifest_path = _resolve_entry(
        manifest_value, paths, context=f"knowledge {family} manifest"
    )
    manifest = _load_json(manifest_path, context=f"knowledge {family} manifest")
    if manifest.get("family") != family or manifest.get("testMetrics") is not None:
        raise DataQualityError(f"knowledge {family} candidate freeze is invalid")
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, Mapping):
        raise DataQualityError(f"knowledge {family} artifacts are missing")
    model_entry = artifacts.get("model")
    calibrator_entry = artifacts.get("calibrator")
    if not isinstance(model_entry, Mapping) or not isinstance(calibrator_entry, Mapping):
        raise DataQualityError(f"knowledge {family} model or calibrator entry is missing")
    model_path = _resolve_entry(model_entry, paths, context=f"knowledge {family} model")
    calibrator_path = _resolve_entry(
        calibrator_entry, paths, context=f"knowledge {family} calibrator"
    )
    model_config = manifest.get("modelConfig")
    if not isinstance(model_config, dict):
        raise DataQualityError(f"knowledge {family} model configuration is missing")
    if family == "IRT":
        model: IRTModel | BKTModel | NeuralKnowledgeModel = IRTModel.load(model_path)
    elif family == "BKT":
        model = BKTModel.load(model_path)
    else:
        model = NeuralKnowledgeModel.load(family, model_path, model_config)
    calibrator = PlattCalibrator.from_dict(
        _load_json(calibrator_path, context=f"knowledge {family} calibrator")
    )
    return model, calibrator


def _verify_frozen_sources(
    freeze: Mapping[str, Any],
    current: Mapping[str, Mapping[str, Any]],
) -> None:
    frozen = freeze.get("sources")
    if not isinstance(frozen, Mapping):
        raise DataQualityError("knowledge freeze source entries are missing")
    for name, current_entry in current.items():
        frozen_entry = frozen.get(name)
        if not isinstance(frozen_entry, Mapping):
            raise DataQualityError(f"knowledge freeze source {name!r} is missing")
        if frozen_entry.get("sha256") != current_entry.get("sha256"):
            raise DataQualityError(f"knowledge freeze source {name!r} hash changed")


def finalize_knowledge_test(
    config: Mapping[str, Any], paths: ProjectPaths
) -> dict[str, Any]:
    """Evaluate every frozen candidate on test exactly once after validation selection."""

    settings = _settings(config, paths)
    if not settings.freeze_manifest.is_file() or not settings.test_state.is_file():
        raise ModelSelectionError("knowledge test cannot run before model freeze")
    state = _load_json(settings.test_state, context="knowledge test state")
    if state.get("status") != "SEALED" or int(state.get("attemptCount", -1)) != 0:
        raise ModelSelectionError(
            f"knowledge test evaluation is not sealed: {state.get('status')!r}"
        )
    if settings.test_lock.exists() or settings.test_metrics.exists():
        raise ModelSelectionError("knowledge test evaluation was already claimed")
    freeze = _load_json(settings.freeze_manifest, context="knowledge freeze manifest")
    freeze_sha256 = sha256_file(settings.freeze_manifest)
    state_freeze = state.get("freezeManifest")
    if not isinstance(state_freeze, Mapping) or state_freeze.get("sha256") != freeze_sha256:
        raise ModelSelectionError("knowledge test state does not match the freeze manifest")
    if freeze.get("trainingConfigSha256") != sha256_object(config):
        raise ModelSelectionError("knowledge training configuration changed after freeze")
    current_sources = _required_source_entries(settings, paths)
    _verify_frozen_sources(freeze, current_sources)

    vocabulary_entry = freeze.get("vocabulary")
    if not isinstance(vocabulary_entry, Mapping):
        raise DataQualityError("knowledge frozen vocabulary reference is missing")
    vocabulary_path = _resolve_entry(
        vocabulary_entry, paths, context="knowledge vocabulary"
    )
    frozen_vocabulary = KnowledgeVocabulary.from_dict(
        _load_json(vocabulary_path, context="knowledge vocabulary")
    )
    data = _load_prepared_data(settings)
    if data.vocabulary != frozen_vocabulary:
        raise DataQualityError("knowledge train vocabulary changed after freeze")
    candidates = freeze.get("candidates")
    if not isinstance(candidates, Mapping) or set(candidates) != set(FAMILIES):
        raise DataQualityError("knowledge freeze does not contain four candidates")
    loaded: dict[
        str, tuple[IRTModel | BKTModel | NeuralKnowledgeModel, PlattCalibrator]
    ] = {}
    for family in FAMILIES:
        reference = candidates[family]
        if not isinstance(reference, Mapping):
            raise DataQualityError(f"knowledge candidate {family} reference is invalid")
        loaded[family] = _load_frozen_candidate(family, reference, paths)

    settings.test_lock.parent.mkdir(parents=True, exist_ok=True)
    with settings.test_lock.open("x", encoding="utf-8", newline="\n") as handle:
        handle.write(f"freeze_sha256={freeze_sha256}\nattempt=1\n")
    claimed_state = {
        **state,
        "status": "RUNNING",
        "attemptCount": 1,
        "freezeManifestSha256": freeze_sha256,
    }
    write_canonical_json(claimed_state, settings.test_state)
    try:
        test_predictions = {
            family: _predict_candidate(model, data.for_role("test"), settings)
            for family, (model, _) in loaded.items()
        }
        _assert_prediction_contract(
            test_predictions,
            expected_prediction_keys(data.for_role("test")),
        )
        test_event_set_sha256 = _event_set_sha256(test_predictions["IRT"])
        test_metrics: dict[str, CandidateMetrics] = {}
        for family in FAMILIES:
            test_metrics[family], _ = evaluate_predictions(
                test_predictions[family],
                loaded[family][1],
                ece_bins=settings.ece_bins,
            )
        metrics_manifest: dict[str, Any] = {
            "schemaVersion": 1,
            "kind": "knowledge-test-metrics",
            "status": "COMPLETED",
            "freezeManifestSha256": freeze_sha256,
            "split": "test",
            "evaluationTarget": "all-post-initial-events",
            "evaluatedOnce": True,
            "eventSetSha256": test_event_set_sha256,
            "candidates": {
                family: {
                    "modelVersion": candidates[family]["modelVersion"],
                    "metrics": test_metrics[family].to_dict(),
                }
                for family in FAMILIES
            },
        }
        write_canonical_json(metrics_manifest, settings.test_metrics)
        metrics_entry = file_entry(settings.test_metrics, paths)
        write_canonical_json(
            {
                **claimed_state,
                "status": "COMPLETED",
                "testMetrics": metrics_entry,
            },
            settings.test_state,
        )
        return {
            "status": "COMPLETED",
            "evaluatedOnce": True,
            "freezeManifestSha256": freeze_sha256,
            "testMetrics": metrics_entry,
            "candidates": {
                family: test_metrics[family].to_dict() for family in FAMILIES
            },
        }
    except Exception as exc:
        write_canonical_json(
            {
                **claimed_state,
                "status": "FAILED",
                "errorType": type(exc).__name__,
                "error": str(exc),
            },
            settings.test_state,
        )
        raise


def verify_knowledge_delivery(
    config: Mapping[str, Any], paths: ProjectPaths
) -> dict[str, Any]:
    """Verify frozen artifacts, serving roles, and the completed one-shot ledger."""

    settings = _settings(config, paths)
    if not settings.freeze_manifest.is_file() or not settings.test_state.is_file():
        raise ModelSelectionError("knowledge delivery has no frozen lifecycle state")
    freeze = _load_json(settings.freeze_manifest, context="knowledge freeze manifest")
    freeze_sha256 = sha256_file(settings.freeze_manifest)
    if freeze.get("status") != "FROZEN" or freeze.get("seed") != settings.seed:
        raise DataQualityError("knowledge freeze manifest status or seed is invalid")
    if freeze.get("trainingConfigSha256") != sha256_object(config):
        raise DataQualityError("knowledge frozen training configuration changed")
    current_sources = _required_source_entries(settings, paths)
    _verify_frozen_sources(freeze, current_sources)

    candidates = freeze.get("candidates")
    if not isinstance(candidates, Mapping) or set(candidates) != set(FAMILIES):
        raise DataQualityError("knowledge freeze does not contain all four candidates")
    loaded: dict[str, IRTModel | BKTModel | NeuralKnowledgeModel] = {}
    candidate_summary: dict[str, Any] = {}
    for family in FAMILIES:
        reference = candidates[family]
        if not isinstance(reference, Mapping):
            raise DataQualityError(f"knowledge candidate {family} reference is invalid")
        model, calibrator = _load_frozen_candidate(family, reference, paths)
        loaded[family] = model
        metrics_value = reference.get("validationMetrics")
        if not isinstance(metrics_value, dict):
            raise DataQualityError(f"knowledge {family} validation metrics are missing")
        metrics = CandidateMetrics.from_dict(metrics_value)
        if metrics.event_count < 1:
            raise DataQualityError(f"knowledge {family} validation event count is empty")
        numeric = (metrics.auc, metrics.log_loss, metrics.ece_15, metrics.cpu_p95_ms)
        if not all(math.isfinite(value) for value in numeric):
            raise DataQualityError(f"knowledge {family} validation metrics are invalid")
        candidate_summary[family] = {
            "modelVersion": reference.get("modelVersion"),
            "modelArtifactSha256": reference.get("modelArtifactSha256"),
            "calibratorVersion": reference.get("calibratorVersion"),
            "calibrationFitEventCount": calibrator.fit_event_count,
            "validationMetrics": metrics.to_dict(),
        }

    selection = freeze.get("selection")
    if not isinstance(selection, Mapping):
        raise DataQualityError("knowledge freeze selection is missing")
    mastery = selection.get("masteryEstimator")
    next_correct = selection.get("nextCorrectPredictor")
    if (
        not isinstance(mastery, Mapping)
        or mastery.get("family") != "BKT"
        or not isinstance(loaded["BKT"], BKTModel)
    ):
        raise DataQualityError("knowledge mastery role is not a loadable BKT artifact")
    if (
        not isinstance(next_correct, Mapping)
        or next_correct.get("family") not in {"DKT", "AKT"}
        or not isinstance(loaded[str(next_correct.get("family"))], NeuralKnowledgeModel)
    ):
        raise DataQualityError(
            "knowledge next-correct role is not a loadable DKT or AKT artifact"
        )

    state = _load_json(settings.test_state, context="knowledge test state")
    if state.get("status") != "COMPLETED" or int(state.get("attemptCount", -1)) != 1:
        raise ModelSelectionError(
            "knowledge test evaluation has not completed exactly once"
        )
    state_freeze = state.get("freezeManifest")
    metrics_entry = state.get("testMetrics")
    if not isinstance(state_freeze, Mapping) or state_freeze.get("sha256") != freeze_sha256:
        raise DataQualityError("knowledge test state freeze hash is invalid")
    if not isinstance(metrics_entry, Mapping):
        raise DataQualityError("knowledge test metrics reference is missing")
    metrics_path = _resolve_entry(
        metrics_entry, paths, context="knowledge test metrics"
    )
    metrics_manifest = _load_json(metrics_path, context="knowledge test metrics")
    if (
        metrics_manifest.get("status") != "COMPLETED"
        or metrics_manifest.get("evaluatedOnce") is not True
        or metrics_manifest.get("freezeManifestSha256") != freeze_sha256
    ):
        raise DataQualityError("knowledge test metrics lifecycle contract is invalid")
    if not settings.test_lock.is_file():
        raise DataQualityError("knowledge one-shot test claim lock is missing")
    lock_lines = settings.test_lock.read_text(encoding="utf-8").splitlines()
    if lock_lines != [f"freeze_sha256={freeze_sha256}", "attempt=1"]:
        raise DataQualityError("knowledge one-shot test claim lock is invalid")
    test_candidates = metrics_manifest.get("candidates")
    if not isinstance(test_candidates, Mapping) or set(test_candidates) != set(FAMILIES):
        raise DataQualityError("knowledge test metrics do not contain four candidates")
    for family in FAMILIES:
        test_candidate = test_candidates[family]
        if not isinstance(test_candidate, Mapping):
            raise DataQualityError(f"knowledge test candidate {family} is invalid")
        if test_candidate.get("modelVersion") != candidates[family].get("modelVersion"):
            raise DataQualityError(
                f"knowledge test model version changed for {family}"
            )
        test_metrics_value = test_candidate.get("metrics")
        if not isinstance(test_metrics_value, dict):
            raise DataQualityError(f"knowledge test metrics are missing for {family}")
        if CandidateMetrics.from_dict(test_metrics_value).event_count < 1:
            raise DataQualityError(f"knowledge test event count is empty for {family}")

    return {
        "schemaVersion": 1,
        "status": "VERIFIED",
        "freezeManifestSha256": freeze_sha256,
        "dataVersion": freeze.get("dataVersion"),
        "featureContractVersion": freeze.get("featureContractVersion"),
        "masteryModelVersion": mastery.get("modelVersion"),
        "nextCorrectModelVersion": next_correct.get("modelVersion"),
        "candidates": candidate_summary,
        "testEvaluation": {
            "status": state["status"],
            "attemptCount": state["attemptCount"],
            "metrics": file_entry(metrics_path, paths),
        },
    }
