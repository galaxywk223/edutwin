# OULAD 风险模型卡

## 模型用途

风险模型估计课程开课后第 `0-29` 日行为对应的最终失败或晚期退课概率。模型仅用于合成数据课程演示和学习支持优先级排序，不构成学业处分、录取或成绩决定依据。

## 数据与标签

| 项目 | 固定定义 |
| --- | --- |
| 数据集 | OULAD Figshare v1 |
| 特征窗口 | 开课后第 `0-29` 日，双端包含 |
| 正类 | `Fail` 或 `date_unregistration > 29` 的 `Withdrawn` |
| 负类 | `Pass` 或 `Distinction` |
| 排除样本 | `date_unregistration <= 29` 的 `Withdrawn` |
| 学生切分 | 稳定哈希 `70/15/15`，种子 `42` |

`final_result` 和 `date_unregistration` 只参与离线标签生成。特征表、模型输入、模型制品和服务请求均不得包含这两个字段或第 30 日后的行为。

## 特征合同

三个候选模型共享 `oulad-d0-29-v1` 合同：

1. `vle_total_clicks`
2. `vle_interaction_count`
3. `vle_active_days`
4. `vle_active_weeks`
5. `vle_resource_count`
6. `assessment_count`
7. `assessment_scored_count`
8. `assessment_mean_score`

训练拟合学生用于均值和标准差估计。训练校准学生用于 Platt 校准。验证和测试学生不得参与特征处理或校准器拟合。

## 候选与选择

| 候选 | 实现 |
| --- | --- |
| Logistic Regression | scikit-learn `LogisticRegression` |
| LightGBM | `LGBMClassifier` |
| CatBoost | `CatBoostClassifier` |

验证集首先比较 PR-AUC。与最佳 PR-AUC 差值不超过 `0.005` 的候选依次比较 Brier Score、单请求 CPU P95 延迟和固定族名称。测试集只在配置和验证胜者冻结后执行一次。

## 校准与风险带

原始概率经训练侧 Platt 校准器转换。默认风险带边界如下：

| 风险带 | 概率范围 |
| --- | --- |
| `LOW` | `< 0.35` |
| `MEDIUM` | `>= 0.35` 且 `< 0.65` |
| `HIGH` | `>= 0.65` |

## 解释

Logistic Regression 使用线性模型精确 log-odds 贡献。LightGBM 和 CatBoost 使用各自原生 TreeSHAP。服务按绝对贡献降序输出前五个因素，并保留原始值、方向、贡献、基线值和风险模型版本。生成式模型不得补充 SHAP 结果之外的风险原因。

## 局限

- OULAD 行为与 EduTwin 合成练习行为只共享聚合特征合同，不代表课程内容或人群完全等价。
- 第 29 日后的风险演化不属于该模型输入合同。
- 模型输出反映统计关联，不构成因果解释。
- 合成题目映射到训练词表未知项时，知识模型精度与风险模型无直接等价关系。
