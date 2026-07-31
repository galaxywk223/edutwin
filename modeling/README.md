# EduTwin Modeling

## 范围

该工程负责数据处理、稳定切分、双源匹配、七个候选模型训练、统一评估、校准、SHAP、模型注册和 FastAPI CPU 推理。DKT/AKT 使用配置声明的 CUDA 设备训练，训练后迁回 CPU 完成统一评估和制品保存。

原始数据、处理中数据和训练集不进入 Git、Docker 构建上下文或服务器。部署镜像只包含冻结的推理制品、特征合同、校准器和模型清单。

## 固定入口

```powershell
edutwin-modeling data prepare --config configs/data/pipeline.yaml
edutwin-modeling data verify --config configs/data/pipeline.yaml
edutwin-modeling knowledge train --config configs/knowledge/training.yaml
edutwin-modeling knowledge finalize-test --config configs/knowledge/training.yaml
edutwin-modeling knowledge verify --config configs/knowledge/training.yaml
edutwin-modeling risk train --config configs/risk/training.yaml
edutwin-modeling risk finalize-test --config configs/risk/training.yaml
edutwin-modeling risk verify --config configs/risk/training.yaml
edutwin-modeling serve --config configs/serving/service.yaml
```

训练与测试评估的具体命令由根目录统一验收脚本调用。测试集只能在候选选择与配置冻结后执行一次。

## 知识模型生命周期

`knowledge train` 使用 ASSISTments 处理产物完成 IRT、BKT、DKT 和 AKT 的真实优化，并执行以下约束：

- `split` 按学生使用 `sha256-u64-mod-10000-v1`、命名空间 `edutwin-student-split-v1` 和种子 `42` 重新验证。
- 词表仅由训练分区构建；未知验证集和测试集题目、知识点统一映射到 `<UNKNOWN>`。
- 训练分区按学生稳定哈希拆分为拟合角色和校准角色，Platt 校准器不接触验证集或测试集。
- 四个候选模型在相同的全量后首事件集合上计算 AUC、Log Loss 和 15 箱 ECE。CPU P95 使用固定事件键哈希样本执行单请求计时，指标同时记录全量事件数与延迟样本数。
- BKT 固定作为掌握度模型；DKT 与 AKT 按验证 AUC 选择。AUC 差值不超过 `0.005` 时，依次比较 Log Loss、ECE 和 CPU P95。
- 冻结清单包含输入哈希、配置哈希、依赖版本、训练日志、词表、校准器、模型制品和验证指标。

`knowledge finalize-test` 只接受已冻结配置和未领取的测试状态。命令在推理前创建独占领取锁，将尝试次数设为 `1`，并在成功或失败后永久关闭再次评估入口。

`knowledge verify` 校验全部源文件和制品哈希、加载四个模型、检查固定服务角色，并要求测试状态为一次完成。该命令不重新训练或评估测试集。

风险模型采用同一冻结生命周期。测试证据同时绑定冻结清单、三个候选模型与校准器制品、测试学生集合和测试单元集合；测试锁、状态或指标任一存在已领取事实时，测试集评估不得重新执行。

## 推理服务

FastAPI 服务提供 `/v1/knowledge/predict`、`/v1/risk/predict`、`/v1/explain` 和 `/health`。启动门禁校验知识模型、风险模型、校准器、SHAP 实现、冻结清单及演示知识标识映射的哈希。任一必需制品缺失或变化时，健康状态返回 `DOWN`，推理接口返回结构化 `503` 问题响应。

推理请求使用 `TraceRef` 传递答题事件、分析任务、目标快照、数据版本、请求模型版本和实际模型版本。知识推理通过哈希校验后的 `knowledge_lineage.csv` 将演示 UUID 转换为训练侧 ASSISTments 题目与知识点键。服务镜像不包含学生来源映射、答题数据、原始数据、处理数据或训练数据。
