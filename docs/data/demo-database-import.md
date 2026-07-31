# 演示数据库导入

## 目标

演示数据库导入流程将 `data/demo/generated` 中的确定性模拟数据写入完成 Flyway 迁移的 MySQL。导入事务包含组织与学期、身份教学、学籍与教职工档案、多课程关系、课程内容、课时进度、考核尝试、原始学习活动、历史答题事件、跨来源匹配、数据来源和模型注册信息。

数据版本固定为 `synthetic-demo-v6`。主清单使用 `schema_version: 7`，数据库接口使用 `database_import.schema_version: 3`。生成参考时刻固定为 `2026-06-15T00:00:00Z`，保证相同输入、配置和种子生成相同文件。

固定基数如下：

| 实体 | 基数 |
| --- | ---: |
| 学生 | 2,000 |
| 教师 | 12 |
| 组织单位 / 学期 | 199 / 1 |
| 课程 | 20 |
| 选课记录 | 8,500（8,400 在读，100 退课） |
| 知识点 | 60 |
| 题目 | 600 |
| 答题事件 | 201,600 |
| 原始学习活动 | 806,400 |
| 课程章节 / 课时 | 120 / 360 |
| 课程考核 / 考核题 | 100 / 500 |
| 课时进度 | 由行为活跃度、坚持度和表现共同确定 |
| 考核尝试 / 提交答案 | 每个在读选课至少 1 次 / 每次 5 项 |
| 学生跨来源映射 | 2,000 |
| 题目来源映射 | 600 |

## 生成接口

数据处理流程同时生成 Parquet 研究制品和 `database` 目录下的 UTF-8 RFC 4180 CSV 接口。`manifest.json` 的 `database_import.outputs` 记录每个 CSV 的相对路径、行数和 SHA-256。

题库源文件为 `data/demo/question-bank.tsv`。文件按 60 个课程知识点记录 4 个真实课程概念及其正式定义，生成器据此构造每个知识点 10 道单选题。题干和选项只使用同一知识点中的实际术语与定义，不使用通用学习策略、空泛处理方式或跨课程干扰项。生成题目的 `content_origin` 固定为 `TEACHER_AUTHORED`，演示数据整体仍保持 `synthetic=true`。

数据库接口包含以下文件：

| 文件 | 数据库用途 |
| --- | --- |
| `organization_units.csv`、`academic_terms.csv` | `organization_unit`、`academic_term` |
| `courses.csv` | `course` |
| `teachers.csv`、`teaching_assignments.csv` | 教师账号、档案和授课关系 |
| `knowledge_skills.csv` | `knowledge_skill` 基础属性 |
| `questions.csv`、`course_questions.csv` | 题库、知识点和课程题目关系 |
| `students.csv`、`enrollments.csv` | 学生账号、学籍档案和多课程选课 |
| `lms_*.csv` | 章节、课时、考核和考核题 |
| `lms_lesson_progress.csv` | 学生课时完成事实 |
| `lms_submissions.csv`、`lms_submission_answers.csv` | 考核尝试、得分和逐题答案 |
| `learning_activity_events.csv` | 原始课程访问、课时、资源、练习和考核活动 |
| `answer_events.csv` | 去重历史 `answer_event` |
| `answer_event_skills.csv` | `answer_event_skill` |
| `source_lineage.csv` | `synthetic_match` |
| `knowledge_lineage.csv` | 题目和知识点 UUID 到训练侧 ASSISTments 词表键的映射 |

`source_lineage.csv` 仅包含合成学生 UUID、`assistments_user_sha256`、`oulad_student_sha256`、切分、融合 z-score、匹配距离和种子。两个来源摘要分别按以下域分离输入计算 lowercase SHA-256：

```text
edutwin-source-lineage-v1\0assistments-user\0<ASSISTments source key>
edutwin-source-lineage-v1\0oulad-student\0<OULAD source key>
```

摘要不可逆且不能跨来源关联。CSV、后端镜像和 MySQL 均不保存 ASSISTments 或 OULAD 原始学生标识。该文件不包含来源行为、答题、题目文本或标签。

`knowledge_lineage.csv` 从 ASSISTments 训练侧词表中按种子 `42` 选择 60 个知识点和 600 个全局唯一题目键。该映射仅服务于冻结模型词表兼容和历史行为代理，不表示人工题库与 ASSISTments 原题文本相同。在线课程题使用 `ONLINE_BKT` 按实际作答更新掌握度。模型服务镜像只包含 `manifest.json` 和 `knowledge_lineage.csv`，不包含 `source_lineage.csv` 或训练数据。

