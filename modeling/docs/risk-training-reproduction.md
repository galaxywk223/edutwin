# 风险模型训练复现

## 环境

训练环境固定使用 Python `>=3.11,<3.12`。依赖版本由 `modeling/pyproject.toml` 固定，实际版本写入风险冻结清单。

## 前置产物

以下文件由数据准备阶段生成：

| 文件 | 作用 |
| --- | --- |
| `data/processed/risk/risk_units.parquet` | OULAD 第 `0-29` 日统一风险特征与标签 |
| `data/processed/risk/manifest.json` | 输入来源哈希、窗口、字段和切分统计 |
| `modeling/configs/risk/training.yaml` | 候选超参数、校准、选择和测试门禁 |

## 固定命令

```powershell
edutwin-modeling risk train --config configs/risk/training.yaml
edutwin-modeling risk finalize-test --config configs/risk/training.yaml
edutwin-modeling risk verify --config configs/risk/training.yaml
```

`risk train` 仅使用训练拟合、训练校准和验证分区，并生成 `risk-freeze.json` 及与冻结清单 SHA-256 绑定的 `SEALED` 测试状态。`risk finalize-test` 以独占锁领取唯一测试机会；锁、状态或指标任一表明已领取时，成功或失败均不得再次评估。`risk verify` 校验制品大小、SHA-256、候选可加载性、测试状态、配置哈希、测试学生集合和测试单元集合。

失败的测试生命周期整体保存在 `artifacts/history/risk-lifecycle-<n>-failed/`，其中包含候选制品、冻结清单、失败状态和独占锁。新生命周期必须递增 `lifecycle_version`，重新训练并产生新的配置哈希；旧生命周期不得删除、覆盖或再次评估测试集。

## 冻结产物

| 产物 | 内容 |
| --- | --- |
| `artifacts/risk/preprocessor.json` | 训练拟合侧均值、标准差、字段顺序和行数 |
| `artifacts/risk/<family>/model.joblib` | 实际训练候选模型 |
| `artifacts/risk/<family>/calibrator.json` | 训练校准侧 Platt 参数 |
| `artifacts/manifests/risk-freeze.json` | 输入、配置、依赖、指标、胜者和制品哈希 |
| `artifacts/manifests/risk-test-state.json` | 一次性测试领取与完成状态 |
| `artifacts/manifests/risk-test-metrics.json` | 三个冻结候选的测试指标 |

## 复核不变量

- 三个候选使用完全相同的字段顺序、训练角色、校准角色和验证行。
- 配置种子固定为 `42`。
- 验证选择不读取测试预测。
- 测试尝试次数固定为 `1`。
- 测试指标绑定冻结清单 SHA-256、三个候选模型版本、模型制品 SHA-256 和校准器 SHA-256。
- 测试学生集合与测试单元集合分别记录稳定排序后的 SHA-256，交付验证从冻结输入重新计算。
- 每个模型、校准器、处理器和清单均记录 SHA-256。
- 延迟指标通过单行服务等价调用测量，不使用批量吞吐除以样本数的估算值。
