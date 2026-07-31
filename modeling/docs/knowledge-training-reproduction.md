# 知识模型训练复现

## 数据合同

知识模型输入固定为以下数据工程产物：

| 产物 | 约束 |
| --- | --- |
| `data/processed/knowledge/answer_events.parquet` | 每个 `order_id` 一条答题事件；包含稳定学生切分和学生内事件序号 |
| `data/processed/knowledge/answer_event_skills.parquet` | 每个事件一至多个知识点；事件内序号从 `1` 连续递增 |
| `data/processed/knowledge/quality_report.json` | 记录 corrected 非折叠来源、折叠事件数、文件哈希和切分合同 |

训练入口在读取 Parquet 前验证质量报告。文件哈希、行数、学生数和稳定切分任一不一致均终止训练。

## 环境

Python 版本固定为 `3.11`。依赖及精确版本位于 `modeling/pyproject.toml`。训练和最终测试仅使用 CPU；随机种子固定为 `42`。

神经模型制品固定记录训练侧题目词表大小、知识点词表大小和 `max_sequence_length`。离线评估与在线服务必须使用相同的最大序列合同；合同不一致时加载或评估终止。每个离线目标保留最近 `max_sequence_length - 1` 条已观测交互，并以真实目标题目和知识点执行单目标推理。

## 训练与冻结

```powershell
Set-Location modeling
edutwin-modeling knowledge train --config configs/knowledge/training.yaml
```

训练命令生成以下制品：

| 产物 | 内容 |
| --- | --- |
| `artifacts/knowledge/run-<runId>/` | 四个候选模型、校准器、训练日志、词表和候选清单 |
| `artifacts/manifests/knowledge-freeze.json` | 数据、配置、依赖、验证指标、胜者和全部制品哈希 |
| `artifacts/manifests/knowledge-test-state.json` | 初始状态为 `SEALED`、尝试次数为 `0` 的测试领取状态 |

已有冻结、测试或同一运行目录时，训练命令拒绝覆盖。

## 最终测试

```powershell
edutwin-modeling knowledge finalize-test --config configs/knowledge/training.yaml
```

最终测试在模型和配置冻结后执行。命令首先以独占方式创建 `knowledge-test-evaluation.lock`，再将状态切换为 `RUNNING` 和尝试次数 `1`。测试成功后写入四个候选的统一事件级指标；失败后状态写为 `FAILED`。两种结果均禁止第二次执行。

## 完整性验证

```powershell
edutwin-modeling knowledge verify --config configs/knowledge/training.yaml
```

完整性验证要求：

- 冻结配置哈希与当前配置一致。
- 三个输入文件与冻结源哈希一致。
- 四个候选清单和所有模型、校准器、训练日志、词表制品哈希一致。
- BKT 可作为掌握度制品加载。
- 验证选出的 DKT 或 AKT 可作为下一题概率制品加载。
- 测试领取锁、状态和测试指标均证明测试只执行一次。

验证命令不访问测试标签进行重新推理。