课时完成数、提交答案、考核得分、历史作答正确率、掌握度输入和风险输入均由同一组学生表现、活跃度和坚持度生成。每门课程固定包含开放考核、已有提交的关闭考核和截止未提交的关闭考核。生成器和导入校验器拒绝跨课程提交、非选课学生进度、逐题得分与总分不一致以及缺失场景的数据。

## 模型与数据注册表

注册表生成命令如下：

```powershell
pwsh -File .\scripts\seed-demo\build-demo-registry.ps1
```

命令要求以下制品全部存在并通过冻结状态检查：

- 数据处理总清单、ASSISTments 质量报告和 OULAD 处理清单。
- IRT、BKT、DKT、AKT 冻结候选与一次性测试指标。
- Logistic Regression、LightGBM、CatBoost 冻结候选与一次性测试指标。
- BKT 掌握度部署、DKT/AKT 下一题胜者、风险胜者和 SHAP 解释器。
- `planner-rules-v1` 规则制品和 `deepseek-v4-flash-tool-schema-v1` 工具 Schema 制品。

命令生成 `data/demo/generated/database/registry.json`。注册表记录三个数据版本、十个模型或规则制品、六个活动部署、回滚版本、指标、依赖、配置、制品大小和 SHA-256。

## 启动导入

Spring Boot 导入器由 `EDUTWIN_DEMO_IMPORT_ENABLED` 控制。Compose 默认启用导入，并要求以下环境变量：

```text
EDUTWIN_DEMO_TEACHER_PASSWORD
EDUTWIN_DEMO_STUDENT_PASSWORD
EDUTWIN_DEMO_AS_OF
EDUTWIN_ADMIN_USERNAME
EDUTWIN_ADMIN_PASSWORD
EDUTWIN_ADMIN_DISPLAY_NAME
```

固定登录名如下：

| 角色 | 登录名 |
| --- | --- |
| 教师 | `teacher-01` 至 `teacher-12` |
| 学生 | 学号，例如 `2022010001` |
| 系统管理员 | `EDUTWIN_ADMIN_USERNAME` 配置值 |

密码在首次导入时使用 BCrypt cost `12` 计算。密码明文不进入注册表、镜像或数据库。

`EDUTWIN_DEMO_AS_OF` 接受 ISO 日期，例如 `2026-07-15`。空值使用 UTC 导入日。导入器以固定参考日为原点，将课程和学期日期、考核发布时间与截止时间、课时完成时间、考核提交时间、学习活动时间和答题时间整体平移相同天数。数据与文件哈希保持固定，运行时开放、已提交和截止未提交场景相对导入日保持有效。

## 事务与重放

导入器在任何写入前执行以下门禁：

1. 校验主清单 Schema、数据库接口 Schema、数据版本、固定参考时刻、种子、固定基数和合成标志。
2. 校验全部 CSV 的 SHA-256、表头和行数。
3. 校验 UUID、lowercase SHA-256、唯一键、组织层级、切分基数、事件序号、课程场景和全部跨文件引用。
4. 校验三个数据来源、七个候选模型、六个活动部署和模型制品完整性。
5. 校验 BKT、DKT/AKT、风险胜者、SHAP、规则计划和 DeepSeek 诊断的活动用途。

全部写入位于一个 MySQL 事务。任一写入或最终数据库断言失败时，事务整体回滚。

历史种子事件使用从 `9,000,000,000,000` 开始的确定性 `source_order_id` 保留区间。重启校验只统计该保留区间，公共答题接口后续写入的实时事件可以继续增加，不会破坏种子重放。

相同 `processing_run.id` 的重放仅在处理清单 SHA-256、历史种子基数、来源映射、数据版本、模型版本、指标和制品完全一致时返回成功。活动部署允许在已登记模型集合内切换，重启不会覆盖回滚后的活动版本。部分导入、不同清单或未登记的已有合成数据会使启动失败，导入器不会覆盖已有事实。

## 部署边界

后端镜像包含合成数据库包、哈希化学生谱系和注册表。模型服务镜像包含模型制品、演示主清单和知识词表映射。服务器镜像不包含原始学生标识、`data/raw`、`data/interim`、`data/processed`、来源行为表或训练数据。
| `organization_units.csv`、`academic_terms.csv` | `organization_unit`、`academic_term` |
