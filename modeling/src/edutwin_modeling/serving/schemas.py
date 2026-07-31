"""Strict request and response models for the internal inference API."""

from __future__ import annotations

import json
import math
from datetime import UTC, datetime
from enum import StrEnum
from itertools import pairwise
from typing import Annotated, ClassVar, Self
from uuid import UUID

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    model_validator,
)

NonBlank = Annotated[str, StringConstraints(min_length=1)]
Sha256 = Annotated[str, StringConstraints(pattern=r"^[a-f0-9]{64}$")]
Probability = Annotated[
    float, Field(strict=True, ge=0.0, le=1.0, allow_inf_nan=False)
]
FiniteNumber = Annotated[float, Field(strict=True, allow_inf_nan=False)]
NonNegativeNumber = Annotated[
    float, Field(strict=True, ge=0.0, allow_inf_nan=False)
]
PositiveInteger = Annotated[int, Field(strict=True, ge=1)]


class ApiModel(BaseModel):
    """Base model that rejects undeclared JSON properties."""

    model_config: ClassVar[ConfigDict] = ConfigDict(
        extra="forbid",
        frozen=True,
        populate_by_name=True,
        serialize_by_alias=True,
    )


class DataSourceId(StrEnum):
    ASSISTMENTS = "ASSISTMENTS_2009_2010_SKILL_BUILDER_CORRECTED"
    OULAD = "OULAD"
    DEMO = "EDUTWIN_DEMO"


class ModelPurpose(StrEnum):
    MASTERY = "MASTERY"
    NEXT_CORRECT = "NEXT_CORRECT"
    RISK = "RISK"
    EXPLANATION = "EXPLANATION"
    DIAGNOSIS = "DIAGNOSIS"
    PLAN_RULES = "PLAN_RULES"


class ModelFamily(StrEnum):
    IRT = "IRT"
    BKT = "BKT"
    DKT = "DKT"
    AKT = "AKT"
    LOGISTIC_REGRESSION = "LOGISTIC_REGRESSION"
    LIGHTGBM = "LIGHTGBM"
    CATBOOST = "CATBOOST"
    SHAP = "SHAP"
    DEEPSEEK = "DEEPSEEK"
    RULE = "RULE"
    TEMPLATE = "TEMPLATE"


class DataVersionRef(ApiModel):
    source_id: DataSourceId = Field(alias="sourceId")
    version: NonBlank
    manifest_sha256: Sha256 = Field(alias="manifestSha256")


class ModelVersionRef(ApiModel):
    purpose: ModelPurpose
    family: ModelFamily
    model_name: NonBlank = Field(alias="modelName")
    model_version: NonBlank = Field(alias="modelVersion")
    artifact_sha256: Sha256 = Field(alias="artifactSha256")
    calibrator_version: NonBlank | None = Field(alias="calibratorVersion")


def _canonical_key(value: ApiModel) -> str:
    return json.dumps(
        value.model_dump(mode="json", by_alias=True),
        ensure_ascii=True,
        sort_keys=True,
        separators=(",", ":"),
    )


class TraceRef(ApiModel):
    correlation_id: UUID = Field(alias="correlationId")
    answer_event_id: UUID = Field(alias="answerEventId")
    analysis_job_id: UUID = Field(alias="analysisJobId")
    snapshot_id: UUID = Field(alias="snapshotId")
    data_versions: tuple[DataVersionRef, ...] = Field(alias="dataVersions", min_length=1)
    requested_model_versions: tuple[ModelVersionRef, ...] = Field(
        alias="requestedModelVersions", min_length=1
    )
    effective_model_versions: tuple[ModelVersionRef, ...] = Field(
        alias="effectiveModelVersions", min_length=1
    )

    @model_validator(mode="after")
    def validate_unique_versions(self) -> Self:
        data_keys = [_canonical_key(value) for value in self.data_versions]
        requested_keys = [_canonical_key(value) for value in self.requested_model_versions]
        effective_keys = [_canonical_key(value) for value in self.effective_model_versions]
        if len(data_keys) != len(set(data_keys)):
            raise ValueError("dataVersions must contain unique values")
        if len(requested_keys) != len(set(requested_keys)):
            raise ValueError("requestedModelVersions must contain unique values")
        if len(effective_keys) != len(set(effective_keys)):
            raise ValueError("effectiveModelVersions must contain unique values")
        if len({value.source_id for value in self.data_versions}) != len(self.data_versions):
            raise ValueError("dataVersions must contain at most one version per source")
        if len({value.purpose for value in self.requested_model_versions}) != len(
            self.requested_model_versions
        ):
            raise ValueError("requestedModelVersions must contain at most one version per purpose")
        if len({value.purpose for value in self.effective_model_versions}) != len(
            self.effective_model_versions
        ):
            raise ValueError("effectiveModelVersions must contain at most one version per purpose")
        return self


