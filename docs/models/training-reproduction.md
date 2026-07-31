# 训练复现

## 环境合同

训练环境固定使用 Python `3.11` 和 `modeling/pyproject.toml` 中的精确版本。知识模型训练配置固定使用 PyTorch `2.7.1+cu126`、CUDA 12.6 与计算能力 8.9 的 NVIDIA GPU；训练设备写入冻结模型配置。训练完成后 DKT/AKT 参数迁回 CPU，统一验证延迟和部署推理均在 CPU 执行。项目专用环境位于 `modeling/.venv`，解释器选择记录于未跟踪的 `.codex-python-env.local.json`。

CUDA 训练要求驱动支持 CUDA 12.6。配置声明 `training.device: cuda` 时，CUDA 不可用会使训练失败，不会静默切换 CPU。CPU 复现实验必须显式将该字段改为 `cpu`，由此产生的新配置哈希属于独立训练生命周期。

```powershell
& .\modeling\.venv\Scripts\python.exe -m pip install --upgrade pip
& .\modeling\.venv\Scripts\python.exe -m pip install -e ".\modeling[dev]"
```

原始数据、处理中数据、训练制品和项目环境均不进入 Git。训练命令从仓库根目录执行模型模块时，工作目录固定为 `modeling`。

ASSISTments 原始技能标识为空的事件使用 `__MISSING_SKILL__` 显式来源标记。该规则保留真实 `order_id` 事件，不推断不存在的知识点；来源中的 `opportunity` 与 `opportunity_original` 保持不变并参与持续性维度计算。空技能行数量、事件数量和标记值写入质量报告。空技能与已识别技能混合的同一事件或空技能行携带非空 `skill_name` 会使质量检查失败。

OULAD 的 `Withdrawn` 标签仅在 `date_unregistration > 29` 时进入正类。退课日期位于第 0 至 29 日或日期为空的退课单元均从监督样本排除，非数字日期仍使质量检查失败。各排除类别与最终样本数写入 OULAD 清单。

OULAD 测评行为窗口使用 `studentAssessment.date_submitted`，`assessments.date` 仅属于作业计划信息且不作为提交行为时间。VLE 日期与提交日期均仅保留第 0 至 29 日。

跨源匹配要求 ASSISTments 学生至少具有 20 个真实答题事件。该阈值是同时满足 train 1,400、validation 300、test 300 无放回目标的最高整数阈值；处理清单记录阈值、候选人数与最终匹配计数。

## 来源锁定

```powershell
pwsh -File .\scripts\data\download-datasets.ps1
```

该命令验证下载状态、文件格式、大小、固定哈希、OULAD MD5、ZIP 条目和 ASSISTments canonical 语义，并生成 `data/manifests/source-lock.json`。任一来源门禁失败时，来源锁不得更新。

## 代码质量

```powershell
Push-Location .\modeling
& .\.venv\Scripts\python.exe -m ruff check src tests
& .\.venv\Scripts\python.exe -m mypy src
& .\.venv\Scripts\python.exe -m pytest -q
Pop-Location
```

Ruff、mypy 或 pytest 任一非零退出码都会阻止数据处理与训练进入验收通过状态。

## 数据处理

```powershell
Push-Location .\modeling
& .\.venv\Scripts\python.exe -m edutwin_modeling.cli data prepare `
  --config configs/data/pipeline.yaml
& .\.venv\Scripts\python.exe -m edutwin_modeling.cli data verify `
  --config configs/data/pipeline.yaml
Pop-Location
```

处理流程执行以下固定步骤：

1. ASSISTments canonical 输入质量检查与 `order_id` 多知识点折叠。
2. OULAD 第 `0-29` 日窗口构建与泄漏字段拒绝。
3. 学生稳定哈希 `70/15/15` 切分，种子为 `42`。
4. 双源表现、活跃、持续性维度计算。
5. 来源内 1%/99% 缩尾与 z-score。
6. 固定种子、无放回最近邻匹配。
7. 恰好 `2,000` 名学生、`8,400` 条在读选课、`201,600` 条去重答题事件与 `806,400` 条原始学习活动生成。
8. `data/manifests/processing-manifest.json` 写入。

双次处理复现由以下门禁执行：

```powershell
pwsh -File .\scripts\verify\verify-data-reproducibility.ps1
```

门禁在隔离输出目录重复处理并比较清单和输出哈希，不以时间戳相等替代内容相等。

## 知识模型训练

