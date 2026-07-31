"""Deterministic initial twin projections from frozen serving artifacts."""

from __future__ import annotations

import json
import math
import time
from collections.abc import Mapping
from pathlib import Path
from typing import Any, cast

import numpy as np
import pandas as pd

from edutwin_modeling.config import ProjectPaths, resolve_repository_path
from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.hashing import sha256_file
from edutwin_modeling.knowledge.runtime import load_frozen_knowledge_runtime
from edutwin_modeling.manifest import file_entry, write_canonical_json
from edutwin_modeling.risk.model import FEATURE_COLUMNS
from edutwin_modeling.risk.runtime import load_frozen_risk_runtime

EXPECTED_STUDENTS = 2_000
EXPECTED_SKILLS = 60
SNAPSHOTS_PER_ENROLLMENT = 6
MIN_EVENTS_PER_ENROLLMENT = 18
MAX_EVENTS_PER_ENROLLMENT = 42


def _section(config: Mapping[str, Any], name: str) -> Mapping[str, Any]:
    value = config.get(name)
    if not isinstance(value, Mapping):
        raise DataQualityError(f"bootstrap configuration {name!r} must be an object")
    return value


def _path(config: Mapping[str, Any], name: str, paths: ProjectPaths) -> Path:
    value = config.get(name)
    if not isinstance(value, str) or not value:
        raise DataQualityError(f"bootstrap paths.{name} must be a non-empty string")
    return resolve_repository_path(value, paths)


