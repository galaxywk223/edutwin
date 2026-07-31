"""Verified frozen-artifact registry used by the inference application."""

from __future__ import annotations

import json
import os
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any, ClassVar

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from edutwin_modeling.config import ProjectPaths, load_yaml
from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.hashing import sha256_file
from edutwin_modeling.knowledge.runtime import (
    FrozenKnowledgeRuntime,
    load_frozen_knowledge_runtime,
)
from edutwin_modeling.risk.runtime import FrozenRiskRuntime, load_frozen_risk_runtime
from edutwin_modeling.serving.lineage import SourceLineageRegistry
from edutwin_modeling.serving.schemas import (
    LoadedArtifact,
    ModelFamily,
    ModelPurpose,
    ModelVersionRef,
)


class _ConfigModel(BaseModel):
    model_config: ClassVar[ConfigDict] = ConfigDict(
        extra="forbid", populate_by_name=True
    )


class _PathConfig(_ConfigModel):
    knowledge_freeze_manifest: str = Field(min_length=1)
    risk_freeze_manifest: str = Field(min_length=1)


class _ContractConfig(_ConfigModel):
    knowledge: str = Field(min_length=1)
    risk: str = Field(min_length=1)


class ServingConfig(_ConfigModel):
    schema_version: int = Field(strict=True)
    service_version: str = Field(min_length=1)
    paths: _PathConfig
    contracts: _ContractConfig


@dataclass(frozen=True)
class ArtifactRegistry:
    """All artifacts required to serve one immutable model deployment."""

    service_version: str
    knowledge_contract_version: str
    risk_contract_version: str
    knowledge_runtime: FrozenKnowledgeRuntime
    risk_runtime: FrozenRiskRuntime
    mastery_model: ModelVersionRef
    next_model: ModelVersionRef
    risk_model: ModelVersionRef
    explanation_model: ModelVersionRef
    loaded_artifacts: tuple[LoadedArtifact, ...]
    source_lineage: SourceLineageRegistry
    lineage_manifest_sha256: str
    knowledge_lineage_sha256: str

    @classmethod
    def load(cls, deployment_mode: str | None = None) -> ArtifactRegistry:
        """Load configuration and verify every referenced frozen artifact."""

        paths = ProjectPaths.discover()
        config_path = _configuration_path(paths)
        try:
            config = ServingConfig.model_validate(load_yaml(config_path))
        except (OSError, ValidationError) as exc:
            raise DataQualityError(f"serving configuration is invalid: {config_path}") from exc
        if config.schema_version != 1 or not config.service_version.strip():
            raise DataQualityError("serving configuration version is invalid")
        if config.contracts.knowledge != "assistments-event-v1":
            raise DataQualityError("knowledge serving contract version differs")
        if config.contracts.risk != "oulad-d0-29-v1":
            raise DataQualityError("risk serving contract version differs")
        deployment_mode = (
            deployment_mode
            or os.environ.get("EDUTWIN_MODEL_DEPLOYMENT_MODE")
            or "active"
        ).strip().lower()
        if deployment_mode not in {"active", "rollback"}:
            raise DataQualityError(
                "EDUTWIN_MODEL_DEPLOYMENT_MODE must be active or rollback"
            )

        artifact_root = _artifact_root(paths)
        knowledge_manifest_path = _manifest_path(
            config.paths.knowledge_freeze_manifest,
            paths,
            artifact_root,
            "knowledge",
        )
        risk_manifest_path = _manifest_path(
            config.paths.risk_freeze_manifest,
            paths,
            artifact_root,
            "risk",
        )
        knowledge_manifest = _read_manifest(knowledge_manifest_path, "knowledge")
        risk_manifest = _read_manifest(risk_manifest_path, "risk")
        if knowledge_manifest.get("featureContractVersion") != config.contracts.knowledge:
            raise DataQualityError("knowledge freeze feature contract differs")
        if risk_manifest.get("featureContractVersion") != config.contracts.risk:
            raise DataQualityError("risk freeze feature contract differs")

        knowledge_runtime = load_frozen_knowledge_runtime(
            knowledge_manifest_path, paths, deployment_mode=deployment_mode
        )
        risk_runtime = load_frozen_risk_runtime(
            risk_manifest_path, paths, deployment_mode=deployment_mode
        )
        source_lineage = SourceLineageRegistry.load(paths)
        mastery_model = _knowledge_reference(
            knowledge_runtime.mastery_reference, ModelPurpose.MASTERY
        )
        next_model = _knowledge_reference(
            knowledge_runtime.next_reference, ModelPurpose.NEXT_CORRECT
        )
        risk_model = _full_reference(
            risk_runtime.risk_reference, expected_purpose=ModelPurpose.RISK
        )
        explanation_model = _full_reference(
            risk_runtime.explanation_reference,
            expected_purpose=ModelPurpose.EXPLANATION,
        )
        knowledge_manifest_sha = sha256_file(knowledge_manifest_path)
        risk_manifest_sha = sha256_file(risk_manifest_path)
        loaded = (
            LoadedArtifact(
                model=mastery_model,
                manifest_sha256=knowledge_manifest_sha,
                loaded=True,
            ),
            LoadedArtifact(
                model=next_model,
                manifest_sha256=knowledge_manifest_sha,
                loaded=True,
            ),
            LoadedArtifact(
                model=risk_model,
                manifest_sha256=risk_manifest_sha,
                loaded=True,
            ),
            LoadedArtifact(
                model=explanation_model,
                manifest_sha256=risk_manifest_sha,
                loaded=True,
            ),
        )
        return cls(
            service_version=config.service_version,
            knowledge_contract_version=config.contracts.knowledge,
            risk_contract_version=config.contracts.risk,
            knowledge_runtime=knowledge_runtime,
            risk_runtime=risk_runtime,
            mastery_model=mastery_model,
            next_model=next_model,
            risk_model=risk_model,
            explanation_model=explanation_model,
            loaded_artifacts=loaded,
            source_lineage=source_lineage,
            lineage_manifest_sha256=source_lineage.manifest_sha256,
            knowledge_lineage_sha256=source_lineage.knowledge_lineage_sha256,
        )

    @property
    def feature_contract_versions(self) -> tuple[str, str]:
        return self.knowledge_contract_version, self.risk_contract_version