```powershell
Push-Location .\modeling
& .\.venv\Scripts\python.exe -m edutwin_modeling.cli knowledge train `
  --config configs/knowledge/training.yaml
Pop-Location
```

训练命令真实优化 IRT、BKT、DKT 和 AKT，使用训练侧稳定校准分区拟合 Platt 校准器，在验证集统一事件集合上选择 DKT/AKT 胜者，并冻结 BKT 掌握度角色与下一题角色。冻结完成后生成 `knowledge-freeze.json` 和测试状态封印。

知识模型的 AUC、Log Loss 与 ECE 使用分区内全部后首事件。神经模型的全量概率按 64 个在线等价 rolling-window 在 CPU 批量前向；CPU P95 延迟按事件键 SHA-256 最小值固定抽取最多 2,048 个窗口，以单请求方式执行 1 次预热和 3 次计时。指标清单同时记录全量事件数和延迟样本数。

## 知识测试集一次性评估

```powershell
Push-Location .\modeling
& .\.venv\Scripts\python.exe -m edutwin_modeling.cli knowledge finalize-test `
  --config configs/knowledge/training.yaml
Pop-Location
```

`finalize-test` 在读取测试集前创建独占领取锁，将尝试次数设为 `1`。成功、失败或手工删除锁文件均不得重新执行该测试评估。配置、清单或测试集合发生变化时必须开始新的版本化训练生命周期，旧封印不得覆盖。

## 风险模型训练

```powershell
Push-Location .\modeling
& .\.venv\Scripts\python.exe -m edutwin_modeling.cli risk train `
  --config configs/risk/training.yaml
Pop-Location
```

风险训练使用同一特征合同优化 Logistic Regression、LightGBM 和 CatBoost，在训练侧稳定校准分区拟合校准器，并按验证 PR-AUC、Brier Score 和 CPU P95 固定规则选择胜者。冻结清单同时记录风险胜者、原生 SHAP 解释器、阈值、预处理器与 runner-up 回滚候选。

## 风险测试集一次性评估

```powershell
Push-Location .\modeling
& .\.venv\Scripts\python.exe -m edutwin_modeling.cli risk finalize-test `
  --config configs/risk/training.yaml
Pop-Location
```

风险测试状态、领取锁、冻结清单哈希、测试学生集合哈希和测试单元集合哈希共同构成一次性证据。任何已领取事实都会拒绝第二次评估。

## 冻结制品验证

```powershell
Push-Location .\modeling
& .\.venv\Scripts\python.exe -m edutwin_modeling.cli knowledge verify `
  --config configs/knowledge/training.yaml
& .\.venv\Scripts\python.exe -m edutwin_modeling.cli risk verify `
  --config configs/risk/training.yaml
Pop-Location
```

验证命令重新计算输入、配置、清单、模型、校准器、词表、预处理器和测试证据哈希，并实际加载七个候选制品。验证命令不重新训练，也不重新读取测试集生成指标。

## 演示初始状态与注册表

```powershell
Push-Location .\modeling
& .\.venv\Scripts\python.exe -m edutwin_modeling.cli demo bootstrap `
  --config configs/demo/bootstrap.yaml
& .\.venv\Scripts\python.exe -m edutwin_modeling.cli demo verify-bootstrap `
  --config configs/demo/bootstrap.yaml
Pop-Location

pwsh -File .\scripts\seed-demo\build-demo-registry.ps1
```

初始状态使用冻结 BKT、下一题胜者、风险胜者、校准器和原生 SHAP，为 `8,400` 条在读选课分别生成首个不可变快照、计划与模板诊断。注册表绑定双源处理版本、七个候选、三套 SHAP 制品、规则与诊断版本，以及 active/rollback 部署指针。

## 最终复核

```powershell
pwsh -File .\scripts\verify-delivery.ps1 `
  -Target All `
  -Server $env:EDUTWIN_SERVER `
  -SshUser $env:EDUTWIN_SSH_USER `
  -ServerBaseUrl http://localhost:18080
```

最终命令要求预先建立 `localhost:18080` 至服务器回环入口的 SSH 隧道，并重新执行数据复现、制品加载、单元与集成测试、本地闭环、故障注入、性能、重启、模型回滚、离线镜像部署和服务器验收。`reports/dist/acceptance-manifest.json` 记录代码树、数据、模型、镜像、迁移、测试退出码及本地和服务器证据。只有退出码为 `0` 且清单无开放项时，交付状态才为完成。
