# ASSISTments 2009-2010 使用条款

## 许可性质

ASSISTments 2009-2010 Skill Builder 数据不采用标准开放数据许可证。数据使用受 ASSISTments/WPI 发布的非标准条款约束，原始条款具有优先效力：

- [Terms Of Use For Using Data](https://sites.google.com/site/assistmentsdata/termsofuseforusingdata)
- [2009-2010 Skill Builder 官方说明页](https://sites.google.com/site/assistmentsdata/home/2009-2010-assistment-data/skill-builder-data-2009-2010)

## 主要约束

条款包含以下数据保护与研究使用要求：

- 数据仅用于声明的研究或分析目的。
- 禁止尝试识别匿名学生或关联个人身份信息。
- 发现可能识别个人的信息后必须删除相关信息并通知 ASSISTments/WPI。
- 原始数据不得转交或再分发给其他主体。
- 论文、报告和其他成果必须确认 ASSISTments TestBed 的数据贡献。
- 使用该数据产生的研究代码和算法必须按原始条款公开。

该条款摘要仅用于工程约束。数据使用资格、用途声明和成果发布仍以原始条款为准。

## EduTwin 数据边界

EduTwin 仅在本地数据工程环境保存原始文件和训练侧处理中间文件。以下位置禁止包含 ASSISTments 原始数据、修正镜像或训练数据：

- Git 提交与发布附件
- Docker 构建上下文与容器镜像
- 公网演示服务器
- 前端静态资源与业务后端种子包
- 报告附件和可公开下载的制品

服务器和演示库仅包含由数据工程流程生成的合成学生、去标识化聚合指标、模型制品及其不可逆哈希。

## 来源关系

官方原始非折叠文件 `skill_builder_data.csv` 由 [ASSISTments 官方数据页](https://sites.google.com/site/assistmentsdata/home/2009-2010-assistment-data/skill-builder-data-2009-2010) 提供。下载能力 URL 仅通过 `EDUTWIN_ASSISTMENTS_DOWNLOAD_URL` 注入，不写入仓库或源锁。官方当前提供的 corrected 文件为 [`skill_builder_data_corrected_collapsed.csv`](https://drive.google.com/file/d/1NNXHFRxcArrU0ZJSb9BIL56vmUt5FhlE/view)，该文件已经把多知识点折叠到单行，不作为 EduTwin 训练输入。

USTC EduData 提供的 [`2009_skill_builder_data_corrected.zip`](https://base.ustc.edu.cn/data/ASSISTment/2009_skill_builder_data_corrected.zip) 属于 corrected 非折叠学术镜像，不属于 ASSISTments 官方托管制品。canonical 文件必须由官方原始文件执行全行精确去重生成，并与 USTC 镜像执行逐行和规范化语义哈希复核。任一复核失败均使数据门禁失败。

## 引用

数据来源说明至少包含以下内容：

```text
ASSISTments 2009-2010 Skill Builder data, released by the ASSISTments/WPI research team.
```

具体论文引用应结合使用的数据版本和研究方法补充，不得将 USTC 镜像描述为 ASSISTments 官方下载端点。
