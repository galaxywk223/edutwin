"""Deterministic demo generation without redistributing source-level behavior."""

# ruff: noqa: RUF001

from __future__ import annotations

import csv
import hashlib
import json
from collections.abc import Mapping
from pathlib import Path
from typing import Any, cast
from uuid import UUID, uuid5

import numpy as np
import pandas as pd

from edutwin_modeling.config import ProjectPaths
from edutwin_modeling.data.quality import require_columns, require_unique
from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.hashing import canonical_json_bytes, sha256_file, stable_rank
from edutwin_modeling.manifest import repository_relative

ASSIST_EVENT_COLUMNS = (
    "order_id",
    "user_id",
    "problem_id",
    "assignment_id",
    "correct",
    "attempt_count",
    "ms_first_response",
    "hint_count",
    "skill_count",
    "event_sequence",
    "split",
)
ASSIST_SKILL_COLUMNS = (
    "order_id",
    "skill_id",
    "skill_name",
    "opportunity",
    "opportunity_original",
    "ordinal",
)
MATCH_COLUMNS = (
    "match_order",
    "split",
    "assistments_user_key",
    "oulad_student_key",
    "distance",
    "assist_performance",
    "oulad_performance",
    "assist_activity",
    "oulad_activity",
    "assist_persistence",
    "oulad_persistence",
)
SPLIT_ORDER = {"train": 0, "validation": 1, "test": 2}
COURSE_COUNT = 20
MIN_ANSWER_EVENTS_PER_ENROLLMENT = 18
MAX_ANSWER_EVENTS_PER_ENROLLMENT = 42
MIN_ACTIVITY_EVENTS_PER_ENROLLMENT = 56
MAX_ACTIVITY_EVENTS_PER_ENROLLMENT = 176
SKILL_COUNT = 60
QUESTION_COUNT = 600
DEMO_DATA_VERSION = "synthetic-demo-v6"
DEMO_REFERENCE_TIME = pd.Timestamp("2026-06-15T00:00:00Z")
DEMO_TERM_CODE = "2026-SPRING"
QUESTION_BANK_DEFAULT = (
    Path(__file__).resolve().parents[4] / "data" / "demo" / "question-bank.tsv"
)
SOURCE_LINEAGE_HASH_NAMESPACE = b"edutwin-source-lineage-v1"
DATABASE_FRAME_NAMES = (
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
)

COURSE_CATALOG = (
    ("MATH101", "高等数学", 5.0),
    ("MATH102", "线性代数", 3.0),
    ("STAT201", "概率论与数理统计", 4.0),
    ("CS201", "离散数学", 3.0),
    ("CS101", "程序设计基础", 4.0),
    ("CS202", "数据结构", 4.0),
    ("CS203", "计算机组成原理", 4.0),
    ("CS301", "操作系统", 4.0),
    ("CS302", "计算机网络", 3.0),
    ("CS303", "数据库系统", 3.0),
    ("SE301", "软件工程", 3.0),
    ("CS304", "算法设计与分析", 4.0),
    ("DS201", "Python 数据分析", 3.0),
    ("AI201", "人工智能导论", 3.0),
    ("AI301", "机器学习", 4.0),
    ("AI302", "深度学习", 3.0),
    ("DS301", "数据挖掘", 3.0),
    ("DS302", "大数据技术", 3.0),
    ("AI401", "自然语言处理", 3.0),
    ("STAT301", "时间序列分析", 3.0),
)

COURSE_SKILLS = {
    "MATH101": ("极限与连续", "导数与微分", "定积分与应用"),
    "MATH102": ("矩阵与方程组", "向量空间与线性变换", "特征值与二次型"),
    "STAT201": ("随机变量与分布", "数字特征与极限定理", "参数估计与假设检验"),
    "CS201": ("数理逻辑", "集合关系与组合计数", "图论与代数结构"),
    "CS101": ("数据类型与表达式", "分支循环与函数", "数组指针与结构化程序"),
    "CS202": ("线性表栈与队列", "树与图", "查找排序与复杂度"),
    "CS203": ("数据表示与运算", "指令系统与处理器", "存储系统与输入输出"),
    "CS301": ("进程线程与调度", "内存与虚拟存储", "同步与文件系统"),
    "CS302": ("网络体系与链路层", "IP 与路由", "传输层与应用层"),
    "CS303": ("关系模型与 SQL", "规范化与事务", "索引与查询优化"),
    "SE301": ("需求与建模", "架构与设计", "测试与项目管理"),
    "CS304": ("分治与动态规划", "贪心与回溯", "图算法与复杂度"),
    "DS201": ("NumPy 数值计算", "Pandas 数据处理", "清洗与可视化"),
    "AI201": ("搜索与规划", "知识表示与推理", "机器学习基础"),
    "AI301": ("监督学习", "无监督学习与降维", "评估正则化与调参"),
    "AI302": ("神经网络与反向传播", "卷积网络", "序列模型与 Transformer"),
    "DS301": ("预处理与相似度", "分类与聚类", "频繁模式与异常检测"),
    "DS302": ("分布式存储", "MapReduce 与 Spark", "流处理与一致性"),
    "AI401": ("文本预处理与语言模型", "序列标注与表示学习", "Transformer 与生成评估"),
    "STAT301": ("平稳性与相关分析", "ARMA/ARIMA", "预测评估与季节模型"),
}

QUESTION_BANK_HEADERS = (
    "skill_code",
    "concept_1",
    "definition_1",
    "concept_2",
    "definition_2",
    "concept_3",
    "definition_3",
    "concept_4",
    "definition_4",
)
TEACHER_CATALOG = (
    ("陈明远", (0, 1)),
    ("王晓峰", (2, 3)),
    ("刘思远", (4, 12)),
    ("赵文博", (5, 11)),
    ("孙静", (6, 7)),
    ("周凯", (8, 9)),
    ("吴佳宁", (10,)),
    ("郑博文", (13, 14)),
    ("何雨欣", (15, 18)),
    ("郭子涵", (16,)),
    ("林浩", (17,)),
    ("唐若琳", (19,)),
)
MAJOR_DISTRIBUTION = (
    ("计算机科学与技术", 650),
    ("软件工程", 500),
    ("人工智能", 450),
    ("数据科学与大数据技术", 400),
)
MAJOR_CODES = {
    "计算机科学与技术": "CS",
    "软件工程": "SE",
    "人工智能": "AI",
    "数据科学与大数据技术": "DS",
}
FAMILY_NAMES = tuple(
    "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍余元顾孟平黄和穆萧尹姚邵汪祁毛禹狄米贝明臧计伏成戴谈宋茅庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林刁钟徐邱骆高夏蔡田樊胡凌霍虞万支柯昝管卢莫经房裘缪干解应宗丁宣邓郁单杭洪包诸左石崔吉龚程嵇邢裴陆荣翁荀羊甄家封芮羿储靳汲邴糜松井段富巫乌焦巴弓牧隗山谷车侯宓蓬全郗班仰秋仲伊宫宁仇栾暴甘厉戎祖武符刘景詹束龙叶幸司韶黎乔苍双闻莘党翟谭贡劳逄姬申扶堵冉宰郦雍桑桂濮牛寿通边扈燕冀郏浦尚农温庄晏柴瞿阎充慕连茹习宦艾鱼容向古易慎戈廖庾终步都耿满弘匡国文寇广禄阙东欧殳沃利蔚越夔隆师巩厍聂晁勾敖融冷訾辛阚那简饶空曾毋沙乜养鞠须丰巢关蒯相查后荆红游竺权逯盖益桓公"
)
GIVEN_NAMES = tuple(
    "子涵宇轩浩然梓轩雨桐欣怡思远嘉宁博文若琳佳琪明哲俊杰诗涵语嫣一诺晨曦可欣天佑思齐昊宇梦瑶书航芷晴泽宇雅雯景行知夏清越安然亦辰沐阳星辰思源嘉懿文昊雨泽语彤佳航睿哲舒涵锦程心怡卓然"
)


def _section(config: Mapping[str, Any], name: str) -> Mapping[str, Any]:
    value = config.get(name, {})
    if not isinstance(value, Mapping):
        raise DataQualityError(f"configuration section {name!r} must be an object")
    return value


def _source_key(value: object, *, field: str) -> str:
    if pd.isna(cast(Any, value)):
        raise DataQualityError(f"{field} contains null source keys")
    if isinstance(value, int | np.integer):
        return str(int(value))
    if isinstance(value, float | np.floating) and float(value).is_integer():
        return str(int(value))
    return str(value)


def _deterministic_id(namespace: UUID, category: str, *parts: str) -> str:
    return str(uuid5(namespace, "|".join((category, *parts))))


def _source_lineage_sha256(domain: str, value: object, *, field: str) -> str:
    source_key = _source_key(value, field=field)
    payload = b"\x00".join(
        (
            SOURCE_LINEAGE_HASH_NAMESPACE,
            domain.encode("ascii"),
            source_key.encode("utf-8"),
        )
    )
    return hashlib.sha256(payload).hexdigest()


def _as_finite_float(value: object, *, field: str) -> float:
    try:
        converted = float(cast(Any, value))
    except (TypeError, ValueError) as error:
        raise DataQualityError(f"{field} is not numeric") from error
    if not np.isfinite(converted):
        raise DataQualityError(f"{field} is not finite")
    return converted


def _unit_interval(value: float) -> float:
    if 0.0 <= value <= 1.0:
        return value
    return float(1.0 / (1.0 + np.exp(-value)))


