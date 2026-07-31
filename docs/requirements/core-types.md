# EduTwin 核心类型契约

## 统一追溯引用

`TraceRef` 是异步任务及其派生结果的内部统一追溯对象。该类型保存在数据库、事件和模型服务合同中，不属于学生或教师公共响应。

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `answerEventId` | UUID | 非空，原始答题事件标识 |
| `analysisJobId` | UUID | 非空，分析任务标识 |
| `snapshotId` | UUID | 非空，答题事务内预分配的目标快照标识 |
| `dataVersions` | `DataVersionRef[]` | 非空，至少包含 ASSISTments 与 OULAD 处理版本 |
| `requestedModelVersions` | `ModelVersionRef[]` | 非空，任务创建时的激活版本 |
| `effectiveModelVersions` | `ModelVersionRef[]` | 非空，实际推理或降级版本 |
| `correlationId` | UUID | 非空，端到端关联标识 |

## 固定核心类型

| 类型 | 必需业务字段 | 必需追溯字段 |
| --- | --- | --- |
| `TwinState` | 学生、课程、快照版本、知识掌握向量、下一题概率、风险概率、参与度、稳定性、计划完成率、生成时间 | `trace` |
| `RiskPrediction` | 校准概率、风险层级、模型基值、预测时间、降级标识；前五因素由解释与诊断证据类型承载 | `trace` |
| `DataProvenance` | 来源、许可、来源 URL、来源哈希、处理版本、处理配置哈希、行数 | `trace` 或透明度查询上下文 |
| `DiagnosisEvidence` | 排名、特征名、原始值、方向、贡献、基值、输出单位、风险模型版本 | `trace` |
| `LearningPlan` | 学生、课程、计划版本、来源快照、规则版本、状态、任务数组、创建时间 | `trace` |
| `AnalysisJob` | 状态、持久事件序号、降级状态、失败信息、创建/开始/完成时间 | `trace` |

## 状态枚举

| 类型 | 值 |
| --- | --- |
| `AnalysisJobStatus` | `QUEUED`、`PROCESSING`、`COMPLETED`、`FAILED` |
| `RiskBand` | `LOW`、`MEDIUM`、`HIGH` |
| `EvidenceDirection` | `INCREASES_RISK`、`DECREASES_RISK` |
| `PlanStatus` | `ACTIVE`、`SUPERSEDED`、`COMPLETED`、`EXPIRED` |
| `PlanItemStatus` | `PENDING`、`IN_PROGRESS`、`COMPLETED`、`SKIPPED`、`EXPIRED` |
| `AssessmentStatus` | `DRAFT`、`PUBLISHED`、`CLOSED`、`CANCELLED`、`ARCHIVED` |
| `LearnerAssessmentState` | `AVAILABLE`、`SUBMITTED`、`CLOSED_UNSUBMITTED`、`CANCELLED` |
| `RiskCaseStatus` | `OPEN`、`IN_PROGRESS`、`RESOLVED`、`CLOSED` |
| `DatasetLifecycleStatus` | `ENABLED`、`DEPRECATED`、`RETIRED` |

## 身份与角色会话

`AuthenticatedUser` 同时包含兼容字段 `role`、活动角色 `activeRole` 和全部授权角色 `availableRoles`。JWT 仅携带一个活动角色权限。角色、密码或账号状态变化递增 `tokenVersion`，此前签发的令牌立即失效。

多角色账号首次登录按 `STUDENT`、`COUNSELOR`、`TEACHER`、`ADMIN` 的顺序选择最低权限角色，后续登录恢复最近一次活动角色。

## 助手事件

助手事件类型固定为 `message.started`、`tool.started`、`tool.completed`、`message.delta`、`message.completed` 和 `message.failed`。事件按消息维护单调递增序号并持久化，`Last-Event-ID` 用于断线恢复。

助手会话按用户与活动角色隔离。助手消息仅保存只读工具来源、查询时间和业务深链；删除会话后保留不含聊天正文、原始答案和工具结果的审计元数据。

## 数值约束

- 概率与掌握度范围为 `[0, 1]`。
- SHAP `direction` 仅由 `contribution` 的符号产生；零贡献不进入前五因素。
- 风险层级阈值由激活风险模型清单提供，业务接口不得自行改写。
- 快照版本和计划版本在学生课程范围内从 `1` 开始严格递增。
- 事件、任务、快照、数据版本和有效模型版本不得以时间戳替代。

## 版本标识

| 版本类型 | 格式示例 |
| --- | --- |
| 数据处理版本 | `assistments-2009-2010-sb-corrected-v1` |
| 知识模型版本 | `bkt-assist09-seed42-<manifest12>` |
| 下一题模型版本 | `akt-assist09-seed42-<manifest12>` |
| 风险模型版本 | `lightgbm-oulad-d0-29-seed42-<manifest12>` |
| 规则版本 | `rule-plan-v1` |
| 降级版本 | `rule-mastery-v1`、`rule-next-v1`、`rule-risk-v1`、`template-diagnosis-v1` |

`<manifest12>` 是模型清单 SHA-256 的前 12 个十六进制字符。完整清单哈希与制品哈希保存在模型来源表和管理员透明度接口中。