def _load_json(path: Path, context: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DataQualityError(f"failed to read {context}: {path}") from exc
    if not isinstance(value, dict):
        raise DataQualityError(f"{context} must contain an object")
    return value


def _read_csv(path: Path, context: str) -> pd.DataFrame:
    if not path.is_file():
        raise DataQualityError(f"{context} is missing: {path}")
    try:
        return pd.read_csv(path, dtype="string", keep_default_na=False)
    except (OSError, pd.errors.ParserError) as exc:
        raise DataQualityError(f"{context} is unreadable: {path}") from exc


def _require_columns(frame: pd.DataFrame, columns: set[str], context: str) -> None:
    missing = sorted(columns.difference(frame.columns))
    if missing:
        raise DataQualityError(f"{context} omits required columns: {missing}")


def _boolean(value: object, context: str) -> bool:
    normalized = str(value).strip().lower()
    if normalized not in {"true", "false"}:
        raise DataQualityError(f"{context} is not boolean")
    return normalized == "true"


def _probability(value: float) -> float:
    if not np.isfinite(value):
        raise DataQualityError("bootstrap probability is not finite")
    return round(float(np.clip(value, 0.0, 1.0)), 8)


def _plan_progress_fraction(
    snapshot_version: int,
    engagement: float,
    persistence: float,
    risk_probability: float,
) -> float:
    if snapshot_version < SNAPSHOTS_PER_ENROLLMENT:
        return (snapshot_version - 1) / SNAPSHOTS_PER_ENROLLMENT
    return float(
        np.clip(
            0.05 + 0.45 * engagement + 0.35 * persistence - 0.45 * risk_probability,
            0.02,
            0.90,
        )
    )


def _plan_task_completed_count(progress_fraction: float, ordinal: int) -> int:
    offset = 0.85 if ordinal % 2 else -0.45
    return int(np.clip(math.floor(progress_fraction * 3 + offset), 0, 3))


def _decimal(value: float) -> float:
    if not np.isfinite(value):
        raise DataQualityError("bootstrap decimal is not finite")
    return round(float(value), 10)


def _risk_features(events: pd.DataFrame, course_start: pd.Timestamp) -> dict[str, float]:
    window_end = course_start + pd.Timedelta(days=29)
    occurred = (
        events["_occurred_at"]
        if "_occurred_at" in events
        else pd.to_datetime(events["occurred_at"], utc=True, errors="raise", format="ISO8601")
    )
    days = occurred.dt.floor("D")
    start = course_start.tz_convert("UTC").floor("D")
    selected = events.loc[(days >= start) & (days <= window_end.floor("D"))].copy()
    selected_occurred = occurred.loc[selected.index]
    selected_days = selected_occurred.dt.floor("D")
    correct = selected["correct"].map(lambda value: _boolean(value, "answer_events.correct"))
    count = len(selected)
    active_weeks = ((selected_days - start).dt.days // 7).nunique() if count else 0
    mean_score = 100.0 * float(correct.sum()) / count if count else 0.0
    values = {
        "vle_total_clicks": float(count),
        "vle_interaction_count": float(count),
        "vle_active_days": float(selected_days.nunique()),
        "vle_active_weeks": float(active_weeks),
        "vle_resource_count": float(selected["question_id"].nunique()),
        "assessment_count": float(count),
        "assessment_scored_count": float(count),
        "assessment_mean_score": float(mean_score),
    }
    if tuple(values) != FEATURE_COLUMNS:
        raise DataQualityError("bootstrap risk feature order differs")
    return values


def _activity_scores(
    activities: pd.DataFrame,
    captured_at: pd.Timestamp,
    course_start: pd.Timestamp,
) -> tuple[float, float]:
    occurred = (
        activities["_occurred_at"]
        if "_occurred_at" in activities
        else pd.to_datetime(
            activities["occurred_at"], utc=True, errors="raise", format="ISO8601"
        )
    )
    selected = activities.loc[occurred <= captured_at].copy()
    if selected.empty:
        return 0.0, 0.0

    selected_occurred = occurred.loc[selected.index]
    active_days = selected_occurred.dt.floor("D").nunique()
    elapsed_days = max(
        1,
        int(
            (captured_at.floor("D") - course_start.tz_convert("UTC").floor("D")).days
        )
        + 1,
    )
    duration = pd.to_numeric(selected["duration_seconds"], errors="raise").clip(lower=0)
    duration_minutes = float(duration.sum()) / 60.0
    event_types = selected["event_type"].astype(str)
    starts = int(event_types.isin({"PRACTICE_START", "ASSESSMENT_OPEN"}).sum())
    submits = int(event_types.isin({"PRACTICE_SUBMIT", "ASSESSMENT_SUBMIT"}).sum())
    completion_ratio = submits / starts if starts else 0.0
    engagement = 0.55 * min(active_days / min(elapsed_days, 14), 1.0) + 0.45 * min(
        duration_minutes / max(active_days * 12.0, 1.0), 1.0
    )
    persistence = 0.7 * min(completion_ratio, 1.0) + 0.3 * min(
        active_days / max(min(elapsed_days, 7), 1), 1.0
    )
    return _probability(engagement), _probability(persistence)


def _load_inputs(
    path_config: Mapping[str, Any], paths: ProjectPaths
) -> tuple[
    pd.DataFrame,
    pd.DataFrame,
    pd.DataFrame,
    pd.DataFrame,
    pd.DataFrame,
    pd.DataFrame,
    pd.DataFrame,
    pd.DataFrame,
    pd.DataFrame,
]:
    students = _read_csv(_path(path_config, "students", paths), "bootstrap students")
    enrollments = _read_csv(_path(path_config, "enrollments", paths), "bootstrap enrollments")
    courses = _read_csv(_path(path_config, "courses", paths), "bootstrap courses")
    course_questions = _read_csv(
        _path(path_config, "course_questions", paths), "bootstrap course questions"
    )
    knowledge_skills = _read_csv(
        _path(path_config, "knowledge_skills", paths), "bootstrap knowledge skills"
    )
    questions = _read_csv(_path(path_config, "questions", paths), "bootstrap questions")
    events = _read_csv(_path(path_config, "answer_events", paths), "bootstrap events")
    event_skills = _read_csv(
        _path(path_config, "answer_event_skills", paths), "bootstrap event skills"
    )
    activities = _read_csv(
        _path(path_config, "learning_activity_events", paths),
        "bootstrap learning activities",
    )
    lineage = _read_csv(
        _path(path_config, "knowledge_lineage", paths), "bootstrap knowledge lineage"
    )
    _require_columns(
        students,
        {
            "student_id",
            "synthetic",
        },
        "bootstrap students",
    )
    _require_columns(courses, {"course_id", "starts_on"}, "bootstrap courses")
    _require_columns(enrollments, {"course_id", "student_id", "status"}, "bootstrap enrollments")
    _require_columns(course_questions, {"course_id", "question_id"}, "bootstrap course questions")
    _require_columns(
        knowledge_skills,
        {"knowledge_skill_id", "name"},
        "bootstrap knowledge skills",
    )
    _require_columns(
        questions,
        {"question_id", "knowledge_skill_id", "question_key"},
        "bootstrap questions",
    )
    _require_columns(
        events,
        {
            "answer_event_id",
            "student_id",
            "course_id",
            "question_id",
            "event_sequence",
            "correct",
            "occurred_at",
        },
        "bootstrap events",
    )
    _require_columns(
        event_skills,
        {"answer_event_id", "knowledge_skill_id"},
        "bootstrap event skills",
    )
    _require_columns(
        activities,
        {
            "course_id",
            "student_id",
            "event_type",
            "occurred_at",
            "duration_seconds",
        },
        "bootstrap learning activities",
    )
    _require_columns(
        lineage,
        {
            "knowledge_skill_id",
            "source_skill_key",
            "question_id",
            "source_problem_key",
        },
        "bootstrap knowledge lineage",
    )
    if (
        len(students) != EXPECTED_STUDENTS
        or not students["synthetic"].map(lambda value: _boolean(value, "students.synthetic")).all()
    ):
        raise DataQualityError("bootstrap requires exactly 2,000 synthetic students")
    if len(lineage) != 600 or lineage["knowledge_skill_id"].nunique() != EXPECTED_SKILLS:
        raise DataQualityError("bootstrap knowledge lineage cardinality differs")
    if event_skills["answer_event_id"].duplicated().any():
        raise DataQualityError("bootstrap expects one synthetic skill per answer event")

    events = events.merge(
        event_skills[["answer_event_id", "knowledge_skill_id"]],
        on="answer_event_id",
        how="left",
        validate="one_to_one",
    )
    events = events.merge(
        lineage[
            [
                "question_id",
                "knowledge_skill_id",
                "source_problem_key",
                "source_skill_key",
            ]
        ],
        on=["question_id", "knowledge_skill_id"],
        how="left",
        validate="many_to_one",
    )
    if (
        events[["source_problem_key", "source_skill_key"]].isna().any().any()
        or events[["source_problem_key", "source_skill_key"]].eq("").any().any()
    ):
        raise DataQualityError("bootstrap events contain unmapped knowledge lineage")
    events["event_sequence"] = pd.to_numeric(events["event_sequence"], errors="raise").astype(
        "int64"
    )
    events["_occurred_at"] = pd.to_datetime(
        events["occurred_at"], utc=True, errors="raise", format="ISO8601"
    )
    activities["_occurred_at"] = pd.to_datetime(
        activities["occurred_at"], utc=True, errors="raise", format="ISO8601"
    )
    return (
        students,
        enrollments,
        courses,
        course_questions,
        knowledge_skills,
        questions,
        events,
        lineage,
        activities,
    )


def _diagnosis(
    risk_band: str,
    probability: float,
    weakest_skill_name: str,
    evidence: list[dict[str, Any]],
    fallback_version: str,
) -> dict[str, Any]:
    strongest = evidence[0]
    risk_labels = {"LOW": "低风险", "MEDIUM": "中风险", "HIGH": "高风险"}
    feature_labels = {
        "vle_total_clicks": "学习平台交互总量",
        "vle_interaction_count": "学习平台交互次数",
        "vle_active_days": "活跃学习天数",
        "vle_active_weeks": "活跃学习周数",
        "vle_resource_count": "学习资源使用数",
        "assessment_count": "考核次数",
        "assessment_scored_count": "已评分考核次数",
        "assessment_mean_score": "考核平均成绩",
    }
    return {
        "effectiveModel": fallback_version,
        "toolCallVerified": False,
        "summary": (
            f"初始校准风险为{risk_labels.get(risk_band, '未知风险')}"
            f"。风险概率为{probability:.2%}。"
        ),
        "strengths": ["当前阶段已形成可追溯的答题与学习活动记录。"],
        "concerns": [
            "当前影响最大的模型指标为“"
            f"{feature_labels.get(strongest['featureName'], '其他学习行为指标')}”。"
        ],
        "recommendedActions": [
            f"完成“{weakest_skill_name}”相关的规则生成练习任务。"
        ],
    }


def _resume_snapshot_keys(path: Path) -> set[tuple[str, str, int]]:
    if not path.is_file() or path.stat().st_size == 0:
        return set()
    keys: set[tuple[str, str, int]] = set()
    last_good_offset = 0
    with path.open("r+b") as handle:
        while True:
            line = handle.readline()
            if not line:
                break
            try:
                value = json.loads(line.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                handle.truncate(last_good_offset)
                break
            if not isinstance(value, dict) or value.get("schemaVersion") != 2:
                raise DataQualityError("bootstrap temporary snapshot contract differs")
            key = (
                str(value.get("studentId", "")),
                str(value.get("courseId", "")),
                int(value.get("snapshotVersion", -1)),
            )
            if not key[0] or not key[1] or key[2] not in range(1, SNAPSHOTS_PER_ENROLLMENT + 1):
                raise DataQualityError("bootstrap temporary snapshot identity differs")
            if key in keys:
                raise DataQualityError("bootstrap temporary snapshot identity is duplicated")
            keys.add(key)
            last_good_offset = handle.tell()
    return keys


def _replace_with_retry(source: Path, target: Path) -> None:
    for attempt in range(60):
        try:
            source.replace(target)
            return
        except PermissionError:
            if attempt == 59:
                raise
            time.sleep(1)


def generate_initial_states(config: Mapping[str, Any], paths: ProjectPaths) -> dict[str, Any]:
    if (
        config.get("schema_version") != 2
        or config.get("seed") != 42
        or config.get("generator_version") != "frozen-model-bootstrap-v2"
    ):
        raise DataQualityError("bootstrap schema, seed, or generator version differs")
    path_config = _section(config, "paths")
    plan_config = _section(config, "plan")
    diagnosis_config = _section(config, "diagnosis")
    if (
        plan_config.get("rule_version") != "planner-rules-v1"
        or int(plan_config.get("maximum_items", -1)) != 5
        or float(plan_config.get("target_mastery", -1.0)) != 0.8
        or int(plan_config.get("target_count", -1)) != 3
        or int(plan_config.get("valid_days", -1)) != 7
    ):
        raise DataQualityError("bootstrap plan contract differs")
    if (
        diagnosis_config.get("requested_model") != "deepseek-v4-flash"
        or diagnosis_config.get("fallback_version") != "template-diagnosis-v1"
    ):
        raise DataQualityError("bootstrap diagnosis contract differs")

    (
        students,
        enrollments,
        courses,
        course_questions,
        knowledge_skills,
        questions,
        events,
        lineage,
        activities,
    ) = _load_inputs(path_config, paths)
    demo_manifest_path = _path(path_config, "demo_manifest", paths)
    demo_manifest = _load_json(demo_manifest_path, "demo manifest")
    if (
        demo_manifest.get("student_count") != EXPECTED_STUDENTS
        or not 150_000 <= int(demo_manifest.get("answer_event_count", 0)) <= 360_000
    ):
        raise DataQualityError("bootstrap demo manifest cardinality differs")

    knowledge_freeze_path = _path(path_config, "knowledge_freeze_manifest", paths)
    risk_freeze_path = _path(path_config, "risk_freeze_manifest", paths)
    knowledge_runtime = load_frozen_knowledge_runtime(knowledge_freeze_path, paths)
    risk_runtime = load_frozen_risk_runtime(risk_freeze_path, paths)

    course_start = {
        str(row.course_id): pd.Timestamp(str(row.starts_on), tz="UTC")
        for row in courses.itertuples(index=False)
    }
    course_question_ids = {
        course_id: set(group["question_id"])
        for course_id, group in course_questions.groupby("course_id")
    }
    events_by_enrollment = {
        (str(student_id), str(course_id)): group.sort_values(
            "event_sequence", kind="mergesort"
        )
        for (student_id, course_id), group in events.groupby(
            ["student_id", "course_id"], sort=False
        )
    }
    activities_by_enrollment = {
        (str(student_id), str(course_id)): group
        for (student_id, course_id), group in activities.groupby(
            ["student_id", "course_id"], sort=False
        )
    }
    questions_by_course: dict[str, tuple[dict[str, pd.Series], set[str]]] = {}
    for course_id, question_ids in course_question_ids.items():
        scoped_questions = questions.loc[questions["question_id"].isin(question_ids)]
        question_by_skill = {
            str(skill): group.sort_values("question_key", kind="mergesort").iloc[0]
            for skill, group in scoped_questions.groupby("knowledge_skill_id", sort=True)
        }
        questions_by_course[str(course_id)] = (
            question_by_skill,
            {str(value) for value in scoped_questions["knowledge_skill_id"]},
        )
    skill_source = (
        lineage[["knowledge_skill_id", "source_skill_key"]]
        .drop_duplicates()
        .set_index("knowledge_skill_id")["source_skill_key"]
        .to_dict()
    )
    source_problem = lineage.set_index("question_id")["source_problem_key"].to_dict()
    skill_names = knowledge_skills.set_index("knowledge_skill_id")["name"].to_dict()
    if set(skill_names) != set(skill_source):
        raise DataQualityError("bootstrap knowledge skill identities differ")

    output_path = _path(path_config, "output", paths)
    output_manifest_path = _path(path_config, "output_manifest", paths)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = output_path.with_suffix(output_path.suffix + ".tmp")
    seen_snapshots = _resume_snapshot_keys(temporary)
    active_enrollments = enrollments.loc[enrollments["status"].eq("ACTIVE")].sort_values(
        ["student_id", "course_id"], kind="mergesort"
    )
    try:
        mode = "a" if seen_snapshots else "w"
        with temporary.open(mode, encoding="utf-8", newline="\n") as handle:
            for enrollment in active_enrollments.itertuples(index=False):
                student_id = str(enrollment.student_id)
                course_id = str(enrollment.course_id)
                student_events = events_by_enrollment.get((student_id, course_id))
                if student_events is None:
                    raise DataQualityError(f"bootstrap student {student_id} events are missing")
                if not (
                    MIN_EVENTS_PER_ENROLLMENT
                    <= len(student_events)
                    <= MAX_EVENTS_PER_ENROLLMENT
                ):
                    raise DataQualityError(f"bootstrap student {student_id} event count differs")
                if student_events["event_sequence"].tolist() != list(
                    range(1, len(student_events) + 1)
                ):
                    raise DataQualityError(f"bootstrap student {student_id} event sequence differs")
                student_activities = activities_by_enrollment.get((student_id, course_id))
                if student_activities is None or student_activities.empty:
                    raise DataQualityError(
                        f"bootstrap student {student_id} learning activities are missing"
                    )
                question_by_skill, scoped_skill_ids = questions_by_course[course_id]
                for snapshot_version in range(1, SNAPSHOTS_PER_ENROLLMENT + 1):
                    snapshot_key = (student_id, course_id, snapshot_version)
                    if snapshot_key in seen_snapshots:
                        continue
                    checkpoint = math.ceil(
                        len(student_events) * snapshot_version / SNAPSHOTS_PER_ENROLLMENT
                    )
                    prefix = student_events.iloc[:checkpoint]
                    interactions = tuple(
                        (
                            str(row.source_problem_key),
                            (str(row.source_skill_key),),
                            int(_boolean(row.correct, "answer_events.correct")),
                        )
                        for row in prefix.itertuples(index=False)
                    )
                    outcomes_by_skill: dict[str, list[int]] = {
                        str(skill_source[skill_id]): [] for skill_id in scoped_skill_ids
                    }
                    for _, skills, outcome in interactions:
                        for source in skills:
                            outcomes_by_skill[source].append(outcome)
                    mastery: list[dict[str, Any]] = []
                    for skill_id in sorted(scoped_skill_ids):
                        source = str(skill_source[skill_id])
                        probability = knowledge_runtime.mastery_model.mastery_after(
                            knowledge_runtime.vocabulary.skill_index(source),
                            tuple(outcomes_by_skill[source]),
                        )
                        mastery.append(
                            {
                                "skillId": skill_id,
                                "skillName": skill_names[skill_id],
                                "probability": _probability(probability),
                            }
                        )
                    weakest = sorted(
                        mastery,
                        key=lambda value: (
                            value["probability"],
                            value["skillName"],
                            value["skillId"],
                        ),
                    )
                    target_skill = weakest[0]
                    target_question = question_by_skill[target_skill["skillId"]]
                    prediction = knowledge_runtime.predict(
                        interactions,
                        str(source_problem[str(target_question.question_id)]),
                        (str(skill_source[target_skill["skillId"]]),),
                    )

                    features = _risk_features(prefix, course_start[course_id])
                    risk_probability, risk_band = risk_runtime.predict(features)
                    base_value, output_value, raw_evidence = risk_runtime.explain(features)
                    evidence = [
                        {
                            **factor,
                            "rawValue": _decimal(float(factor["rawValue"])),
                            "contribution": _decimal(float(factor["contribution"])),
                            "baseValue": _decimal(float(factor["baseValue"])),
                        }
                        for factor in raw_evidence
                    ]
                    last_event = prefix.iloc[-1]
                    captured_timestamp = pd.Timestamp(last_event["_occurred_at"]) + pd.Timedelta(
                        seconds=1
                    )
                    engagement, persistence = _activity_scores(
                        student_activities,
                        captured_timestamp,
                        course_start[course_id],
                    )
                    progress_fraction = _plan_progress_fraction(
                        snapshot_version,
                        engagement,
                        persistence,
                        risk_probability,
                    )
                    tasks = []
                    for ordinal, skill in enumerate(weakest[:5], start=1):
                        question = question_by_skill[skill["skillId"]]
                        completed_count = (
                            min(3, int(progress_fraction * 4 + (ordinal % 2)))
                            if snapshot_version < SNAPSHOTS_PER_ENROLLMENT
                            else _plan_task_completed_count(progress_fraction, ordinal)
                        )
                        tasks.append(
                            {
                                "ordinal": ordinal,
                                "questionId": str(question.question_id),
                                "skillId": str(skill["skillId"]),
                                "taskType": "PRACTICE",
                                "reasonCode": (
                                    "LOW_MASTERY"
                                    if float(skill["probability"]) < 0.65
                                    else "SPACED_REVIEW"
                                ),
                                "targetMastery": 0.8,
                                "targetCount": 3,
                                "completedCount": completed_count,
                                "status": (
                                    "COMPLETED"
                                    if completed_count == 3
                                    else "IN_PROGRESS"
                                    if completed_count > 0
                                    else "PENDING"
                                ),
                                "dueOffsetDays": ordinal,
                            }
                        )
                    plan_completion_rate = _probability(
                        sum(cast(int, task["completedCount"]) for task in tasks)
                        / sum(cast(int, task["targetCount"]) for task in tasks)
                    )

                    value = {
                        "schemaVersion": 2,
                        "snapshotVersion": snapshot_version,
                        "studentId": student_id,
                        "courseId": course_id,
                        "answerEventId": str(last_event["answer_event_id"]),
                        "eventSequence": int(last_event["event_sequence"]),
                        "capturedAt": captured_timestamp.isoformat(),
                        "knowledge": {
                            "masteryModelVersion": prediction.mastery_model_version,
                            "nextModelVersion": prediction.next_model_version,
                            "nextCalibratorVersion": prediction.next_calibrator_version,
                            "nextCorrectProbability": _probability(
                                prediction.next_correct_probability
                            ),
                            "skills": mastery,
                        },
                        "risk": {
                            "modelVersion": str(risk_runtime.risk_reference["modelVersion"]),
                            "explainerModelVersion": str(
                                risk_runtime.explanation_reference["modelVersion"]
                            ),
                            "featureContractVersion": "oulad-d0-29-v1",
                            "probability": _probability(risk_probability),
                            "riskBand": risk_band,
                            "baseValue": _decimal(base_value),
                            "outputValue": _decimal(output_value),
                            "calibrated": True,
                            "features": features,
                            "evidence": evidence,
                        },
                        "engagementScore": engagement,
                        "persistenceScore": persistence,
                        "planCompletionRate": plan_completion_rate,
                        "plan": {
                            "ruleVersion": "planner-rules-v1",
                            "validDays": 7,
                            "tasks": tasks,
                        },
                        "diagnosis": _diagnosis(
                            risk_band,
                            _probability(risk_probability),
                            str(target_skill["skillName"]),
                            evidence,
                            "template-diagnosis-v1",
                        ),
                    }
                    seen_snapshots.add(snapshot_key)
                    handle.write(
                        json.dumps(
                            value,
                            ensure_ascii=True,
                            sort_keys=True,
                            separators=(",", ":"),
                        )
                        + "\n"
                    )
        expected_snapshots = len(active_enrollments) * SNAPSHOTS_PER_ENROLLMENT
        if len(seen_snapshots) != expected_snapshots:
            raise DataQualityError("bootstrap output snapshot cardinality differs")
        _replace_with_retry(temporary, output_path)
    finally:
        if temporary.exists():
            try:
                temporary.unlink()
            except PermissionError:
                pass

    manifest = {
        "schemaVersion": 2,
        "kind": "edutwin-initial-state-projection",
        "generatorVersion": "frozen-model-bootstrap-v2",
        "seed": 42,
        "studentCount": EXPECTED_STUDENTS,
        "enrollmentCount": len(active_enrollments),
        "skillsPerEnrollment": 3,
        "snapshotsPerEnrollment": SNAPSHOTS_PER_ENROLLMENT,
        "eventsPerEnrollmentRange": [MIN_EVENTS_PER_ENROLLMENT, MAX_EVENTS_PER_ENROLLMENT],
        "demoManifest": file_entry(demo_manifest_path, paths),
        "knowledgeFreezeManifest": file_entry(knowledge_freeze_path, paths),
        "riskFreezeManifest": file_entry(risk_freeze_path, paths),
        "output": file_entry(output_path, paths),
    }
    write_canonical_json(manifest, output_manifest_path)
    return {
        "initial_state_manifest": file_entry(output_manifest_path, paths),
        "initial_states": file_entry(output_path, paths),
        "student_count": EXPECTED_STUDENTS,
        "enrollment_count": len(active_enrollments),
    }


def verify_initial_states(config: Mapping[str, Any], paths: ProjectPaths) -> dict[str, Any]:
    path_config = _section(config, "paths")
    output_path = _path(path_config, "output", paths)
    manifest_path = _path(path_config, "output_manifest", paths)
    manifest = _load_json(manifest_path, "initial state manifest")
    expected = {
        "schemaVersion": 2,
        "kind": "edutwin-initial-state-projection",
        "generatorVersion": "frozen-model-bootstrap-v2",
        "seed": 42,
        "studentCount": EXPECTED_STUDENTS,
        "skillsPerEnrollment": 3,
        "snapshotsPerEnrollment": SNAPSHOTS_PER_ENROLLMENT,
        "eventsPerEnrollmentRange": [MIN_EVENTS_PER_ENROLLMENT, MAX_EVENTS_PER_ENROLLMENT],
    }
    if any(manifest.get(key) != value for key, value in expected.items()):
        raise DataQualityError("initial state manifest contract differs")
    for name, configured_name in (
        ("demoManifest", "demo_manifest"),
        ("knowledgeFreezeManifest", "knowledge_freeze_manifest"),
        ("riskFreezeManifest", "risk_freeze_manifest"),
        ("output", "output"),
    ):
        entry = manifest.get(name)
        configured = _path(path_config, configured_name, paths)
        if (
            not isinstance(entry, Mapping)
            or entry.get("path") != configured.relative_to(paths.repository_root).as_posix()
            or entry.get("sha256") != sha256_file(configured)
            or entry.get("bytes") != configured.stat().st_size
        ):
            raise DataQualityError(f"initial state manifest {name} differs")

    snapshots: set[tuple[str, str, int]] = set()
    line_count = 0
    try:
        with output_path.open("r", encoding="utf-8") as handle:
            for line in handle:
                line_count += 1
                value = json.loads(line)
                if not isinstance(value, dict) or value.get("schemaVersion") != 2:
                    raise DataQualityError("initial state row contract differs")
                student = str(value.get("studentId", ""))
                course = str(value.get("courseId", ""))
                snapshot_version = value.get("snapshotVersion")
                knowledge = value.get("knowledge")
                risk = value.get("risk")
                plan = value.get("plan")
                if (
                    not student
                    or not course
                    or not isinstance(snapshot_version, int)
                    or snapshot_version not in range(1, SNAPSHOTS_PER_ENROLLMENT + 1)
                    or (student, course, snapshot_version) in snapshots
                    or not isinstance(knowledge, dict)
                    or len(knowledge.get("skills", ())) != 3
                    or not isinstance(risk, dict)
                    or len(risk.get("evidence", ())) != 5
                    or not isinstance(plan, dict)
                    or not 1 <= len(plan.get("tasks", ())) <= 5
                    or not 0 <= float(value.get("planCompletionRate", -1)) <= 1
                ):
                    raise DataQualityError("initial state row cardinality differs")
                for task in plan["tasks"]:
                    if (
                        task.get("status") not in {"PENDING", "IN_PROGRESS", "COMPLETED"}
                        or not 0 <= int(task.get("completedCount", -1)) <= int(
                            task.get("targetCount", -1)
                        )
                    ):
                        raise DataQualityError("initial state plan progress differs")
                if snapshot_version == SNAPSHOTS_PER_ENROLLMENT and all(
                    task.get("status") == "COMPLETED" for task in plan["tasks"]
                ):
                    raise DataQualityError("current initial plan has no actionable task")
                snapshots.add((student, course, snapshot_version))
    except (OSError, json.JSONDecodeError) as exc:
        raise DataQualityError("initial state JSONL is unreadable") from exc
    expected_enrollments = int(manifest.get("enrollmentCount", -1))
    expected_snapshots = expected_enrollments * SNAPSHOTS_PER_ENROLLMENT
    if line_count != expected_snapshots or len(snapshots) != expected_snapshots:
        raise DataQualityError("initial state output must cover six versions per enrollment")
    enrollment_versions: dict[tuple[str, str], set[int]] = {}
    for student, course, version in snapshots:
        enrollment_versions.setdefault((student, course), set()).add(version)
    expected_versions = set(range(1, SNAPSHOTS_PER_ENROLLMENT + 1))
    if any(versions != expected_versions for versions in enrollment_versions.values()):
        raise DataQualityError("initial state snapshot versions are incomplete")
    return {
        "initial_state_manifest": file_entry(manifest_path, paths),
        "initial_states": file_entry(output_path, paths),
        "enrollment_count": expected_enrollments,
        "snapshot_count": line_count,
    }
