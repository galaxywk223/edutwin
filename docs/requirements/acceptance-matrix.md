# EduTwin 验收矩阵

## 状态定义

| 状态 | 含义 |
| --- | --- |
| `required` | 最终验收必须执行且通过 |
| `passed` | 当前制品已执行并通过 |
| `failed` | 当前制品已执行但失败 |

最终清单不得使用 `skipped`、`not-run` 或同义状态满足门禁。

## 需求与证据映射

| ID | 需求 | 自动化证据 | 清单字段 | 门禁 |
| --- | --- | --- | --- | --- |
| `CONTRACT-001` | 公共与模型 OpenAPI 可解析且实现无漂移 | `tests/contract` | `tests.contract` | required |
| `CONTRACT-002` | AsyncAPI、事件包络与实现一致 | `tests/contract` | `tests.asyncapi` | required |
| `AUTH-001` | 学生只能访问本人数据 | 后端安全测试、E2E IDOR 测试 | `tests.security.studentScope` | required |
| `AUTH-002` | 教师只能访问本人授课课程 | 后端安全测试、E2E IDOR 测试 | `tests.security.teacherScope` | required |
| `AUTH-003` | 模型数据治理仅允许系统管理员访问，管理员不得访问学习业务内容 | 后端角色矩阵与 E2E 测试 | `tests.security.adminScope` | required |
| `AUTH-004` | 学生与教师响应不公开模型、数据、哈希、快照和追溯字段 | 公共响应递归审计测试 | `tests.security.publicRedaction` | required |
| `AUTH-005` | 辅导员仅查看分配范围内跨课程汇总，不能读取原始答案或调用修改接口 | 后端辅导员集成测试与 E2E 测试 | `tests.security.counselorScope` | required |
| `AUTH-006` | 多角色切换签发新活动角色令牌并使旧令牌失效 | 身份集成测试与多角色 E2E | `tests.security.multiRole` | required |
| `PROFILE-001` | 官方身份只读，联系方式、预设头像和主题色可由本人修改 | 个人资料集成测试 | `tests.profile.selfService` | required |
| `NOTIFY-001` | 站内通知按业务去重键生成并支持未读数、单条与全部已读 | 通知集成测试与前端测试 | `tests.notifications.lifecycle` | required |
| `LMS-001` | 课程、内容和考核状态转换符合前向生命周期及影响确认规则 | LMS 集成测试与教师 E2E | `tests.lms.lifecycle` | required |
| `LMS-002` | 补交、重做和申诉批准增加一次尝试，全部尝试保留且最高分生效 | LMS 集成测试与学生 E2E | `tests.lms.attempts` | required |
| `RISK-001` | 高风险工单幂等创建，内部研判对学生隐藏，行动项支持反馈 | 风险集成测试与三角色 E2E | `tests.risk.intervention` | required |
| `PLAN-001` | 计划与任务支持到期、跳过、深链练习和自动完成 | 计划集成测试与学生 E2E | `tests.plans.lifecycle` | required |
| `COUNSELOR-001` | 授权范围内最多四班聚合对比不包含原始答案 | 辅导员集成测试与 E2E | `tests.counselor.classComparison` | required |
| `ASSISTANT-001` | 四角色工具严格按活动角色隔离且全部只读 | 工具单元测试、越权测试、真实大模型门禁 | `tests.assistant.roleTools` | required |
| `ASSISTANT-002` | 会话持久化、SSE 断线恢复、失败重试和正文删除符合合同 | 助手集成测试与 E2E | `tests.assistant.recovery` | required |
| `GOV-001` | 数据影响预览、弃用和无引用退役符合生命周期约束 | 管理治理集成测试 | `tests.governance.datasets` | required |
| `GOV-002` | 模型仅按单任务、期望活动版本和确认目标回滚 | 管理治理集成测试与本地回滚测试 | `tests.governance.modelRollback` | required |
| `AUDIT-001` | 成功与失败审计独立持久化并支持分页、筛选、详情和 CSV | 审计集成测试 | `tests.governance.audit` | required |
| `DEMO-001` | synthetic-demo-v6 固定参考时间可复现，导入时整体平移业务时间 | Python 数据测试与导入测试 | `tests.demo.timeShift` | required |
| `IDEMP-001` | 相同答题重放不新增事件、任务或快照 | MySQL/Redis 集成测试 | `tests.idempotency.replay` | required |
| `IDEMP-002` | 相同键不同请求返回 409 | 后端集成测试 | `tests.idempotency.conflict` | required |
| `DATA-001` | 两套来源下载、许可和 SHA-256 可复核 | 数据复现脚本 | `data.sources` | required |
| `DATA-002` | ASSISTments 核心字段冲突导致失败 | 数据质量测试 | `tests.data.assistmentsConflicts` | required |
| `DATA-003` | OULAD 特征不包含结果、退课日和第 30 日后行为 | 泄漏审计测试 | `tests.data.ouladLeakage` | required |
| `DATA-004` | 稳定切分、训练侧拟合和无放回匹配可复现 | 双次生成哈希比较 | `data.reproducibility` | required |
| `DATA-005` | 演示库恰好 2,000 人且至少 100,000 个唯一答题事件 | 数据库断言 | `demo.cardinality` | required |
| `MODEL-001` | IRT、BKT、DKT、AKT 均实际训练并统一评估 | 训练日志、指标与制品清单 | `models.knowledgeCandidates` | required |
| `MODEL-002` | Logistic、LightGBM、CatBoost 使用同一合同并实际训练 | 训练日志、指标与制品清单 | `models.riskCandidates` | required |
| `MODEL-003` | 选择规则、校准器、一次测试评估和制品哈希冻结 | 清单审计测试 | `models.selection` | required |
| `MODEL-004` | SHAP 只输出模型前五因素 | FastAPI 集成与 Schema 测试 | `tests.models.shap` | required |
| `LOOP-001` | 答题接口返回 202 且不等待分析 | API 集成测试 | `tests.loop.acceptedAsync` | required |
| `LOOP-002` | 掌握度、下一题概率、风险概率发生数值变化 | 公共接口 E2E | `tests.loop.numericChanges` | required |
| `LOOP-003` | 快照数、计划版本和教师统计发生变化 | 公共接口 E2E | `tests.loop.derivedChanges` | required |
| `LOOP-004` | 内部事件与数据库记录可追溯至模型和数据，普通响应保持脱敏 | 内部追溯与公共响应审计测试 | `tests.loop.traceability` | required |
| `IMMUT-001` | 旧快照 UPDATE/DELETE 被数据库拒绝 | MySQL 集成测试 | `tests.persistence.snapshotImmutable` | required |
| `CACHE-001` | 三类缓存删除后可从 MySQL 重建 | 故障测试 | `tests.resilience.cacheRebuild` | required |
| `REDIS-001` | Redis 发布重试和消费者重领不产生重复快照 | 故障测试 | `tests.resilience.redisRetry` | required |
| `FALLBACK-001` | FastAPI 停止后规则结果完成全链路 | 故障测试 | `tests.resilience.modelFallback` | required |
| `FALLBACK-002` | 活动大模型强制失败后返回同结构模板诊断 | 故障测试 | `tests.resilience.aiFallback` | required |
| `AI-001` | 管理员激活的 OpenAI 兼容模型完成一次真实工具调用 | 真实调用测试与调用记录 | `tests.ai.realToolCall` | required |
| `AI-002` | 管理员配置仅在工具调用验证成功后生效，Key 加密且不回传 | 管理配置集成测试与浏览器测试 | `tests.ai.runtimeConfiguration` | required |
| `SSE-001` | SSE 支持断线恢复并收到任务终态 | E2E 测试 | `tests.sse.recovery` | required |
| `PERF-001` | 缓存读取 P95 小于 1 秒 | k6 报告 | `performance.cacheP95Ms` | required |
| `PERF-002` | 答题事务 P95 小于 500 毫秒 | k6 报告 | `performance.answerP95Ms` | required |
| `PERF-003` | 事件至 SSE 完成 P95 小于 5 秒 | k6 与 E2E 时间线 | `performance.eventToSseP95Ms` | required |
| `PERF-004` | 50 浏览并发、5 答题并发下无 OOM、重启或持续换页 | 容器与系统指标 | `performance.stability` | required |
| `OPS-001` | 空数据库 Compose 可启动并通过健康检查 | 本地部署测试 | `local.compose` | required |
| `OPS-002` | 重启后任务、快照与缓存状态恢复 | 重启恢复测试 | `local.restartRecovery` | required |
| `OPS-003` | 模型回滚激活 runner-up 冻结制品、完成公共闭环并恢复胜者 | 本地与服务器回滚测试 | `local.modelRollback`、`server.rollback` | required |
| `SERVER-001` | 同摘要 linux/amd64 镜像通过离线包部署 | 摘要比较 | `server.imageDigests` | required |
| `SERVER-002` | 服务器仅保留推理制品且只公开应用 80 端口 | 远端审计 | `server.boundary` | required |
| `SERVER-003` | 服务器公共答题闭环和性能门禁通过 | 远端 E2E 与 k6 | `server.acceptance` | required |

## 最终清单不变量

- `openItems` 必须为空数组。
- 所有 `required` 测试必须包含实际开始时间、结束时间、退出码和证据路径。
- 代码树、数据来源、数据处理、模型清单、模型制品和镜像必须记录 SHA-256。
- 数据库迁移必须记录 Flyway 当前版本和校验结果。
- 本地与服务器结果必须分别记录，服务器结果不得复用本地证据。
