# 数据来源与许可

## 来源清单

机器可读来源定义位于 `data/manifests/sources.yaml`。完整下载成功后，`scripts/data/download-datasets.ps1` 生成 `data/manifests/source-lock.json`，记录实际文件大小、SHA-256、OULAD MD5、ASSISTments 规范化语义哈希和解压文件清单。

| 数据集 | canonical 来源 | 交付端点 | 许可状态 |
| --- | --- | --- | --- |
| ASSISTments 2009-2010 Skill Builder | 官方原始非折叠文件经稳定谱系去重 | 官方 Google Drive 原始文件 + USTC corrected 非折叠复核镜像 | ASSISTments/WPI 非标准条款，禁止再分发 |
| OULAD | Figshare v1 固定 ZIP | Figshare file `8606371` | CC BY 4.0 |

## ASSISTments 来源链

ASSISTments 官方说明页当前区分以下两个文件：

1. `skill_builder_data.csv`：官方原始非折叠文件，`83,201,940` 字节，包含重复记录。文件入口由 [ASSISTments 官方数据页](https://sites.google.com/site/assistmentsdata/home/2009-2010-assistment-data/skill-builder-data-2009-2010) 发布。
2. [`skill_builder_data_corrected_collapsed.csv`](https://drive.google.com/file/d/1NNXHFRxcArrU0ZJSb9BIL56vmUt5FhlE/view)：官方 corrected 文件，`64,412,812` 字节，多个知识点已经折叠到单行。

EduTwin 要求保留一个答题事件对应多条知识点关联，因此官方 collapsed 文件不得进入训练主路径。canonical corrected 非折叠文件按以下链路生成：

下载脚本要求运行环境提供 `EDUTWIN_ASSISTMENTS_DOWNLOAD_URL`。该变量保存官方原始文件的能力 URL；`sources.yaml` 与 `source-lock.json` 仅记录变量名、官方落地页和内容哈希。

```text
official skill_builder_data.csv
  -> 排除 answer_text、opportunity、opportunity_original 后按稳定谱系去重
  -> 保留官方文件中每个谱系的首次记录和 answer_text
  -> canonical skill_builder_data_corrected.csv
  -> 与 USTC corrected 非折叠镜像执行一对一谱系与训练字段复核
  -> 计算排除 answer_text 的 canonical 语义 SHA-256
  -> 通过后进入知识模型处理
```

固定质量指标如下：

| 指标 | 预期值 |
| --- | ---: |
| 官方原始数据行 | 525,534 |
| 重复谱系删除行 | 123,778 |
| 仅 opportunity 计数变化的重复谱系行 | 123,778 |
| corrected 非折叠数据行 | 401,756 |
| 唯一 `order_id` | 346,860 |
| 镜像 `answer_text` 编码差异行 | 83 |
| 镜像保留行 opportunity 差异 | 0 |
| 字段数 | 30 |

同一 `order_id` 只允许 `skill_id`、`skill_name`、`opportunity` 和 `opportunity_original` 随知识点变化。其他核心字段发生冲突时，canonical 门禁失败。下游流程再把相同 `order_id` 折叠为一个答题事件和多条知识点关联。

[`2009_skill_builder_data_corrected.zip`](https://base.ustc.edu.cn/data/ASSISTment/2009_skill_builder_data_corrected.zip) 属于独立学术镜像，不属于 ASSISTments 官方托管文件。其固定 ZIP 元数据为：

- ZIP：`2009_skill_builder_data_corrected.zip`，`9,084,422` 字节
- 内部 CSV：`skill_builder_data_corrected.csv`，`63,455,745` 字节
- 内部 CSV CRC32：`E0A1CC26`

## 拒绝的 ASSISTments 输入

| 来源 | 拒绝原因 |
| --- | --- |
| 官方 `skill_builder_data_corrected_collapsed.csv` | 多知识点已折叠，不满足非折叠输入合同 |
| Hugging Face `OloriBern/assistments-dataset/skill_builder_data_corrected.csv` | 文件名误导；文件大小和抽样内容与官方 collapsed 制品一致 |
| WPI `users.wpi.edu` 旧直链 | 当前返回错误或 TLS 失败，不具备稳定下载条件 |

## OULAD 来源链

OULAD 使用 Figshare v1 固定制品：

- 数据集 DOI：`10.6084/m9.figshare.5081998.v1`
- 固定文件：[`anonymisedData.zip`](https://ndownloader.figshare.com/files/8606371)
- OU 当前 CDN：`https://schools.stem.open.ac.uk/cdn/files/anonymisedData.zip`
- 大小：`46,750,706` 字节
- MD5：`7412686fd77cf0e0ee1e8c3e9b354308`
- 许可：CC BY 4.0

The Open University 当前 CDN 文件与 Figshare 制品具有相同文件名和大小。下载主路径使用可固定 file id 和公开 MD5 的 Figshare 制品。旧 `analyse.kmi.open.ac.uk/open-dataset/download` 与 checksum 地址当前重定向到 HTML 页面，下载器不得使用这些地址。

## 下载与锁定

数据下载命令如下：

```powershell
pwsh -File .\scripts\data\download-datasets.ps1
```

重新获取全部来源时使用：

```powershell
pwsh -File .\scripts\data\download-datasets.ps1 -Force
```

流程依次执行以下门禁：

1. HTTP 状态、重定向终点、Content-Type 和 Content-Length 校验。
2. CSV 固定表头或 ZIP `PK` 文件头校验。
3. 下载文件实际大小校验。
4. OULAD 固定 MD5 校验。
5. ZIP 条目名称、数量、大小和 ASSISTments CRC32 校验。
6. ASSISTments 官方原始文件稳定谱系去重、核心字段一致性检查、镜像一对一谱系与训练字段比较，以及 canonical 语义 SHA-256 计算。
7. 所有源文件与解压文件 SHA-256 计算。
8. `data/manifests/source-lock.json` 原子写入。

任一门禁失败时不更新来源锁文件。

## 存储与部署边界

`data/raw/**`、`data/interim/**` 和 `data/processed/**` 不进入 Git。Docker 构建上下文、镜像、服务器传输包和公网演示环境不得包含原始数据、corrected 镜像、解压表或训练集。服务器仅接收模型推理制品、版本元数据、聚合统计和合成演示数据。

合成学生的跨来源谱系只保存域分离 lowercase SHA-256。ASSISTments 与 OULAD 原始学生标识不得进入 `source_lineage.csv`、MySQL、镜像或服务器。知识点和题目来源键仅用于模型训练词表映射，不包含学生标识或来源行为。

ASSISTments 具体约束见 `data/licenses/ASSISTMENTS-2009-2010.md`。OULAD 署名与许可文本见 `data/licenses/OULAD.md`。