def _configuration_path(paths: ProjectPaths) -> Path:
    configured = os.environ.get("EDUTWIN_MODEL_CONFIG")
    candidate = (
        Path(configured)
        if configured
        else paths.modeling_root / "configs" / "serving" / "service.yaml"
    )
    resolved = candidate.resolve()
    if not resolved.is_file():
        raise DataQualityError(f"serving configuration is missing: {resolved}")
    return resolved


def _artifact_root(paths: ProjectPaths) -> Path:
    configured = os.environ.get("EDUTWIN_MODEL_ARTIFACT_ROOT")
    candidate = Path(configured) if configured else paths.modeling_root / "artifacts"
    resolved = candidate.resolve()
    if not resolved.is_dir():
        raise DataQualityError(f"model artifact root is missing: {resolved}")
    return resolved


def _manifest_path(
    configured: str,
    paths: ProjectPaths,
    artifact_root: Path,
    purpose: str,
) -> Path:
    candidate = Path(configured)
    resolved = (
        candidate.resolve()
        if candidate.is_absolute()
        else (paths.modeling_root / candidate).resolve()
    )
    try:
        resolved.relative_to(artifact_root)
    except ValueError as exc:
        raise DataQualityError(
            f"{purpose} freeze manifest is outside the artifact root: {resolved}"
        ) from exc
    if not resolved.is_file():
        raise DataQualityError(f"{purpose} freeze manifest is missing: {resolved}")
    return resolved


def _read_manifest(path: Path, purpose: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DataQualityError(f"{purpose} freeze manifest is unreadable") from exc
    if not isinstance(value, dict) or value.get("status") != "FROZEN":
        raise DataQualityError(f"{purpose} freeze manifest is not frozen")
    return value


def _knowledge_reference(
    reference: Mapping[str, Any], purpose: ModelPurpose
) -> ModelVersionRef:
    family_value = str(reference.get("family", ""))
    try:
        family = ModelFamily(family_value)
    except ValueError as exc:
        raise DataQualityError(f"unsupported knowledge model family: {family_value}") from exc
    try:
        return ModelVersionRef.model_validate(
            {
                "purpose": purpose,
                "family": family,
                "modelName": family.value.lower(),
                "modelVersion": reference["modelVersion"],
                "artifactSha256": reference["modelArtifactSha256"],
                "calibratorVersion": reference["calibratorVersion"],
            }
        )
    except (KeyError, ValidationError) as exc:
        raise DataQualityError(f"{purpose.value} model reference is invalid") from exc


def _full_reference(
    reference: Mapping[str, Any], *, expected_purpose: ModelPurpose
) -> ModelVersionRef:
    try:
        result = ModelVersionRef.model_validate(reference)
    except ValidationError as exc:
        raise DataQualityError(
            f"{expected_purpose.value} model reference is invalid"
        ) from exc
    if result.purpose != expected_purpose:
        raise DataQualityError(
            f"{expected_purpose.value} model reference uses the wrong purpose"
        )
    if expected_purpose == ModelPurpose.RISK and result.calibrator_version is None:
        raise DataQualityError("RISK model reference has no calibrator version")
    return result
