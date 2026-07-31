"""End-to-end deterministic data preparation orchestration."""

from __future__ import annotations

import json
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import pandas as pd

from edutwin_modeling.config import ProjectPaths, resolve_repository_path
from edutwin_modeling.data.assistments import prepare_assistments
from edutwin_modeling.data.matching import match_students
from edutwin_modeling.data.oulad import prepare_oulad
from edutwin_modeling.data.provenance import verify_source_lock
from edutwin_modeling.data.synthetic import generate_synthetic_demo
from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.hashing import sha256_file
from edutwin_modeling.manifest import file_entry, repository_relative, write_canonical_json


def _paths_config(config: Mapping[str, Any]) -> Mapping[str, Any]:
    value = config.get("paths")
    if not isinstance(value, Mapping):
        raise DataQualityError("configuration paths section must be an object")
    return value


def _configured_path(
    config: Mapping[str, Any], paths: ProjectPaths, key: str
) -> Path:
    value = _paths_config(config).get(key)
    if not isinstance(value, str) or not value:
        raise DataQualityError(f"paths.{key} is required")
    return resolve_repository_path(value, paths)


def prepare_data(config: Mapping[str, Any], paths: ProjectPaths) -> dict[str, Any]:
    source_lock = verify_source_lock(config, paths)
    assistments_manifest = prepare_assistments(
        config, paths, verified_source_lock=source_lock
    )
    oulad_manifest = prepare_oulad(config, paths)

    assistments_output = _configured_path(config, paths, "assistments_output")
    oulad_output = _configured_path(config, paths, "oulad_output")
    fusion_output = _configured_path(config, paths, "fusion_output")
    demo_output = _configured_path(config, paths, "demo_output")
    manifest_output = _configured_path(config, paths, "manifest_output")

    assist_dimensions = pd.read_parquet(assistments_output / "student_dimensions.parquet")
    oulad_dimensions = pd.read_parquet(oulad_output / "dimensions.parquet")
    matches, matching_manifest = match_students(
        assist_dimensions, oulad_dimensions, config
    )
    fusion_output.mkdir(parents=True, exist_ok=True)
    matches_path = fusion_output / "synthetic_matches.local.parquet"
    matches.to_parquet(matches_path, index=False, compression="zstd")
    matching_manifest_path = fusion_output / "manifest.json"
    portable_matching = dict(matching_manifest)
    portable_matching["matches"] = file_entry(matches_path, paths)
    write_canonical_json(portable_matching, matching_manifest_path)

    assist_events = pd.read_parquet(assistments_output / "answer_events.parquet")
    assist_skills = pd.read_parquet(assistments_output / "answer_event_skills.parquet")
    demo_manifest = generate_synthetic_demo(
        matches, assist_events, assist_skills, config, demo_output, paths
    )

    processing_manifest: dict[str, Any] = {
        "schema_version": 1,
        "seed": 42,
        "source_lock": {
            "path": repository_relative(source_lock["source_lock_path"], paths),
            "sha256": source_lock["source_lock_sha256"],
        },
        "assistments": assistments_manifest,
        "oulad": oulad_manifest,
        "matching": {
            **matching_manifest,
            "manifest": file_entry(matching_manifest_path, paths),
        },
        "demo": demo_manifest,
    }
    write_canonical_json(processing_manifest, manifest_output)
    processing_manifest["manifest"] = file_entry(manifest_output, paths)
    return processing_manifest