class KnowledgeInteraction(ApiModel):
    event_id: UUID = Field(alias="eventId")
    question_id: UUID = Field(alias="questionId")
    skill_ids: tuple[UUID, ...] = Field(alias="skillIds", min_length=1)
    correct: Annotated[bool, Field(strict=True)]
    occurred_at: AwareDatetime = Field(alias="occurredAt")
    sequence_number: PositiveInteger = Field(alias="sequenceNumber")

    @model_validator(mode="after")
    def validate_unique_skills(self) -> Self:
        if len(self.skill_ids) != len(set(self.skill_ids)):
            raise ValueError("skillIds must contain unique values")
        return self


class KnowledgePredictRequest(ApiModel):
    trace: TraceRef
    student_id: UUID = Field(alias="studentId")
    course_id: UUID = Field(alias="courseId")
    interactions: tuple[KnowledgeInteraction, ...] = Field(min_length=1)
    target_question_id: UUID = Field(alias="targetQuestionId")
    target_skill_ids: tuple[UUID, ...] = Field(alias="targetSkillIds", min_length=1)
    feature_contract_version: NonBlank = Field(alias="featureContractVersion")

    @model_validator(mode="after")
    def validate_sequence(self) -> Self:
        if len(self.target_skill_ids) != len(set(self.target_skill_ids)):
            raise ValueError("targetSkillIds must contain unique values")
        event_ids = [value.event_id for value in self.interactions]
        if len(event_ids) != len(set(event_ids)):
            raise ValueError("interactions must contain unique eventId values")
        sequences = [value.sequence_number for value in self.interactions]
        if any(current <= previous for previous, current in pairwise(sequences)):
            raise ValueError("interactions must be ordered by strictly increasing sequenceNumber")
        timestamps = [value.occurred_at for value in self.interactions]
        if any(current < previous for previous, current in pairwise(timestamps)):
            raise ValueError("interactions must be ordered by nondecreasing occurredAt")
        return self


class MasteryEstimate(ApiModel):
    skill_id: UUID = Field(alias="skillId")
    probability: Probability
    estimator_model: ModelVersionRef = Field(alias="estimatorModel")


class KnowledgePrediction(ApiModel):
    trace: TraceRef
    mastery: tuple[MasteryEstimate, ...] = Field(min_length=1)
    next_question_id: UUID = Field(alias="nextQuestionId")
    next_correct_probability: Probability = Field(alias="nextCorrectProbability")
    mastery_model: ModelVersionRef = Field(alias="masteryModel")
    next_predictor_model: ModelVersionRef = Field(alias="nextPredictorModel")
    selected_by_validation: bool = Field(alias="selectedByValidation")
    feature_contract_version: NonBlank = Field(alias="featureContractVersion")
    inference_latency_ms: NonNegativeNumber = Field(alias="inferenceLatencyMs")

    @model_validator(mode="after")
    def validate_selection(self) -> Self:
        if not self.selected_by_validation:
            raise ValueError("selectedByValidation must be true")
        return self


class ObservationWindow(ApiModel):
    start_day_inclusive: Annotated[int, Field(strict=True, ge=0, le=0)] = Field(
        alias="startDayInclusive"
    )
    end_day_inclusive: Annotated[int, Field(strict=True, ge=29, le=29)] = Field(
        alias="endDayInclusive"
    )


class RiskFeature(ApiModel):
    name: Annotated[
        str,
        StringConstraints(min_length=1, pattern=r"^[a-z][a-z0-9_]*$"),
    ]
    value: FiniteNumber

    @model_validator(mode="after")
    def validate_finite_value(self) -> Self:
        if not math.isfinite(self.value):
            raise ValueError("value must be finite")
        return self


class RiskFeatureRequest(ApiModel):
    features: tuple[RiskFeature, ...] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_unique_feature_names(self) -> Self:
        names = [feature.name for feature in self.features]
        if len(names) != len(set(names)):
            raise ValueError("features must contain unique names")
        return self


class RiskPredictRequest(RiskFeatureRequest):
    trace: TraceRef
    student_id: UUID = Field(alias="studentId")
    course_id: UUID = Field(alias="courseId")
    observation_window: ObservationWindow = Field(alias="observationWindow")
    feature_contract_version: NonBlank = Field(alias="featureContractVersion")