def _fused_dimensions(match: Mapping[str, Any]) -> dict[str, float]:
    performance = np.mean(
        [
            _as_finite_float(match["assist_performance"], field="assist_performance"),
            _as_finite_float(match["oulad_performance"], field="oulad_performance"),
        ]
    )
    activity = np.mean(
        [
            _unit_interval(_as_finite_float(match["assist_activity"], field="assist_activity")),
            _unit_interval(_as_finite_float(match["oulad_activity"], field="oulad_activity")),
        ]
    )
    persistence = np.mean(
        [
            _unit_interval(
                _as_finite_float(match["assist_persistence"], field="assist_persistence")
            ),
            _unit_interval(_as_finite_float(match["oulad_persistence"], field="oulad_persistence")),
        ]
    )
    return {
        "performance": float(np.clip(performance, 0.0, 1.0)),
        "activity": float(np.clip(activity, 0.0, 1.0)),
        "persistence": float(np.clip(persistence, 0.0, 1.0)),
    }


def _normalize_matches(matches: pd.DataFrame, expected_students: int) -> pd.DataFrame:
    require_columns(matches, list(MATCH_COLUMNS), "student matches")
    if len(matches) != expected_students:
        raise DataQualityError(
            f"synthetic demo requires exactly {expected_students} matches; received {len(matches)}"
        )
    result = matches.copy()
    result["assistments_user_key"] = result["assistments_user_key"].map(
        lambda value: _source_key(value, field="matches.assistments_user_key")
    )
    result["oulad_student_key"] = result["oulad_student_key"].map(
        lambda value: _source_key(value, field="matches.oulad_student_key")
    )
    result["match_order"] = pd.to_numeric(result["match_order"], errors="coerce")
    if (
        result["match_order"].isna().any()
        or not np.equal(result["match_order"], np.floor(result["match_order"])).all()
    ):
        raise DataQualityError("student matches.match_order must contain integers")
    result["match_order"] = result["match_order"].astype("int64")
    if sorted(result["match_order"].tolist()) != list(range(1, expected_students + 1)):
        raise DataQualityError("student matches.match_order must be the sequence 1..student_count")
    require_unique(result, ["assistments_user_key"], "student matches")
    require_unique(result, ["oulad_student_key"], "student matches")
    require_unique(result, ["match_order"], "student matches")
    if not set(result["split"]).issubset(SPLIT_ORDER):
        raise DataQualityError("student matches contain an unknown split")
    result["_split_rank"] = result["split"].map(SPLIT_ORDER)
    return result.sort_values(["_split_rank", "match_order"], kind="mergesort").reset_index(
        drop=True
    )


def _validate_source_quality(
    matches: pd.DataFrame,
    assist_events: pd.DataFrame,
    assist_event_skills: pd.DataFrame,
    *,
    minimum_events_per_source_student: int,
) -> None:
    """Prove matched source coverage locally without returning source-derived values."""

    require_columns(assist_events, list(ASSIST_EVENT_COLUMNS), "ASSISTments events")
    require_columns(
        assist_event_skills,
        list(ASSIST_SKILL_COLUMNS),
        "ASSISTments event skills",
    )
    events = assist_events.copy()
    events["_source_user"] = events["user_id"].map(
        lambda value: _source_key(value, field="ASSISTments events.user_id")
    )
    events["_source_order"] = events["order_id"].map(
        lambda value: _source_key(value, field="ASSISTments events.order_id")
    )
    require_unique(events, ["_source_order"], "ASSISTments events")
    require_unique(events, ["_source_user", "event_sequence"], "ASSISTments event sequence")
    matched_sources = set(matches["assistments_user_key"])
    selected_events = events.loc[events["_source_user"].isin(matched_sources)].copy()
    source_counts = (
        selected_events.groupby("_source_user")
        .size()
        .reindex(sorted(matched_sources), fill_value=0)
    )
    insufficient = source_counts.loc[source_counts.lt(minimum_events_per_source_student)]
    if not insufficient.empty:
        raise DataQualityError(
            "matched ASSISTments students require at least "
            f"{minimum_events_per_source_student} source events; insufficient count="
            f"{len(insufficient)}"
        )

    skills = assist_event_skills.copy()
    skills["_source_order"] = skills["order_id"].map(
        lambda value: _source_key(value, field="ASSISTments event skills.order_id")
    )
    skills["_source_skill"] = skills["skill_id"].map(
        lambda value: _source_key(value, field="ASSISTments event skills.skill_id")
    )
    require_unique(skills, ["_source_order", "_source_skill"], "ASSISTments event skills")
    require_unique(skills, ["_source_order", "ordinal"], "ASSISTments event skill order")
    selected_skills = skills.loc[
        skills["_source_order"].isin(set(selected_events["_source_order"]))
    ]
    expected_counts = pd.to_numeric(selected_events["skill_count"], errors="coerce")
    if (
        expected_counts.isna().any()
        or (expected_counts < 1).any()
        or not np.equal(expected_counts, np.floor(expected_counts)).all()
    ):
        raise DataQualityError("ASSISTments skill_count must contain positive integers")
    expected_by_order = pd.Series(
        expected_counts.astype("int64").to_numpy(),
        index=selected_events["_source_order"].to_numpy(),
    )
    actual_by_order = (
        selected_skills.groupby("_source_order")
        .size()
        .reindex(expected_by_order.index, fill_value=0)
    )
    if actual_by_order.ne(expected_by_order).any():
        raise DataQualityError("ASSISTments event-skill associations fail the local quality gate")


def _build_courses(namespace: UUID) -> pd.DataFrame:
    records: list[dict[str, Any]] = []
    for index, (code, title, credit_value) in enumerate(COURSE_CATALOG, start=1):
        records.append(
            {
                "course_id": _deterministic_id(namespace, "course", f"{index:03d}"),
                "course_code": code,
                "title": title,
                "presentation": "2026 春",
                "starts_on": (DEMO_REFERENCE_TIME - pd.Timedelta(weeks=16)).date().isoformat(),
                "description": f"{title}本科课程，包含课堂资源、阶段练习与课程考核。",
                "college": "计算机与数据科学学院",
                "department": "计算机科学系"
                if not code.startswith(("DS", "STAT"))
                else "数据科学系",
                "organization_id": _deterministic_id(
                    namespace,
                    "organization",
                    "DEPT-CS" if not code.startswith(("DS", "STAT")) else "DEPT-DS",
                ),
                "academic_term_id": _deterministic_id(
                    namespace, "academic-term", DEMO_TERM_CODE
                ),
                "credits": credit_value,
                "data_version": DEMO_DATA_VERSION,
                "synthetic": True,
            }
        )
    return pd.DataFrame.from_records(records)


