# OULAD 许可说明

## 数据集

Open University Learning Analytics Dataset（OULAD）由 The Open University 发布，包含课程、注册、测评和虚拟学习环境行为表。

- [The Open University 数据集页面](https://research.stem.open.ac.uk/ouanalyse/dataset/)
- [Figshare 数据集记录](https://doi.org/10.6084/m9.figshare.5081998.v1)
- [数据集论文](https://doi.org/10.1038/sdata.2017.171)

## 许可

OULAD 采用 [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/) 许可。

CC BY 4.0 允许复制、再分发、转换和构建衍生材料，前提是保留适当署名、提供许可证链接并标明是否发生修改。许可不免除对隐私、研究伦理和适用法律的独立义务。

## 固定制品

| 属性 | 固定值 |
| --- | --- |
| 文件名 | `anonymisedData.zip` |
| Figshare file id | `8606371` |
| 文件大小 | `46,750,706` 字节 |
| MD5 | `7412686fd77cf0e0ee1e8c3e9b354308` |
| 固定下载地址 | `https://ndownloader.figshare.com/files/8606371` |
| OU CDN 地址 | `https://schools.stem.open.ac.uk/cdn/files/anonymisedData.zip` |

下载流程必须校验文件大小、ZIP 文件头和 MD5，并在本地计算 SHA-256 写入来源锁文件。旧 `analyse.kmi.open.ac.uk/open-dataset/download` 地址当前返回 HTML 页面，不属于有效数据端点。

## 引用

```text
Kuzilek, J., Hlosta, M. & Zdrahal, Z. Open University Learning Analytics dataset.
Scientific Data 4, 170171 (2017). https://doi.org/10.1038/sdata.2017.171
```

## EduTwin 数据边界

OULAD 原始 ZIP、解压 CSV 和训练侧特征仅保存在本地被忽略目录。服务器和容器镜像不包含原始数据或训练数据。公开文档保留来源、许可、处理配置、聚合统计和哈希，不发布原始学生级记录。