class RiskBand(StrEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class RiskPrediction(ApiModel):
    trace: TraceRef
    probability: Probability
    calibrated: bool
    risk_band: RiskBand = Field(alias="riskBand")
    medium_threshold: Probability = Field(alias="mediumThreshold")
    high_threshold: Probability = Field(alias="highThreshold")
    model: ModelVersionRef
    calibrator_version: NonBlank = Field(alias="calibratorVersion")
    feature_contract_version: NonBlank = Field(alias="featureContractVersion")
    inference_latency_ms: NonNegativeNumber = Field(alias="inferenceLatencyMs")

    @model_validator(mode="after")
    def validate_calibration(self) -> Self:
        if not self.calibrated:
            raise ValueError("calibrated must be true")
        if self.medium_threshold >= self.high_threshold:
            raise ValueError("mediumThreshold must be less than highThreshold")
        return self


class ExplainRequest(RiskFeatureRequest):
    features: tuple[RiskFeature, ...] = Field(min_length=5)
    trace: TraceRef
    student_id: UUID = Field(alias="studentId")
    course_id: UUID = Field(alias="courseId")
    risk_model_version: NonBlank = Field(alias="riskModelVersion")
    feature_contract_version: NonBlank = Field(alias="featureContractVersion")


class EvidenceDirection(StrEnum):
    INCREASES_RISK = "INCREASES_RISK"
    DECREASES_RISK = "DECREASES_RISK"


class DiagnosisEvidence(ApiModel):
    trace: TraceRef
    rank: Annotated[int, Field(strict=True, ge=1, le=5)]
    feature_name: NonBlank = Field(alias="featureName")
    raw_value: FiniteNumber = Field(alias="rawValue")
    direction: EvidenceDirection
    contribution: FiniteNumber
    base_value: FiniteNumber = Field(alias="baseValue")
    output_unit: Annotated[str, StringConstraints(pattern=r"^LOG_ODDS$")] = Field(
        alias="outputUnit"
    )
    risk_model_version: NonBlank = Field(alias="riskModelVersion")

    @model_validator(mode="after")
    def validate_direction(self) -> Self:
        expected = (
            EvidenceDirection.INCREASES_RISK
            if self.contribution >= 0.0
            else EvidenceDirection.DECREASES_RISK
        )
        if self.direction != expected:
            raise ValueError("direction must be derived from the contribution sign")
        return self


class ExplainResponse(ApiModel):
    trace: TraceRef
    risk_model_version: NonBlank = Field(alias="riskModelVersion")
    base_value: FiniteNumber = Field(alias="baseValue")
    output_value: FiniteNumber = Field(alias="outputValue")
    output_unit: Annotated[str, StringConstraints(pattern=r"^LOG_ODDS$")] = Field(
        alias="outputUnit"
    )
    factors: tuple[DiagnosisEvidence, ...] = Field(min_length=5, max_length=5)
    generated_at: AwareDatetime = Field(alias="generatedAt")
    inference_latency_ms: NonNegativeNumber = Field(alias="inferenceLatencyMs")

    @model_validator(mode="after")
    def validate_factor_order(self) -> Self:
        if [factor.rank for factor in self.factors] != [1, 2, 3, 4, 5]:
            raise ValueError("factors must be ordered by rank")
        names = [factor.feature_name for factor in self.factors]
        if len(names) != len(set(names)):
            raise ValueError("factors must contain unique feature names")
        if any(factor.trace != self.trace for factor in self.factors):
            raise ValueError("factor traces must match the response trace")
        if any(
            factor.risk_model_version != self.risk_model_version
            for factor in self.factors
        ):
            raise ValueError("factor riskModelVersion values must match the response")
        if any(factor.base_value != self.base_value for factor in self.factors):
            raise ValueError("factor baseValue values must match the response")
        ranking = [(-abs(factor.contribution), factor.feature_name) for factor in self.factors]
        if ranking != sorted(ranking):
            raise ValueError("factors must be ordered by absolute contribution")
        return self


class LoadedArtifact(ApiModel):
    model: ModelVersionRef
    manifest_sha256: Sha256 = Field(alias="manifestSha256")
    loaded: bool


class HealthStatus(StrEnum):
    UP = "UP"
    DOWN = "DOWN"


class HealthResponse(ApiModel):
    status: HealthStatus
    service_version: NonBlank = Field(alias="serviceVersion")
    checked_at: AwareDatetime = Field(alias="checkedAt")
    loaded_artifacts: tuple[LoadedArtifact, ...] = Field(
        alias="loadedArtifacts", min_length=3
    )
    feature_contract_versions: tuple[NonBlank, ...] = Field(
        alias="featureContractVersions", min_length=2
    )

    @model_validator(mode="after")
    def validate_contract_versions(self) -> Self:
        if len(self.feature_contract_versions) != len(set(self.feature_contract_versions)):
            raise ValueError("featureContractVersions must contain unique values")
        return self


class FieldViolation(ApiModel):
    field: NonBlank
    message: NonBlank


class ProblemDetail(ApiModel):
    problem_type: NonBlank = Field(alias="type")
    title: NonBlank
    status: Annotated[int, Field(strict=True, ge=400, le=599)]
    detail: NonBlank
    instance: NonBlank
    code: NonBlank
    trace_id: NonBlank = Field(alias="traceId")
    violations: tuple[FieldViolation, ...]


def utc_now() -> datetime:
    """Return an aware UTC timestamp for contract responses."""

    return datetime.now(UTC)
