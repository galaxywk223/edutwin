# EduTwin 模型卡

## 模型系统

EduTwin 模型系统由知识追踪、课程风险与风险解释三个推理角色组成。模型服务只加载冻结制品并执行 CPU 推理，业务后端负责权限、事实写入、规则计划、快照与降级。

| 角色 | 候选或固定模型 | 输出 | 选择规则 |
| --- | --- | --- | --- |
| 掌握度 | BKT | 知识点掌握概率 | 合同固定 |
| 下一题正确率 | DKT、AKT | 下一题正确概率 | 验证 AUC；差值不超过 `0.005` 时依次比较 Log Loss、15 箱 ECE、CPU P95 |
| 知识基线 | IRT | 事件级正确概率 | 统一评估，不承担在线掌握度角色 |
| 课程风险 | Logistic Regression、LightGBM、CatBoost | 校准风险概率与风险层级 | 验证 PR-AUC；差值不超过 `0.005` 时依次比较 Brier Score、CPU P95 |
| 风险解释 | 胜者模型原生 SHAP | 前五项特征贡献 | 与风险模型版本绑定 |

## 训练数据

| 任务 | 数据 | 观测边界 | 标签或目标 |
| --- | --- | --- | --- |
| 知识追踪 | ASSISTments 2009-2010 Skill Builder corrected 非折叠数据 | 完整答题序列；`order_id` 折叠为一个事件与多知识点关联 | 后续答题正确性 |
| 课程风险 | OULAD | 开课后第 `0-29` 日 | 最终 `Fail` 或窗口后 `Withdrawn` 为正类 |

OULAD 特征禁止包含最终结果、退课日期和第 30 日后的行为。双源学生切分使用种子 `42` 的稳定学生哈希 `70/15/15`。词表、归一化、预处理和校准器只使用训练侧数据拟合。

## 评估合同

知识模型在统一的后首事件集合上计算 AUC、Log Loss、15 箱 ECE 与 CPU P95。风险模型在统一学生特征合同上计算 PR-AUC、Brier Score 与 CPU P95。测试集只在验证选择、配置和制品冻结后评估一次。

最终数值与哈希由以下机器文件记录：

| 证据 | 路径 |
| --- | --- |
| 知识冻结清单 | `modeling/artifacts/manifests/knowledge-freeze.json` |
| 知识测试指标 | `modeling/artifacts/manifests/knowledge-test-metrics.json` |
| 风险冻结清单 | `modeling/artifacts/manifests/risk-freeze.json` |
| 风险测试指标 | `modeling/artifacts/manifests/risk-test-metrics.json` |
| 演示模型注册表 | `data/demo/generated/database/registry.json` |
| 最终验收清单 | `reports/dist/acceptance-manifest.json` |

冻结清单记录数据哈希、训练配置哈希、种子、依赖版本、验证指标、校准器、制品路径和制品 SHA-256。测试指标清单绑定冻结清单哈希与测试事件或学生集合哈希。

## 在线推理

FastAPI 固定提供以下接口：

| 接口 | 结果 |
| --- | --- |
| `/v1/knowledge/predict` | BKT 掌握度与 DKT/AKT 激活版本下一题概率 |
| `/v1/risk/predict` | 风险胜者校准概率与层级 |
| `/v1/explain` | 模型计算的前五项 SHAP 因素 |
| `/health` | 冻结制品、清单与合同加载状态 |

每个结果携带答题事件、分析任务、目标快照、数据版本、请求模型版本和有效模型版本。SHAP 因素仅包含特征名、原始值、方向、贡献、基值、输出单位和风险模型版本。DeepSeek 只能基于工具返回的孪生状态与五项证据生成文本，不参与风险原因计算。

## 降级与回滚

FastAPI 不可用、超时或合同错误时，业务后端使用版本化规则掌握度、下一题概率、风险与证据，并在任务和快照中记录降级原因。DeepSeek 不可用或超时时，诊断切换为 `template-diagnosis-v1`，规则计划保持不变。

模型镜像同时包含激活胜者与 runner-up 冻结制品。回滚事务同步交换 `NEXT_CORRECT`、`RISK` 和 `EXPLANATION` 部署指针，模型服务以 `rollback` 模式加载对应候选；恢复事务重新激活验证集胜者。

## 适用范围与限制

- 模型仅用于合成学生课程演示，不用于真实学生的高风险决策。
- ASSISTments 与 OULAD 的人群、课程、时间与行为分布不代表目标学校当前学生。
- 双源最近邻匹配只对齐表现、活跃和持续性三个标准化维度，不建立个体身份或因果对应。
- 风险概率表示固定 OULAD 标签合同下的模型输出，不表示临床、心理或纪律结论。
- 公网 HTTP 部署不具备生产数据安全属性，不得接收真实学生数据。

## 许可与责任

数据许可、来源 URL、固定哈希和再分发边界记录于 `docs/data/sources-and-licenses.md`。服务器和推理镜像不得包含原始数据、处理中数据或训练集。