def _build_teachers(namespace: UUID, courses: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    teachers: list[dict[str, Any]] = []
    assignments: list[dict[str, Any]] = []
    titles = ("教授", "副教授", "讲师")
    course_ids = courses["course_id"].tolist()
    for index, (name, course_indexes) in enumerate(TEACHER_CATALOG, start=1):
        teacher_id = _deterministic_id(namespace, "teacher", f"{index:02d}")
        teachers.append(
            {
                "teacher_id": teacher_id,
                "username": f"teacher-{index:02d}",
                "display_name": name,
                "staff_number": f"T{202000 + index:06d}",
                "staff_key": f"DEMO-TEACHER-{index:02d}",
                "college": "计算机与数据科学学院",
                "department": "数据科学系" if index in (2, 10, 11, 12) else "计算机科学系",
                "organization_id": _deterministic_id(
                    namespace,
                    "organization",
                    "DEPT-DS" if index in (2, 10, 11, 12) else "DEPT-CS",
                ),
                "academic_title": titles[(index - 1) % len(titles)],
                "synthetic": True,
            }
        )
        for course_index in course_indexes:
            assignments.append({"course_id": course_ids[course_index], "teacher_id": teacher_id})
    return pd.DataFrame.from_records(teachers), pd.DataFrame.from_records(assignments)


def _load_question_bank(path: Path) -> dict[str, tuple[tuple[str, str], ...]]:
    if not path.is_file():
        raise DataQualityError(f"question bank does not exist: {path}")
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        if tuple(reader.fieldnames or ()) != QUESTION_BANK_HEADERS:
            raise DataQualityError("question bank headers do not match the frozen contract")
        result: dict[str, tuple[tuple[str, str], ...]] = {}
        for row_number, row in enumerate(reader, start=2):
            skill_code = (row.get("skill_code") or "").strip()
            if not skill_code or skill_code in result:
                raise DataQualityError(
                    f"question bank row {row_number} has a missing or duplicate skill_code"
                )
            concepts = tuple(
                (
                    (row.get(f"concept_{index}") or "").strip(),
                    (row.get(f"definition_{index}") or "").strip(),
                )
                for index in range(1, 5)
            )
            if any(not concept or not definition for concept, definition in concepts):
                raise DataQualityError(f"question bank row {row_number} contains blank content")
            if len({concept for concept, _ in concepts}) != 4:
                raise DataQualityError(f"question bank row {row_number} repeats concepts")
            if len({definition for _, definition in concepts}) != 4:
                raise DataQualityError(f"question bank row {row_number} repeats definitions")
            result[skill_code] = concepts
    expected = {
        f"{course_code}-K{index}"
        for course_code, _, _ in COURSE_CATALOG
        for index in range(1, 4)
    }
    if set(result) != expected:
        missing = sorted(expected - set(result))
        extra = sorted(set(result) - expected)
        raise DataQualityError(
            f"question bank skills differ from the course catalog; missing={missing}, extra={extra}"
        )
    return result


def _choice_options(
    labels: list[str], *, correct_label: str, rotation: int
) -> tuple[str, str]:
    if len(labels) != 4 or len(set(labels)) != 4 or correct_label not in labels:
        raise DataQualityError("question options must contain four unique labels and one answer")
    offset = rotation % len(labels)
    ordered = labels[offset:] + labels[:offset]
    choices = ("A", "B", "C", "D")
    options = [
        {"choiceId": choice, "label": label}
        for choice, label in zip(choices, ordered, strict=True)
    ]
    answer = choices[ordered.index(correct_label)]
    return json.dumps(options, ensure_ascii=False, separators=(",", ":")), answer


def _authored_questions(
    concepts: tuple[tuple[str, str], ...], *, skill_name: str, skill_number: int
) -> list[tuple[str, str, str, float]]:
    terms = [concept for concept, _ in concepts]
    definitions = [definition for _, definition in concepts]
    records: list[tuple[str, str, str, float]] = []
    definition_prompts = (
        "“{definition}”对应哪个概念？",
        "在“{skill}”中，哪一术语表示“{definition}”？",
        "根据定义“{definition}”，应选择哪一概念？",
        "下列哪个概念的含义是“{definition}”？",
    )
    term_prompts = (
        "关于“{term}”，哪项定义正确？",
        "在“{skill}”中，“{term}”指什么？",
        "下列哪项准确说明了“{term}”？",
        "选择与术语“{term}”匹配的定义。",
    )
    for index, (term, definition) in enumerate(concepts):
        options, answer = _choice_options(
            terms.copy(), correct_label=term, rotation=skill_number + index
        )
        records.append(
            (
                definition_prompts[index].format(
                    definition=definition, skill=skill_name
                ),
                options,
                answer,
                0.25 + index * 0.05,
            )
        )
    for index, (term, definition) in enumerate(concepts):
        options, answer = _choice_options(
            definitions.copy(), correct_label=definition, rotation=skill_number + index + 2
        )
        records.append(
            (
                term_prompts[index].format(term=term, skill=skill_name),
                options,
                answer,
                0.45 + index * 0.05,
            )
        )
    correct_pair = f"{concepts[0][0]}：{concepts[0][1]}"
    matching_labels = [
        correct_pair,
        f"{concepts[1][0]}：{concepts[2][1]}",
        f"{concepts[2][0]}：{concepts[3][1]}",
        f"{concepts[3][0]}：{concepts[1][1]}",
    ]
    options, answer = _choice_options(
        matching_labels, correct_label=correct_pair, rotation=skill_number
    )
    records.append((f"关于“{skill_name}”，哪组术语与定义匹配？", options, answer, 0.65))
    incorrect_pair = f"{concepts[3][0]}：{concepts[0][1]}"
    comparison_labels = [
        f"{concepts[0][0]}：{concepts[0][1]}",
        f"{concepts[1][0]}：{concepts[1][1]}",
        f"{concepts[2][0]}：{concepts[2][1]}",
        incorrect_pair,
    ]
    options, answer = _choice_options(
        comparison_labels, correct_label=incorrect_pair, rotation=skill_number + 1
    )
    records.append((f"关于“{skill_name}”，哪组术语与定义不匹配？", options, answer, 0.75))
    return records


def _build_question_bank(
    namespace: UUID,
    question_bank: Mapping[str, tuple[tuple[str, str], ...]],
) -> tuple[pd.DataFrame, pd.DataFrame, dict[str, list[dict[str, Any]]]]:
    skill_records: list[dict[str, Any]] = []
    question_records: list[dict[str, Any]] = []
    questions_by_skill: dict[str, list[dict[str, Any]]] = {}
    questions_per_skill = QUESTION_COUNT // SKILL_COUNT
    for skill_number in range(1, SKILL_COUNT + 1):
        course_number = (skill_number - 1) // 3
        course_code, course_title, _ = COURSE_CATALOG[course_number]
        skill_code = f"{course_code}-K{(skill_number - 1) % 3 + 1}"
        skill_id = _deterministic_id(namespace, "skill", f"{skill_number:03d}")
        skill_name = COURSE_SKILLS[course_code][(skill_number - 1) % 3]
        skill_records.append(
            {
                "knowledge_skill_id": skill_id,
                "skill_code": skill_code,
                "name": skill_name,
                "data_version": DEMO_DATA_VERSION,
                "content_origin": "CURATED_SYNTHETIC",
                "synthetic": True,
            }
        )
        questions_by_skill[skill_id] = []
        authored = _authored_questions(
            question_bank[skill_code], skill_name=skill_name, skill_number=skill_number
        )
        if len(authored) != questions_per_skill:
            raise DataQualityError(f"question bank skill {skill_code} must produce 10 questions")
        for local_number, (prompt, options_json, correct_choice, difficulty) in enumerate(
            authored, start=1
        ):
            question_number = (skill_number - 1) * questions_per_skill + local_number
            question_id = _deterministic_id(namespace, "question", f"{question_number:04d}")
            question = {
                "question_id": question_id,
                "question_key": f"{course_code}-Q{(question_number - course_number * 30):02d}",
                "knowledge_skill_id": skill_id,
                "prompt_text": prompt,
                "answer_type": "SINGLE_CHOICE",
                "options_json": options_json,
                "correct_answer": correct_choice,
                "difficulty": float(difficulty),
                "data_version": DEMO_DATA_VERSION,
                "knowledge_model_mode": "ONLINE_BKT",
                "content_origin": "TEACHER_AUTHORED",
                "active": True,
                "synthetic": True,
            }
            question_records.append(question)
            questions_by_skill[skill_id].append(question)
    return (
        pd.DataFrame.from_records(skill_records),
        pd.DataFrame.from_records(question_records),
        questions_by_skill,
    )


def _build_students_and_matches(
    matches: pd.DataFrame,
    courses: pd.DataFrame,
    *,
    namespace: UUID,
    seed: int,
) -> tuple[pd.DataFrame, pd.DataFrame]:
    student_records: list[dict[str, Any]] = []
    match_records: list[dict[str, Any]] = []
    major_ranges: list[str] = []
    for major, count in MAJOR_DISTRIBUTION:
        major_ranges.extend([major] * count)
    given_names = (
        "子涵",
        "宇轩",
        "浩然",
        "梓轩",
        "雨桐",
        "欣怡",
        "思远",
        "嘉宁",
        "博文",
        "若琳",
        "佳琪",
        "明哲",
        "俊杰",
        "诗涵",
        "语嫣",
        "一诺",
        "晨曦",
        "可欣",
        "天佑",
        "思齐",
        "昊宇",
        "梦瑶",
        "书航",
        "芷晴",
        "泽宇",
        "雅雯",
        "景行",
        "知夏",
        "清越",
        "安然",
        "亦辰",
        "沐阳",
        "星辰",
        "思源",
        "嘉懿",
        "文昊",
        "雨泽",
        "语彤",
        "佳航",
        "睿哲",
        "舒涵",
        "锦程",
        "心怡",
        "卓然",
    )
    for raw_match in matches.to_dict("records"):
        match = cast(dict[str, Any], raw_match)
        match_order = int(match["match_order"])
        split = str(match["split"])
        student_id = _deterministic_id(namespace, "student", split, f"{match_order:04d}")
        dimensions = _fused_dimensions(match)
        risk_probability = float(
            np.clip(
                1.0
                - (
                    0.55 * dimensions["performance"]
                    + 0.20 * dimensions["activity"]
                    + 0.25 * dimensions["persistence"]
                ),
                0.02,
                0.98,
            )
        )
        cohort = 2022 + ((match_order - 1) % 4)
        major = major_ranges[match_order - 1]
        major_code = {
            "计算机科学与技术": "01",
            "软件工程": "02",
            "人工智能": "03",
            "数据科学与大数据技术": "04",
        }[major]
        class_number = ((match_order - 1) // 40) % 13 + 1
        student_number = f"{cohort}{major_code}{match_order:04d}"
        display_name = (
            FAMILY_NAMES[(match_order * 17) % len(FAMILY_NAMES)]
            + given_names[(match_order * 29) % len(given_names)]
        )
        student_records.append(
            {
                "student_id": student_id,
                "username": student_number,
                "display_name": display_name,
                "student_number": student_number,
                "college": "计算机与数据科学学院",
                "major": major,
                "cohort_year": cohort,
                "class_name": f"{major}{cohort}级{class_number:02d}班",
                "organization_id": _deterministic_id(
                    namespace,
                    "organization",
                    f"CLASS-{MAJOR_CODES[major]}-{cohort}-{class_number:02d}",
                ),
                "synthetic_key": f"SYN-{match_order:04d}",
                "synthetic": True,
                "split": split,
                **dimensions,
                "initial_risk_probability": risk_probability,
            }
        )
        match_records.append(
            {
                "student_id": student_id,
                "match_order": match_order,
                "split": split,
                "distance": _as_finite_float(match["distance"], field="distance"),
                **dimensions,
            }
        )
    return pd.DataFrame.from_records(student_records), pd.DataFrame.from_records(match_records)


def _build_organizations(
    namespace: UUID, students: pd.DataFrame
) -> tuple[pd.DataFrame, pd.DataFrame]:
    records: list[dict[str, Any]] = []

    def add(code: str, name: str, unit_type: str, parent_code: str | None) -> None:
        records.append(
            {
                "organization_id": _deterministic_id(namespace, "organization", code),
                "code": code,
                "display_name": name,
                "unit_type": unit_type,
                "parent_id": ""
                if parent_code is None
                else _deterministic_id(namespace, "organization", parent_code),
                "enabled": True,
            }
        )

    add("COLLEGE-CDS", "计算机与数据科学学院", "COLLEGE", None)
    add("DEPT-CS", "计算机科学系", "DEPARTMENT", "COLLEGE-CDS")
    add("DEPT-DS", "数据科学系", "DEPARTMENT", "COLLEGE-CDS")
    for major, _ in MAJOR_DISTRIBUTION:
        major_code = MAJOR_CODES[major]
        add(f"MAJOR-{major_code}", major, "MAJOR", "COLLEGE-CDS")
    class_rows = students[
        ["major", "cohort_year", "class_name", "organization_id"]
    ].drop_duplicates()
    for row in class_rows.sort_values(["major", "cohort_year", "class_name"]).to_dict("records"):
        class_number = str(row["class_name"]).removesuffix("班").rsplit("级", 1)[-1]
        major_code = MAJOR_CODES[str(row["major"])]
        records.append(
            {
                "organization_id": row["organization_id"],
                "code": f"CLASS-{major_code}-{int(row['cohort_year'])}-{class_number}",
                "display_name": row["class_name"],
                "unit_type": "CLASS",
                "parent_id": _deterministic_id(
                    namespace, "organization", f"MAJOR-{major_code}"
                ),
                "enabled": True,
            }
        )
    terms = pd.DataFrame.from_records(
        [
            {
                "academic_term_id": _deterministic_id(
                    namespace, "academic-term", DEMO_TERM_CODE
                ),
                "code": DEMO_TERM_CODE,
                "display_name": "2026 年春季学期",
                "starts_on": (
                    DEMO_REFERENCE_TIME - pd.Timedelta(weeks=16)
                ).date().isoformat(),
                "ends_on": (DEMO_REFERENCE_TIME + pd.Timedelta(weeks=6)).date().isoformat(),
                "enabled": True,
            }
        ]
    )
    organizations = pd.DataFrame.from_records(records)
    require_unique(organizations, ["organization_id"], "synthetic organizations")
    require_unique(organizations, ["code"], "synthetic organization codes")
    return organizations, terms


def _build_enrollments(students: pd.DataFrame, courses: pd.DataFrame, *, seed: int) -> pd.DataFrame:
    records: list[dict[str, Any]] = []
    course_ids = courses["course_id"].tolist()
    for order, student in enumerate(students.to_dict("records"), start=1):
        ranked = sorted(
            range(COURSE_COUNT),
            key=lambda index: stable_rank(f"{order}|{index}", "edutwin-enrollment-v2", seed),
        )
        active_count = 5 if order % 5 == 0 else 4
        for course_index in ranked[:active_count]:
            records.append(
                {
                    "course_id": course_ids[course_index],
                    "student_id": student["student_id"],
                    "status": "ACTIVE",
                }
            )
        if order % 20 == 0:
            records.append(
                {
                    "course_id": course_ids[ranked[active_count]],
                    "student_id": student["student_id"],
                    "status": "WITHDRAWN",
                }
            )
    return pd.DataFrame.from_records(records)


def _build_course_content(
    namespace: UUID, courses: pd.DataFrame, questions: pd.DataFrame
) -> dict[str, pd.DataFrame]:
    course_questions: list[dict[str, Any]] = []
    sections: list[dict[str, Any]] = []
    lessons: list[dict[str, Any]] = []
    assessments: list[dict[str, Any]] = []
    assessment_questions: list[dict[str, Any]] = []
    question_rows = questions.to_dict("records")
    section_titles = ("课程导学", "基础知识", "核心方法", "实践训练", "综合应用", "复习与拓展")
    for course_index, course in enumerate(courses.to_dict("records"), start=1):
        scoped_questions = question_rows[(course_index - 1) * 30 : course_index * 30]
        for ordinal, question in enumerate(scoped_questions, start=1):
            course_questions.append(
                {
                    "course_id": course["course_id"],
                    "question_id": question["question_id"],
                    "ordinal": ordinal,
                }
            )
        for section_position, section_title in enumerate(section_titles, start=1):
            section_id = _deterministic_id(
                namespace, "section", f"{course_index:02d}", f"{section_position:02d}"
            )
            sections.append(
                {
                    "section_id": section_id,
                    "course_id": course["course_id"],
                    "title": section_title,
                    "description": f"{course['title']}的{section_title}学习单元。",
                    "position": section_position,
                    "status": "PUBLISHED",
                }
            )
            for lesson_position in range(1, 4):
                lesson_id = _deterministic_id(
                    namespace,
                    "lesson",
                    f"{course_index:02d}",
                    f"{section_position:02d}",
                    f"{lesson_position:02d}",
                )
                lessons.append(
                    {
                        "lesson_id": lesson_id,
                        "section_id": section_id,
                        "title": f"{section_title} {lesson_position}",
                        "summary": f"{course['title']}第{section_position}单元学习内容。",
                        "body": (
                            f"本节围绕{course['title']}的{section_title}"
                            "组织概念讲解、例题分析和练习任务。"
                        ),
                        "resource_url": (
                            f"/resources/{course['course_code'].lower()}/"
                            f"{section_position}-{lesson_position}"
                        ),
                        "position": lesson_position,
                        "status": "PUBLISHED",
                    }
                )
        for assessment_position in range(1, 6):
            assessment_id = _deterministic_id(
                namespace, "assessment", f"{course_index:02d}", f"{assessment_position:02d}"
            )
            assessment_type = "QUIZ" if assessment_position != 4 else "ASSIGNMENT"
            due_offsets = {1: -28, 2: -14, 3: 7, 4: 21}
            due_at = (
                None
                if assessment_position == 5
                else DEMO_REFERENCE_TIME
                + pd.Timedelta(days=due_offsets[assessment_position], hours=15)
            )
            status = "CLOSED" if assessment_position in (1, 2) else "PUBLISHED"
            title = (
                f"{course['title']}不限时体验测验"
                if assessment_position == 5
                else f"{course['title']}阶段考核 {assessment_position}"
            )
            description = (
                "使用课程语义题熟悉答题与结果回顾流程。"
                if assessment_position == 5
                else f"检验第{assessment_position}阶段课程目标达成情况。"
            )
            assessments.append(
                {
                    "assessment_id": assessment_id,
                    "course_id": course["course_id"],
                    "title": title,
                    "description": description,
                    "assessment_type": assessment_type,
                    "status": status,
                    "due_at": "" if due_at is None else due_at.isoformat(),
                    "published_at": (
                        DEMO_REFERENCE_TIME - pd.Timedelta(days=100)
                    ).isoformat(),
                    "closed_at": ""
                    if due_at is None or status != "CLOSED"
                    else (due_at + pd.Timedelta(days=1)).isoformat(),
                }
            )
            for position, question in enumerate(
                scoped_questions[(assessment_position - 1) * 5 : assessment_position * 5], start=1
            ):
                assessment_questions.append(
                    {
                        "assessment_question_id": _deterministic_id(
                            namespace, "assessment-question", assessment_id, f"{position:02d}"
                        ),
                        "assessment_id": assessment_id,
                        "source_question_id": question["question_id"],
                        "prompt": question["prompt_text"],
                        "options_json": json.dumps(
                            json.loads(question["options_json"]),
                            ensure_ascii=False,
                            separators=(",", ":"),
                        ),
                        "correct_choice_id": question["correct_answer"],
                        "points": 1,
                        "position": position,
                    }
                )
    return {
        "course_questions": pd.DataFrame.from_records(course_questions),
        "lms_sections": pd.DataFrame.from_records(sections),
        "lms_lessons": pd.DataFrame.from_records(lessons),
        "lms_assessments": pd.DataFrame.from_records(assessments),
        "lms_assessment_questions": pd.DataFrame.from_records(assessment_questions),
    }


def _build_lesson_progress(
    students: pd.DataFrame,
    enrollments: pd.DataFrame,
    content: Mapping[str, pd.DataFrame],
) -> pd.DataFrame:
    students_by_id = {row["student_id"]: row for row in students.to_dict("records")}
    section_course = dict(
        zip(
            content["lms_sections"]["section_id"],
            content["lms_sections"]["course_id"],
            strict=True,
        )
    )
    lessons_by_course: dict[str, list[str]] = {}
    for lesson in content["lms_lessons"].to_dict("records"):
        lessons_by_course.setdefault(section_course[lesson["section_id"]], []).append(
            lesson["lesson_id"]
        )
    records: list[dict[str, Any]] = []
    active = enrollments.loc[enrollments["status"].eq("ACTIVE")]
    for enrollment in active.to_dict("records"):
        student = students_by_id[enrollment["student_id"]]
        completed_count = int(
            np.clip(
                2
                + round(
                    8 * float(student["activity"])
                    + 5 * float(student["persistence"])
                    + 3 * float(student["performance"])
                ),
                2,
                18,
            )
        )
        for position, lesson_id in enumerate(
            lessons_by_course[enrollment["course_id"]][:completed_count], start=1
        ):
            records.append(
                {
                    "lesson_id": lesson_id,
                    "student_id": enrollment["student_id"],
                    "completed_at": (
                        DEMO_REFERENCE_TIME
                        - pd.Timedelta(days=max(1, 70 - position * 3))
                    ).isoformat(),
                }
            )
    result = pd.DataFrame.from_records(records)
    require_unique(result, ["lesson_id", "student_id"], "synthetic lesson progress")
    return result


def _build_assessment_submissions(
    students: pd.DataFrame,
    enrollments: pd.DataFrame,
    content: Mapping[str, pd.DataFrame],
    *,
    namespace: UUID,
    seed: int,
) -> tuple[pd.DataFrame, pd.DataFrame]:
    students_by_id = {row["student_id"]: row for row in students.to_dict("records")}
    first_assessments = {
        course_id: group.sort_values("due_at").iloc[0].to_dict()
        for course_id, group in content["lms_assessments"].groupby("course_id")
    }
    questions_by_assessment = {
        assessment_id: group.sort_values("position").to_dict("records")
        for assessment_id, group in content["lms_assessment_questions"].groupby(
            "assessment_id"
        )
    }
    submissions: list[dict[str, Any]] = []
    answers: list[dict[str, Any]] = []
    choices = ("A", "B", "C", "D")
    active = enrollments.loc[enrollments["status"].eq("ACTIVE")]
    for enrollment_number, enrollment in enumerate(active.to_dict("records"), start=1):
        student = students_by_id[enrollment["student_id"]]
        assessment = first_assessments[enrollment["course_id"]]
        attempt_count = 2 if enrollment_number % 10 == 0 else 1
        for attempt_number in range(1, attempt_count + 1):
            submission_id = _deterministic_id(
                namespace,
                "submission",
                enrollment["student_id"],
                assessment["assessment_id"],
                str(attempt_number),
            )
            rng = np.random.default_rng(
                stable_rank(submission_id, "edutwin-assessment-attempt-v1", seed)
                % (2**63 - 1)
            )
            score = 0
            for question in questions_by_assessment[assessment["assessment_id"]]:
                probability = float(
                    np.clip(
                        0.08
                        + 0.62 * float(student["performance"])
                        + 0.16 * float(student["persistence"])
                        + 0.10 * (attempt_number - 1),
                        0.05,
                        0.98,
                    )
                )
                correct = bool(rng.random() < probability)
                correct_choice = str(question["correct_choice_id"])
                wrong_choices = [choice for choice in choices if choice != correct_choice]
                selected = (
                    correct_choice
                    if correct
                    else wrong_choices[int(rng.integers(0, len(wrong_choices)))]
                )
                awarded = int(question["points"]) if correct else 0
                score += awarded
                answers.append(
                    {
                        "submission_id": submission_id,
                        "assessment_question_id": question["assessment_question_id"],
                        "selected_choice_id": selected,
                        "correct": correct,
                        "points_awarded": awarded,
                    }
                )
            idempotency_payload = f"demo|{submission_id}|{attempt_number}".encode()
            request_payload = json.dumps(
                {
                    "assessmentId": assessment["assessment_id"],
                    "studentId": enrollment["student_id"],
                    "attemptNumber": attempt_number,
                },
                sort_keys=True,
                separators=(",", ":"),
            ).encode()
            submissions.append(
                {
                    "submission_id": submission_id,
                    "assessment_id": assessment["assessment_id"],
                    "student_id": enrollment["student_id"],
                    "attempt_number": attempt_number,
                    "attempt_type": "INITIAL" if attempt_number == 1 else "RETAKE",
                    "idempotency_key_hash": hashlib.sha256(idempotency_payload).hexdigest(),
                    "request_sha256": hashlib.sha256(request_payload).hexdigest(),
                    "score": score,
                    "max_score": len(questions_by_assessment[assessment["assessment_id"]]),
                    "valid_for_grade": True,
                    "submitted_at": (
                        DEMO_REFERENCE_TIME
                        - pd.Timedelta(days=35 - (attempt_number - 1) * 4)
                        + pd.Timedelta(hours=enrollment_number % 12)
                    ).isoformat(),
                }
            )
    submission_frame = pd.DataFrame.from_records(submissions)
    answer_frame = pd.DataFrame.from_records(answers)
    require_unique(submission_frame, ["submission_id"], "synthetic submissions")
    require_unique(
        submission_frame,
        ["assessment_id", "student_id", "attempt_number"],
        "synthetic assessment attempts",
    )
    require_unique(
        answer_frame,
        ["submission_id", "assessment_question_id"],
        "synthetic submission answers",
    )
    return submission_frame, answer_frame


def _build_source_lineage(
    matches: pd.DataFrame,
    *,
    namespace: UUID,
    seed: int,
) -> pd.DataFrame:
    """Build the identifier-only source lineage required by the demo database."""

    z_columns = [
        f"{source}_{dimension}_z"
        for dimension in ("performance", "activity", "persistence")
        for source in ("assist", "oulad")
    ]
    require_columns(matches, [*MATCH_COLUMNS, *z_columns], "student source lineage")
    normalized = _normalize_matches(matches, len(matches))
    records: list[dict[str, Any]] = []
    for match in normalized.to_dict("records"):
        match_order = int(match["match_order"])
        split = str(match["split"])
        records.append(
            {
                "student_id": _deterministic_id(namespace, "student", split, f"{match_order:04d}"),
                "assistments_user_sha256": _source_lineage_sha256(
                    "assistments-user",
                    match["assistments_user_key"],
                    field="matches.assistments_user_key",
                ),
                "oulad_student_sha256": _source_lineage_sha256(
                    "oulad-student",
                    match["oulad_student_key"],
                    field="matches.oulad_student_key",
                ),
                "split": split,
                "performance_z": float(
                    np.mean([match["assist_performance_z"], match["oulad_performance_z"]])
                ),
                "activity_z": float(
                    np.mean([match["assist_activity_z"], match["oulad_activity_z"]])
                ),
                "persistence_z": float(
                    np.mean([match["assist_persistence_z"], match["oulad_persistence_z"]])
                ),
                "match_distance": _as_finite_float(match["distance"], field="distance"),
                "random_seed": seed,
            }
        )
    result = pd.DataFrame.from_records(records)
    require_unique(result, ["student_id"], "student source lineage")
    require_unique(
        result,
        ["assistments_user_sha256"],
        "student source lineage ASSISTments hashes",
    )
    require_unique(result, ["oulad_student_sha256"], "student source lineage OULAD hashes")
    for column in ("assistments_user_sha256", "oulad_student_sha256"):
        if not result[column].str.fullmatch(r"[a-f0-9]{64}").all():
            raise DataQualityError(f"student source lineage {column} is not SHA-256")
    numeric = result[["performance_z", "activity_z", "persistence_z", "match_distance"]].to_numpy(
        dtype="float64"
    )
    if not np.isfinite(numeric).all():
        raise DataQualityError("student source lineage contains non-finite values")
    return result


def _build_knowledge_lineage(
    assist_events: pd.DataFrame,
    assist_event_skills: pd.DataFrame,
    knowledge_skills: pd.DataFrame,
    questions: pd.DataFrame,
    *,
    seed: int,
) -> pd.DataFrame:
    """Map demo UUIDs to unique train-side ASSISTments vocabulary keys."""

    require_columns(
        assist_events,
        ["order_id", "problem_id", "split"],
        "ASSISTments knowledge lineage events",
    )
    require_columns(
        assist_event_skills,
        ["order_id", "skill_id"],
        "ASSISTments knowledge lineage skills",
    )
    events = assist_events.loc[
        assist_events["split"].eq("train"), ["order_id", "problem_id"]
    ].copy()
    events["_source_order"] = events["order_id"].map(
        lambda value: _source_key(value, field="ASSISTments events.order_id")
    )
    events["_source_problem"] = events["problem_id"].map(
        lambda value: _source_key(value, field="ASSISTments events.problem_id")
    )
    require_unique(events, ["_source_order"], "ASSISTments knowledge lineage events")
    skills = assist_event_skills[["order_id", "skill_id"]].copy()
    skills["_source_order"] = skills["order_id"].map(
        lambda value: _source_key(value, field="ASSISTments event skills.order_id")
    )
    skills["_source_skill"] = skills["skill_id"].map(
        lambda value: _source_key(value, field="ASSISTments event skills.skill_id")
    )
    pairs = (
        skills[["_source_order", "_source_skill"]]
        .merge(events[["_source_order", "_source_problem"]], on="_source_order")[
            ["_source_skill", "_source_problem"]
        ]
        .drop_duplicates()
    )
    problems_by_skill = {
        str(skill): tuple(sorted(group["_source_problem"].astype(str).unique()))
        for skill, group in pairs.groupby("_source_skill", sort=False)
    }
    candidate_skills = sorted(
        (
            skill
            for skill, problems in problems_by_skill.items()
            if len(problems) >= QUESTION_COUNT // SKILL_COUNT
        ),
        key=lambda skill: (
            stable_rank(skill, "edutwin-demo-source-skill-v1", seed),
            skill,
        ),
    )
    selected: list[tuple[str, tuple[str, ...]]] = []
    used_problems: set[str] = set()
    questions_per_skill = QUESTION_COUNT // SKILL_COUNT
    for source_skill in candidate_skills:
        available = [
            problem for problem in problems_by_skill[source_skill] if problem not in used_problems
        ]
        available.sort(
            key=lambda problem: (
                stable_rank(
                    f"{source_skill}|{problem}",
                    "edutwin-demo-source-problem-v1",
                    seed,
                ),
                problem,
            )
        )
        if len(available) < questions_per_skill:
            continue
        chosen = tuple(available[:questions_per_skill])
        selected.append((source_skill, chosen))
        used_problems.update(chosen)
        if len(selected) == SKILL_COUNT:
            break
    if len(selected) != SKILL_COUNT:
        raise DataQualityError(
            "ASSISTments train vocabulary cannot provide 60 skills with "
            "10 globally unique problems each"
        )

    skill_rows = knowledge_skills.sort_values("skill_code", kind="mergesort").to_dict("records")
    records: list[dict[str, Any]] = []
    for skill_row, (source_skill, source_problems) in zip(skill_rows, selected, strict=True):
        question_rows = questions.loc[
            questions["knowledge_skill_id"].eq(skill_row["knowledge_skill_id"])
        ].sort_values("question_key", kind="mergesort")
        if len(question_rows) != questions_per_skill:
            raise DataQualityError("synthetic question bank is not balanced by skill")
        for question_row, source_problem in zip(
            question_rows.to_dict("records"), source_problems, strict=True
        ):
            records.append(
                {
                    "knowledge_skill_id": skill_row["knowledge_skill_id"],
                    "source_skill_key": source_skill,
                    "question_id": question_row["question_id"],
                    "source_problem_key": source_problem,
                }
            )
    result = pd.DataFrame.from_records(records)
    require_unique(result, ["question_id"], "knowledge source lineage questions")
    require_unique(result, ["source_problem_key"], "knowledge source lineage problem keys")
    skill_mapping = result[["knowledge_skill_id", "source_skill_key"]].drop_duplicates()
    require_unique(skill_mapping, ["knowledge_skill_id"], "knowledge source lineage skills")
    require_unique(skill_mapping, ["source_skill_key"], "knowledge source lineage keys")
    return result


BEHAVIOR_PROFILES = (
    "STEADY",
    "IMPROVING",
    "DISENGAGING",
    "CRAMMING",
    "RECOVERING",
)


def _behavior_profile(student: Mapping[str, Any], course_id: str, seed: int) -> str:
    rank = stable_rank(
        f"{student['synthetic_key']}|{course_id}",
        "edutwin-behavior-profile-v1",
        seed,
    )
    return BEHAVIOR_PROFILES[rank % len(BEHAVIOR_PROFILES)]


def _behavior_week_weights(profile: str) -> np.ndarray:
    progress = np.linspace(0.0, 1.0, 16)
    values: np.ndarray
    if profile == "IMPROVING":
        values = 0.45 + 1.35 * progress
    elif profile == "DISENGAGING":
        values = 1.75 - 1.35 * progress
    elif profile == "CRAMMING":
        values = np.full(16, 0.45)
        values[[3, 7, 11, 15]] = 2.8
        values[[2, 6, 10, 14]] = 1.35
    elif profile == "RECOVERING":
        values = 0.55 + 2.2 * np.abs(progress - 0.52)
    else:
        values = np.ones(16)
    normalized: np.ndarray = values / values.sum()
    return normalized


def _behavior_timestamps(
    rng: np.random.Generator,
    count: int,
    profile: str,
    base_time: pd.Timestamp,
    *,
    include_early_hours: bool,
) -> list[pd.Timestamp]:
    if count <= 0:
        return []
    weeks = rng.choice(np.arange(16), size=count, p=_behavior_week_weights(profile))
    weekdays = rng.choice(
        np.arange(7), size=count, p=(0.17, 0.18, 0.18, 0.17, 0.14, 0.09, 0.07)
    )
    hours: tuple[int, ...]
    probabilities: tuple[float, ...]
    if include_early_hours:
        hours = (8, 10, 14, 18, 19, 20, 21, 22)
        probabilities = (0.05, 0.07, 0.10, 0.11, 0.15, 0.20, 0.20, 0.12)
    else:
        hours = (9, 14, 19, 20, 21, 22)
        probabilities = (0.08, 0.12, 0.18, 0.25, 0.24, 0.13)
    if profile == "CRAMMING":
        probabilities = tuple(
            value * (1.45 if hour >= 20 else 0.65)
            for hour, value in zip(hours, probabilities, strict=True)
        )
        total = sum(probabilities)
        probabilities = tuple(value / total for value in probabilities)
    selected_hours = rng.choice(hours, size=count, p=probabilities)
    minutes = rng.integers(0, 60, size=count)
    values = [
        base_time
        + pd.Timedelta(
            weeks=int(week),
            days=int(weekday),
            hours=int(hour),
            minutes=int(minute),
        )
        for week, weekday, hour, minute in zip(
            weeks, weekdays, selected_hours, minutes, strict=True
        )
    ]
    return sorted(values)


def _performance_adjustment(profile: str, progress: float) -> float:
    if profile == "IMPROVING":
        return -0.10 + 0.32 * progress
    if profile == "DISENGAGING":
        return 0.14 - 0.22 * progress
    if profile == "CRAMMING":
        return -0.04 + 0.18 * progress**3
    if profile == "RECOVERING":
        return -0.08 + 0.30 * abs(progress - 0.5)
    return 0.06 + 0.08 * progress


def _build_answer_events(
    students: pd.DataFrame,
    enrollments: pd.DataFrame,
    courses: pd.DataFrame,
    knowledge_skills: pd.DataFrame,
    questions_by_skill: Mapping[str, list[dict[str, Any]]],
    *,
    namespace: UUID,
    seed: int,
) -> tuple[pd.DataFrame, pd.DataFrame]:
    event_records: list[dict[str, Any]] = []
    association_records: list[dict[str, Any]] = []
    skill_ids = knowledge_skills["knowledge_skill_id"].tolist()
    course_ids = courses["course_id"].tolist()
    course_index = {course_id: index for index, course_id in enumerate(course_ids)}
    students_by_id = {row["student_id"]: row for row in students.to_dict("records")}
    activity_percentiles = (
        students.set_index("student_id")["activity"].astype(float).rank(method="average", pct=True)
    ).to_dict()
    choices = ("A", "B", "C", "D")
    base_time = DEMO_REFERENCE_TIME - pd.Timedelta(weeks=16)
    active_enrollments = enrollments.loc[enrollments["status"].eq("ACTIVE")]
    for enrollment_number, enrollment in enumerate(active_enrollments.to_dict("records"), start=1):
        student = students_by_id[enrollment["student_id"]]
        match_order = int(str(student["synthetic_key"]).removeprefix("SYN-"))
        split = str(student["split"])
        student_seed = stable_rank(
            f"{split}|{match_order:04d}|{enrollment['course_id']}",
            "edutwin-synthetic-behavior-v3",
            seed,
        ) % (2**63 - 1)
        rng = np.random.default_rng(student_seed)
        scoped_course_index = course_index[enrollment["course_id"]]
        scoped_skills = skill_ids[scoped_course_index * 3 : scoped_course_index * 3 + 3]
        activity_percentile = float(activity_percentiles[enrollment["student_id"]])
        event_count = round(
            MIN_ANSWER_EVENTS_PER_ENROLLMENT
            * (1.0 - activity_percentile)
            + MAX_ANSWER_EVENTS_PER_ENROLLMENT * activity_percentile
        )
        profile = _behavior_profile(cast(Mapping[str, Any], student), enrollment["course_id"], seed)
        occurred_values = _behavior_timestamps(
            rng, event_count, profile, base_time, include_early_hours=False
        )
        for sequence, occurred_at in enumerate(occurred_values, start=1):
            skill_id = scoped_skills[(sequence - 1) % 3]
            candidates = questions_by_skill[skill_id]
            question = candidates[int(rng.integers(0, len(candidates)))]
            progress = (sequence - 1) / max(1, event_count - 1)
            probability = (
                0.10
                + 0.55 * float(student["performance"])
                + 0.15 * float(student["activity"])
                + 0.15 * float(student["persistence"])
                + _performance_adjustment(profile, progress)
                - 0.25 * (float(question["difficulty"]) - 0.5)
            )
            correct_probability = float(np.clip(probability, 0.05, 0.98))
            correct = bool(rng.random() < correct_probability)
            correct_choice = str(question["correct_answer"])
            if correct:
                selected_choice = correct_choice
            else:
                wrong_choices = [choice for choice in choices if choice != correct_choice]
                selected_choice = wrong_choices[int(rng.integers(0, len(wrong_choices)))]
            response_center = (
                38_000
                - 14_000 * float(student["activity"])
                - 8_000 * float(student["persistence"])
                + 12_000 * float(question["difficulty"])
            )
            response_time_ms = int(np.clip(rng.normal(response_center, 4_000), 2_000, 120_000))
            hint_probability = 0.55 - 0.35 * float(student["persistence"])
            hint_count = 0 if correct else int(rng.random() < hint_probability)
            attempt_number = 1 if correct else 1 + int(rng.random() < 0.45)
            answer_event_id = _deterministic_id(
                namespace,
                "answer-event",
                f"{match_order:04d}",
                f"{scoped_course_index + 1:02d}",
                f"{sequence:03d}",
            )
            event_records.append(
                {
                    "answer_event_id": answer_event_id,
                    "event_key": f"DEMO-E-{enrollment_number:05d}-{sequence:03d}",
                    "student_id": student["student_id"],
                    "course_id": enrollment["course_id"],
                    "question_id": question["question_id"],
                    "event_sequence": sequence,
                    "selected_choice": selected_choice,
                    "correct": correct,
                    "correct_probability": correct_probability,
                    "response_time_ms": response_time_ms,
                    "attempt_number": attempt_number,
                    "hint_count": hint_count,
                    "occurred_at": occurred_at.isoformat(),
                    "split": split,
                    "synthetic": True,
                }
            )
            association_records.append(
                {
                    "answer_event_id": answer_event_id,
                    "knowledge_skill_id": skill_id,
                    "ordinal": 1,
                    "synthetic": True,
                }
            )
    return pd.DataFrame.from_records(event_records), pd.DataFrame.from_records(association_records)


def _build_learning_activity_events(
    students: pd.DataFrame,
    enrollments: pd.DataFrame,
    content: Mapping[str, pd.DataFrame],
    answer_events: pd.DataFrame,
    *,
    namespace: UUID,
    seed: int,
) -> pd.DataFrame:
    records: list[dict[str, Any]] = []
    students_by_id = {row["student_id"]: row for row in students.to_dict("records")}
    activity_percentiles = (
        students.set_index("student_id")["activity"].astype(float).rank(method="average", pct=True)
    ).to_dict()
    lessons_by_course: dict[str, list[str]] = {}
    section_course = dict(
        zip(
            content["lms_sections"]["section_id"],
            content["lms_sections"]["course_id"],
            strict=True,
        )
    )
    for lesson in content["lms_lessons"].to_dict("records"):
        lessons_by_course.setdefault(section_course[lesson["section_id"]], []).append(
            lesson["lesson_id"]
        )
    assessments_by_course = {
        course_id: group["assessment_id"].tolist()
        for course_id, group in content["lms_assessments"].groupby("course_id")
    }
    answers_by_enrollment = {
        (student_id, course_id): group.to_dict("records")
        for (student_id, course_id), group in answer_events.groupby(
            ["student_id", "course_id"], sort=False
        )
    }
    active = enrollments.loc[enrollments["status"].eq("ACTIVE")]
    base_time = DEMO_REFERENCE_TIME - pd.Timedelta(weeks=16)
    for enrollment_number, enrollment in enumerate(active.to_dict("records"), start=1):
        student = students_by_id[enrollment["student_id"]]
        rng = np.random.default_rng(
            stable_rank(
                f"{student['synthetic_key']}|{enrollment['course_id']}", "edutwin-activity-v3", seed
            )
            % (2**63 - 1)
        )
        lessons = lessons_by_course[enrollment["course_id"]]
        assessments = assessments_by_course[enrollment["course_id"]]
        answers = sorted(
            answers_by_enrollment[(enrollment["student_id"], enrollment["course_id"])],
            key=lambda value: value["occurred_at"],
        )
        activity_percentile = float(activity_percentiles[enrollment["student_id"]])
        target_count = round(
            MIN_ACTIVITY_EVENTS_PER_ENROLLMENT
            * (1.0 - activity_percentile)
            + MAX_ACTIVITY_EVENTS_PER_ENROLLMENT * activity_percentile
        )
        profile = _behavior_profile(cast(Mapping[str, Any], student), enrollment["course_id"], seed)
        prepared: list[dict[str, Any]] = []

        for answer in answers:
            submitted_at = pd.Timestamp(answer["occurred_at"])
            prepared.append({
                "session_key": f"practice|{answer['answer_event_id']}",
                "event_type": "PRACTICE_START",
                "occurred_at": submitted_at - pd.Timedelta(minutes=int(rng.integers(4, 16))),
                "resource_type": "QUESTION",
                "resource_id": answer["question_id"],
                "answer_event_id": None,
                "progress_percent": None,
            })
            prepared.append({
                "session_key": f"practice|{answer['answer_event_id']}",
                "event_type": "PRACTICE_SUBMIT",
                "occurred_at": submitted_at,
                "resource_type": "QUESTION",
                "resource_id": answer["question_id"],
                "answer_event_id": answer["answer_event_id"],
                "progress_percent": None,
            })

        assessment_times = _behavior_timestamps(
            rng, len(assessments), profile, base_time, include_early_hours=False
        )
        for assessment, submitted_at in zip(assessments, assessment_times, strict=True):
            prepared.append({
                "session_key": f"assessment|{assessment}",
                "event_type": "ASSESSMENT_OPEN",
                "occurred_at": submitted_at - pd.Timedelta(minutes=int(rng.integers(20, 50))),
                "resource_type": "ASSESSMENT",
                "resource_id": assessment,
                "answer_event_id": None,
                "progress_percent": None,
            })
            prepared.append({
                "session_key": f"assessment|{assessment}",
                "event_type": "ASSESSMENT_SUBMIT",
                "occurred_at": submitted_at,
                "resource_type": "ASSESSMENT",
                "resource_id": assessment,
                "answer_event_id": None,
                "progress_percent": None,
            })

        remaining = target_count - len(prepared)
        content_times = _behavior_timestamps(
            rng, remaining, profile, base_time, include_early_hours=True
        )
        content_types = (
            "COURSE_ACCESS",
            "LESSON_VIEW",
            "VIDEO_PROGRESS",
            "RESOURCE_VIEW",
            "RESOURCE_DOWNLOAD",
            "DISCUSSION_VIEW",
        )
        content_probabilities = (0.22, 0.28, 0.17, 0.14, 0.07, 0.12)
        video_progress: dict[str, float] = {}
        for index, occurred_at in enumerate(content_times):
            event_type = str(rng.choice(content_types, p=content_probabilities))
            resource_type: str | None = None
            resource_id: str | None = None
            progress: float | None = None
            if event_type != "COURSE_ACCESS":
                resource_type = "LESSON"
                resource_id = lessons[index % len(lessons)]
            if event_type == "VIDEO_PROGRESS" and resource_id is not None:
                previous = video_progress.get(resource_id, 0.0)
                progress = round(float(min(100.0, previous + rng.uniform(8, 28))), 2)
                video_progress[resource_id] = progress
            prepared.append({
                "session_key": f"content|{index // 4 + 1:03d}",
                "event_type": event_type,
                "occurred_at": occurred_at,
                "resource_type": resource_type,
                "resource_id": resource_id,
                "answer_event_id": None,
                "progress_percent": progress,
            })

        prepared.sort(key=lambda value: (value["occurred_at"], value["event_type"]))
        for sequence, value in enumerate(prepared, start=1):
            duration = int(np.clip(
                rng.lognormal(4.2 + 0.5 * float(student["persistence"]), 0.7), 5, 3600
            ))
            session_id = _deterministic_id(
                namespace,
                "activity-session",
                f"{enrollment_number:05d}",
                str(value.pop("session_key")),
            )
            records.append({
                "learning_activity_event_id": _deterministic_id(
                    namespace, "activity", f"{enrollment_number:05d}", f"{sequence:03d}"
                ),
                "course_id": enrollment["course_id"],
                "student_id": enrollment["student_id"],
                **value,
                "occurred_at": value["occurred_at"].isoformat(),
                "duration_seconds": duration,
                "metadata_json": json.dumps(
                    {
                        "device": "mobile" if rng.random() < 0.38 else "desktop",
                        "sessionId": session_id,
                        "behaviorProfile": profile,
                        "synthetic": True,
                    },
                    separators=(",", ":"),
                ),
                "synthetic": True,
                "data_version": DEMO_DATA_VERSION,
            })
    return pd.DataFrame.from_records(records)


def build_synthetic_frames(
    matches: pd.DataFrame,
    assist_events: pd.DataFrame,
    assist_event_skills: pd.DataFrame,
    *,
    namespace: UUID,
    expected_students: int,
    seed: int = 42,
    minimum_source_events_per_student: int = 20,
    question_bank_path: Path | None = None,
) -> dict[str, pd.DataFrame]:
    """Build independent synthetic tables after local source-quality validation."""

    if seed != 42:
        raise DataQualityError("synthetic demo seed must remain 42")
    if expected_students < 1:
        raise DataQualityError("synthetic student count must be positive")
    normalized_matches = _normalize_matches(matches, expected_students)
    _validate_source_quality(
        normalized_matches,
        assist_events,
        assist_event_skills,
        minimum_events_per_source_student=minimum_source_events_per_student,
    )
    courses = _build_courses(namespace)
    teachers, teaching_assignments = _build_teachers(namespace, courses)
    question_bank = _load_question_bank(question_bank_path or QUESTION_BANK_DEFAULT)
    knowledge_skills, questions, questions_by_skill = _build_question_bank(
        namespace, question_bank
    )
    students, anonymous_matches = _build_students_and_matches(
        normalized_matches,
        courses,
        namespace=namespace,
        seed=seed,
    )
    organization_units, academic_terms = _build_organizations(namespace, students)
    enrollments = _build_enrollments(students, courses, seed=seed)
    content = _build_course_content(namespace, courses, questions)
    answer_events, answer_event_skills = _build_answer_events(
        students,
        enrollments,
        courses,
        knowledge_skills,
        questions_by_skill,
        namespace=namespace,
        seed=seed,
    )
    learning_activity_events = _build_learning_activity_events(
        students, enrollments, content, answer_events, namespace=namespace, seed=seed
    )
    lesson_progress = _build_lesson_progress(students, enrollments, content)
    submissions, submission_answers = _build_assessment_submissions(
        students, enrollments, content, namespace=namespace, seed=seed
    )
    answer_counts = answer_events.groupby(["course_id", "student_id"]).size()
    activity_counts = learning_activity_events.groupby(["course_id", "student_id"]).size()
    if (
        answer_counts.min() < MIN_ANSWER_EVENTS_PER_ENROLLMENT
        or answer_counts.max() > MAX_ANSWER_EVENTS_PER_ENROLLMENT
        or answer_counts.nunique() < min(5, expected_students)
    ):
        raise DataQualityError("synthetic answer event distribution differs")
    if (
        activity_counts.min() < MIN_ACTIVITY_EVENTS_PER_ENROLLMENT
        or activity_counts.max() > MAX_ACTIVITY_EVENTS_PER_ENROLLMENT
        or activity_counts.nunique() < min(5, expected_students)
    ):
        raise DataQualityError("synthetic activity event distribution differs")
    require_unique(students, ["student_id"], "synthetic students")
    require_unique(questions, ["question_id"], "synthetic questions")
    require_unique(answer_events, ["answer_event_id"], "synthetic answer events")
    require_unique(answer_events, ["event_key"], "synthetic answer events")
    require_unique(
        answer_events,
        ["course_id", "student_id", "event_sequence"],
        "synthetic course student event sequence",
    )
    require_unique(
        answer_event_skills,
        ["answer_event_id", "knowledge_skill_id"],
        "synthetic answer event skills",
    )
    active_enrollment_keys = set(
        enrollments.loc[enrollments["status"].eq("ACTIVE")]
        .apply(lambda row: (row["course_id"], row["student_id"]), axis=1)
        .tolist()
    )
    assessment_course = dict(
        zip(
            content["lms_assessments"]["assessment_id"],
            content["lms_assessments"]["course_id"],
            strict=True,
        )
    )
    if any(
        (assessment_course[row["assessment_id"]], row["student_id"])
        not in active_enrollment_keys
        for row in submissions.to_dict("records")
    ):
        raise DataQualityError("synthetic submissions include a non-enrolled student")
    answers_by_submission = submission_answers.groupby("submission_id").agg(
        answer_count=("assessment_question_id", "count"),
        score=("points_awarded", "sum"),
    )
    submission_checks = submissions.set_index("submission_id").join(
        answers_by_submission, rsuffix="_answers"
    )
    if (
        submission_checks["answer_count"].ne(5).any()
        or submission_checks["score"].ne(submission_checks["score_answers"]).any()
    ):
        raise DataQualityError("synthetic submission answers do not match submission scores")
    submitted_assessments = set(submissions["assessment_id"])
    enrolled_course_ids = {course_id for course_id, _ in active_enrollment_keys}
    for _, course_assessments in content["lms_assessments"].groupby("course_id"):
        if course_assessments.iloc[0]["course_id"] not in enrolled_course_ids:
            continue
        statuses = set(course_assessments["status"])
        course_ids = set(course_assessments["assessment_id"])
        closed_unsubmitted = set(
            course_assessments.loc[
                course_assessments["status"].eq("CLOSED"), "assessment_id"
            ]
        ) - submitted_assessments
        if (
            "PUBLISHED" not in statuses
            or not (course_ids & submitted_assessments)
            or not closed_unsubmitted
        ):
            raise DataQualityError(
                "every synthetic course must include open, submitted, "
                "and closed-unsubmitted assessments"
            )
    return {
        "organization_units": organization_units.reset_index(drop=True),
        "academic_terms": academic_terms.reset_index(drop=True),
        "students": students.reset_index(drop=True),
        "courses": courses.reset_index(drop=True),
        "teachers": teachers.reset_index(drop=True),
        "teaching_assignments": teaching_assignments.reset_index(drop=True),
        "enrollments": enrollments.reset_index(drop=True),
        "knowledge_skills": knowledge_skills.reset_index(drop=True),
        "questions": questions.reset_index(drop=True),
        **{name: frame.reset_index(drop=True) for name, frame in content.items()},
        "lms_lesson_progress": lesson_progress.reset_index(drop=True),
        "lms_submissions": submissions.reset_index(drop=True),
        "lms_submission_answers": submission_answers.reset_index(drop=True),
        "learning_activity_events": learning_activity_events.reset_index(drop=True),
        "answer_events": answer_events.reset_index(drop=True),
        "answer_event_skills": answer_event_skills.reset_index(drop=True),
        "matches": anonymous_matches.reset_index(drop=True),
    }


def _write_parquet(frame: pd.DataFrame, path: Path) -> None:
    temporary = path.with_name(f".{path.name}.tmp")
    if temporary.exists():
        temporary.unlink()
    try:
        frame.to_parquet(temporary, engine="pyarrow", index=False, compression="zstd")
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()


def _write_csv(frame: pd.DataFrame, path: Path) -> None:
    temporary = path.with_name(f".{path.name}.tmp")
    if temporary.exists():
        temporary.unlink()
    try:
        frame.to_csv(
            temporary,
            index=False,
            encoding="utf-8",
            lineterminator="\n",
            float_format="%.10g",
        )
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()


def generate_synthetic_demo(
    matches: pd.DataFrame,
    assist_events: pd.DataFrame,
    assist_event_skills: pd.DataFrame,
    config: Mapping[str, Any],
    output_dir: Path,
    paths: ProjectPaths,
) -> dict[str, Any]:
    """Write the fixed Chinese undergraduate multi-course demo dataset."""

    demo_config = _section(config, "demo")
    seed = int(config.get("seed", 42))
    expected_students = int(demo_config.get("synthetic_students", 2000))
    minimum_answer_events = int(demo_config.get(
        "minimum_answer_events_per_active_enrollment",
        MIN_ANSWER_EVENTS_PER_ENROLLMENT,
    ))
    maximum_answer_events = int(demo_config.get(
        "maximum_answer_events_per_active_enrollment",
        MAX_ANSWER_EVENTS_PER_ENROLLMENT,
    ))
    minimum_activity_events = int(demo_config.get(
        "minimum_activity_events_per_active_enrollment",
        MIN_ACTIVITY_EVENTS_PER_ENROLLMENT,
    ))
    maximum_activity_events = int(demo_config.get(
        "maximum_activity_events_per_active_enrollment",
        MAX_ACTIVITY_EVENTS_PER_ENROLLMENT,
    ))
    skill_count = int(demo_config.get("skill_count", SKILL_COUNT))
    question_count = int(demo_config.get("question_count", QUESTION_COUNT))
    minimum_events = int(demo_config.get("minimum_unique_answer_events", 100_000))
    minimum_source_events = int(demo_config.get("minimum_source_events_per_student", 20))
    reference_time = pd.Timestamp(
        str(demo_config.get("reference_time", DEMO_REFERENCE_TIME.isoformat()))
    )
    if seed != 42:
        raise DataQualityError("synthetic demo seed must remain 42")
    if reference_time != DEMO_REFERENCE_TIME:
        raise DataQualityError(
            f"demo.reference_time must remain {DEMO_REFERENCE_TIME.isoformat()}"
        )
    if expected_students != 2000:
        raise DataQualityError("demo.synthetic_students must remain exactly 2000")
    if (
        minimum_answer_events,
        maximum_answer_events,
        minimum_activity_events,
        maximum_activity_events,
        skill_count,
        question_count,
    ) != (
        18,
        42,
        56,
        176,
        60,
        600,
    ):
        raise DataQualityError(
            "demo generation behavior ranges, skill count, or question count differ"
        )
    if minimum_events < 100_000 or minimum_events > 360_000:
        raise DataQualityError("demo minimum answer event gate must be between 100000 and 360000")
    if minimum_source_events != 20:
        raise DataQualityError("demo source event gate must remain 20")
    if demo_config.get("synthetic_flag", True) is not True:
        raise DataQualityError("demo.synthetic_flag must remain true")
    require_columns(matches, list(MATCH_COLUMNS), "student matches")
    expected_split_counts = {"train": 1400, "validation": 300, "test": 300}
    actual_split_counts = {
        split: int(matches["split"].eq(split).sum()) for split in expected_split_counts
    }
    if actual_split_counts != expected_split_counts:
        raise DataQualityError(
            f"demo split counts must remain {expected_split_counts}; got {actual_split_counts}"
        )
    namespace_value = str(demo_config.get("uuid_namespace", "a3e0f31f-54f6-4f99-82e8-bf5e6fdd55e4"))
    try:
        namespace = UUID(namespace_value)
    except ValueError as error:
        raise DataQualityError("demo.uuid_namespace is not a valid UUID") from error

    question_bank_path = QUESTION_BANK_DEFAULT
    frames = build_synthetic_frames(
        matches,
        assist_events,
        assist_event_skills,
        namespace=namespace,
        expected_students=expected_students,
        seed=seed,
        minimum_source_events_per_student=minimum_source_events,
        question_bank_path=question_bank_path,
    )
    active_enrollment_count = int(frames["enrollments"]["status"].eq("ACTIVE").sum())
    answer_counts = frames["answer_events"].groupby(["course_id", "student_id"]).size()
    activity_counts = frames["learning_activity_events"].groupby(
        ["course_id", "student_id"]
    ).size()
    source_lineage = _build_source_lineage(
        matches,
        namespace=namespace,
        seed=seed,
    )
    if len(source_lineage) != expected_students:
        raise DataQualityError("source lineage must contain exactly 2000 students")
    knowledge_lineage = _build_knowledge_lineage(
        assist_events,
        assist_event_skills,
        frames["knowledge_skills"],
        frames["questions"],
        seed=seed,
    )
    if len(knowledge_lineage) != QUESTION_COUNT:
        raise DataQualityError("knowledge lineage must contain exactly 600 questions")
    destination = Path(output_dir).resolve()
    destination.mkdir(parents=True, exist_ok=True)
    output_paths: dict[str, Path] = {}
    for name, frame in frames.items():
        path = destination / f"{name}.parquet"
        _write_parquet(frame, path)
        output_paths[name] = path

    database_directory = destination / "database"
    database_directory.mkdir(parents=True, exist_ok=True)
    database_frames = {name: frames[name] for name in DATABASE_FRAME_NAMES}
    database_frames["source_lineage"] = source_lineage
    database_frames["knowledge_lineage"] = knowledge_lineage
    database_paths: dict[str, Path] = {}
    for name, frame in database_frames.items():
        path = database_directory / f"{name}.csv"
        _write_csv(frame, path)
        database_paths[name] = path

    manifest_path = destination / "manifest.json"
    manifest: dict[str, Any] = {
        "schema_version": 7,
        "generation_algorithm": "behavior-session-learning-trajectories-v6",
        "data_version": DEMO_DATA_VERSION,
        "reference_time": DEMO_REFERENCE_TIME.isoformat(),
        "seed": seed,
        "synthetic": True,
        "source_rows_redistributed": False,
        "uuid_namespace": str(namespace),
        "student_count": len(frames["students"]),
        "course_count": len(frames["courses"]),
        "teacher_count": len(frames["teachers"]),
        "enrollment_count": len(frames["enrollments"]),
        "active_enrollment_count": active_enrollment_count,
        "answer_event_count_range": {
            "minimum": int(answer_counts.min()),
            "maximum": int(answer_counts.max()),
        },
        "activity_event_count_range": {
            "minimum": int(activity_counts.min()),
            "maximum": int(activity_counts.max()),
        },
        "behavior_profiles": list(BEHAVIOR_PROFILES),
        "minimum_source_events_per_student": minimum_source_events,
        "answer_event_count": len(frames["answer_events"]),
        "learning_activity_event_count": len(frames["learning_activity_events"]),
        "knowledge_skill_count": len(frames["knowledge_skills"]),
        "question_count": len(frames["questions"]),
        "organization_unit_count": len(frames["organization_units"]),
        "lesson_progress_count": len(frames["lms_lesson_progress"]),
        "submission_count": len(frames["lms_submissions"]),
        "submission_answer_count": len(frames["lms_submission_answers"]),
        "question_bank": {
            "path": "data/demo/question-bank.tsv",
            "sha256": sha256_file(question_bank_path),
            "content_origin": "TEACHER_AUTHORED",
        },
        "outputs": {
            name: {
                "path": path.relative_to(destination).as_posix(),
                "rows": len(frames[name]),
                "sha256": sha256_file(path),
            }
            for name, path in sorted(output_paths.items())
        },
        "database_import": {
            "schema_version": 3,
            "format": "rfc4180-csv-utf8-lf",
            "contains_source_behavior_rows": False,
            "source_lineage_fields": list(source_lineage.columns),
            "knowledge_lineage_fields": list(knowledge_lineage.columns),
            "outputs": {
                name: {
                    "path": path.relative_to(destination).as_posix(),
                    "rows": len(database_frames[name]),
                    "sha256": sha256_file(path),
                }
                for name, path in sorted(database_paths.items())
            },
        },
    }
    if not 150_000 <= manifest["answer_event_count"] <= 360_000:
        raise DataQualityError(
            "synthetic manifest answer_event_count must be between 150000 and 360000"
        )
    if not 450_000 <= manifest["learning_activity_event_count"] <= 1_500_000:
        raise DataQualityError(
            "synthetic manifest learning activity count must be between 450000 and 1500000"
        )
    manifest_path.write_bytes(canonical_json_bytes(manifest) + b"\n")
    manifest["manifest_path"] = repository_relative(manifest_path, paths)
    manifest["manifest_sha256"] = sha256_file(manifest_path)
    return manifest