def verify_processed_data(
    config: Mapping[str, Any], paths: ProjectPaths
) -> dict[str, Any]:
    source_lock = verify_source_lock(config, paths)
    manifest_path = _configured_path(config, paths, "manifest_output")
    if not manifest_path.is_file():
        raise DataQualityError(f"processing manifest is missing: {manifest_path}")
    demo_output = _configured_path(config, paths, "demo_output")
    required_demo_files = [
        "students.parquet",
        "courses.parquet",
        "knowledge_skills.parquet",
        "questions.parquet",
        "organization_units.parquet",
        "academic_terms.parquet",
        "answer_events.parquet",
        "answer_event_skills.parquet",
        "matches.parquet",
        "lms_lesson_progress.parquet",
        "lms_submissions.parquet",
        "lms_submission_answers.parquet",
        "manifest.json",
    ]
    missing = [name for name in required_demo_files if not (demo_output / name).is_file()]
    if missing:
        raise DataQualityError(f"demo artifacts are missing: {missing}")
    students = pd.read_parquet(demo_output / "students.parquet")
    events = pd.read_parquet(demo_output / "answer_events.parquet")
    if len(students) != 2_000 or not students["synthetic"].eq(True).all():
        raise DataQualityError("demo must contain exactly 2,000 synthetic students")
    if len(events) < 100_000 or not events["answer_event_id"].is_unique:
        raise DataQualityError("demo must contain at least 100,000 unique answer events")
    try:
        demo_manifest = json.loads(
            (demo_output / "manifest.json").read_text(encoding="utf-8")
        )
    except (OSError, json.JSONDecodeError) as exc:
        raise DataQualityError("demo manifest is not valid JSON") from exc
    database = demo_manifest.get("database_import")
    question_bank = demo_manifest.get("question_bank")
    expected_database_outputs = {
        "organization_units",
        "academic_terms",
        "courses",
        "teachers",
        "teaching_assignments",
        "knowledge_skills",
        "questions",
        "students",
        "enrollments",
        "course_questions",
        "lms_sections",
        "lms_lessons",
        "lms_assessments",
        "lms_assessment_questions",
        "lms_lesson_progress",
        "lms_submissions",
        "lms_submission_answers",
        "learning_activity_events",
        "answer_events",
        "answer_event_skills",
        "source_lineage",
        "knowledge_lineage",
    }
    if (
        demo_manifest.get("schema_version") != 7
        or demo_manifest.get("data_version") != "synthetic-demo-v6"
        or demo_manifest.get("reference_time") != "2026-06-15T00:00:00+00:00"
        or not isinstance(database, Mapping)
        or database.get("schema_version") != 3
        or database.get("contains_source_behavior_rows") is not False
        or not isinstance(database.get("outputs"), Mapping)
        or set(database["outputs"]) != expected_database_outputs
        or not isinstance(question_bank, Mapping)
        or question_bank.get("path") != "data/demo/question-bank.tsv"
        or question_bank.get("content_origin") != "TEACHER_AUTHORED"
        or not isinstance(question_bank.get("sha256"), str)
        or len(question_bank["sha256"]) != 64
    ):
        raise DataQualityError("demo database import manifest is invalid")
    for name, entry in database["outputs"].items():
        if not isinstance(entry, Mapping):
            raise DataQualityError(f"demo database output {name} is not an object")
        relative = entry.get("path")
        if not isinstance(relative, str) or not relative:
            raise DataQualityError(f"demo database output {name} has no path")
        output_path = (demo_output / relative).resolve()
        try:
            output_path.relative_to(demo_output.resolve())
        except ValueError as exc:
            raise DataQualityError(
                f"demo database output {name} escapes its root"
            ) from exc
        if not output_path.is_file() or sha256_file(output_path) != entry.get("sha256"):
            raise DataQualityError(f"demo database output {name} hash differs")
    source_lineage = pd.read_csv(
        demo_output / database["outputs"]["source_lineage"]["path"], dtype="string"
    )
    knowledge_lineage = pd.read_csv(
        demo_output / database["outputs"]["knowledge_lineage"]["path"],
        dtype="string",
    )
    if (
        len(source_lineage) != 2_000
        or not source_lineage["student_id"].is_unique
        or not source_lineage["assistments_user_sha256"].is_unique
        or not source_lineage["oulad_student_sha256"].is_unique
        or not source_lineage["assistments_user_sha256"].str.fullmatch(
            r"[a-f0-9]{64}"
        ).all()
        or not source_lineage["oulad_student_sha256"].str.fullmatch(
            r"[a-f0-9]{64}"
        ).all()
    ):
        raise DataQualityError("demo student source lineage is invalid")
    if (
        len(knowledge_lineage) != 600
        or knowledge_lineage["knowledge_skill_id"].nunique() != 60
        or not knowledge_lineage["question_id"].is_unique
        or not knowledge_lineage["source_problem_key"].is_unique
    ):
        raise DataQualityError("demo knowledge source lineage is invalid")
    tables = {
        name: pd.read_csv(
            demo_output / database["outputs"][name]["path"], dtype="string"
        )
        for name in (
            "organization_units",
            "courses",
            "enrollments",
            "lms_sections",
            "lms_lessons",
            "lms_assessments",
            "lms_assessment_questions",
            "lms_lesson_progress",
            "lms_submissions",
            "lms_submission_answers",
        )
    }
    organizations = tables["organization_units"]
    if (
        not organizations["organization_id"].is_unique
        or not organizations["code"].is_unique
        or not {"COLLEGE", "DEPARTMENT", "MAJOR", "CLASS"}.issubset(
            set(organizations["unit_type"])
        )
    ):
        raise DataQualityError("demo organization hierarchy is invalid")
    active_enrollments = set(
        map(
            tuple,
            tables["enrollments"].loc[
                tables["enrollments"]["status"].eq("ACTIVE"),
                ["course_id", "student_id"],
            ].itertuples(index=False, name=None),
        )
    )
    assessments = tables["lms_assessments"]
    assessment_courses = dict(
        zip(assessments["assessment_id"], assessments["course_id"], strict=True)
    )
    submissions = tables["lms_submissions"]
    if (
        submissions.duplicated(
            ["assessment_id", "student_id", "attempt_number"]
        ).any()
        or any(
            (assessment_courses[row.assessment_id], row.student_id)
            not in active_enrollments
            for row in submissions.itertuples(index=False)
        )
        or not submissions["attempt_number"].eq("2").any()
    ):
        raise DataQualityError("demo assessment attempts are inconsistent")
    answers = tables["lms_submission_answers"]
    question_assessments = dict(
        zip(
            tables["lms_assessment_questions"]["assessment_question_id"],
            tables["lms_assessment_questions"]["assessment_id"],
            strict=True,
        )
    )
    submission_assessments = dict(
        zip(submissions["submission_id"], submissions["assessment_id"], strict=True)
    )
    if any(
        question_assessments.get(row.assessment_question_id)
        != submission_assessments.get(row.submission_id)
        for row in answers.itertuples(index=False)
    ):
        raise DataQualityError("demo submission answers cross assessment boundaries")
    answer_scores = answers.assign(
        points_awarded=pd.to_numeric(answers["points_awarded"])
    ).groupby("submission_id")["points_awarded"].agg(["count", "sum"])
    submission_scores = submissions.assign(
        score=pd.to_numeric(submissions["score"])
    ).set_index("submission_id")
    joined_scores = submission_scores.join(answer_scores)
    if joined_scores["count"].ne(5).any() or joined_scores["score"].ne(
        joined_scores["sum"]
    ).any():
        raise DataQualityError("demo submission scores differ from their answers")
    reference_time = pd.Timestamp(demo_manifest["reference_time"])
    submitted_assessments = set(submissions["assessment_id"])
    for _, scoped in assessments.groupby("course_id"):
        open_rows = scoped.loc[scoped["status"].eq("PUBLISHED")]
        open_due = pd.to_datetime(open_rows["due_at"], utc=True, errors="coerce")
        has_open = bool((open_rows["due_at"].isna() | open_due.gt(reference_time)).any())
        closed_unsubmitted = set(
            scoped.loc[scoped["status"].eq("CLOSED"), "assessment_id"]
        ) - submitted_assessments
        if (
            not has_open
            or not (set(scoped["assessment_id"]) & submitted_assessments)
            or not closed_unsubmitted
        ):
            raise DataQualityError(
                "each demo course must include open, submitted, and closed-unsubmitted assessments"
            )
    lesson_ids = set(tables["lms_lessons"]["lesson_id"])
    section_courses = dict(
        zip(
            tables["lms_sections"]["section_id"],
            tables["lms_sections"]["course_id"],
            strict=True,
        )
    )
    lesson_courses = {
        row.lesson_id: section_courses.get(row.section_id)
        for row in tables["lms_lessons"].itertuples(index=False)
    }
    progress = tables["lms_lesson_progress"]
    if (
        progress.duplicated(["lesson_id", "student_id"]).any()
        or not set(progress["lesson_id"]).issubset(lesson_ids)
        or any(
            (lesson_courses.get(row.lesson_id), row.student_id)
            not in active_enrollments
            for row in progress.itertuples(index=False)
        )
    ):
        raise DataQualityError("demo lesson progress is invalid")
    return {
        "source_lock_sha256": source_lock["source_lock_sha256"],
        "processing_manifest": file_entry(manifest_path, paths),
        "synthetic_students": len(students),
        "unique_answer_events": int(events["answer_event_id"].nunique()),
        "source_lineage_students": len(source_lineage),
        "knowledge_lineage_questions": len(knowledge_lineage),
        "lesson_progress_rows": len(progress),
        "assessment_attempts": len(submissions),
    }
