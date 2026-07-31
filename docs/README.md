# EduTwin 文档索引

## 架构与合同

- [事件语义与异步闭环](./architecture/event-semantics.md)
- [核心类型](./requirements/core-types.md)
- [用例](./requirements/use-cases.md)
- [验收矩阵](./requirements/acceptance-matrix.md)
- [数据库实体关系](./database/er.md)
- [OpenAPI 3.1](../contracts/openapi/public-api.yaml)
- [AsyncAPI](../contracts/asyncapi/analysis-events.yaml)

## 展示模式

Showcase profile 使用固定种子 `42` 生成 96 名学生、4 个班级、6 门课程、3 名教师、1 名辅导员和 1 名管理员。数据不包含真实身份信息、受限来源记录或训练模型制品。

| 入口 | 运行模式 | 数据与推理 |
| --- | --- | --- |
| GitHub Pages | 浏览器内传输适配 | 确定性合成数据、规则推理、模板回复 |
| `infra/compose/showcase.yaml` | Vue 前端、Showcase API、Caddy | 同一生成器产物、内存状态、SSE 模拟 |
| `infra/compose/compose.yaml` | Spring、MySQL、Redis、FastAPI、Vue、Caddy | 完整研究配置与部署边界 |

展示数据生成命令：

```powershell
npm --prefix frontend run generate:showcase
```

生成清单位于 `frontend/public/showcase-manifest.json`。清单固定记录 profile、实体计数、合成标记、推理模式、受限来源行数和外部密钥要求。

## 公开展示素材

- `assets/showcase-login.png`：四角色演示入口。
- `assets/showcase-student.png`：学生学情画像。
- `assets/showcase-teacher.png`：教师教学分析。
- `assets/showcase-counselor.png`：辅导员学业观察。
- `assets/showcase-admin.png`：系统治理概览。
- `assets/showcase-assistant.png`：基于合成数据的助手响应。
- `assets/architecture-overview.png`：完整研究模式架构。
- `assets/social-preview.png`：GitHub 社交预览图，尺寸为 1280 x 640。

## 数据与模型

- [数据来源与许可](./data/sources-and-licenses.md)
- [演示数据库导入](./data/demo-database-import.md)
- [训练复现](./models/training-reproduction.md)
- [模型卡](./models/model-card.md)

ASSISTments 原始数据不得再分发。OULAD 使用 CC BY 4.0。Showcase 数据独立生成，不复制两个研究来源中的学生级记录。

## 运行与维护

- [本地部署](./operations/local-deployment.md)
- [服务器部署](./operations/server-deployment.md)
- [备份与恢复](./operations/backup-and-restore.md)
- [回滚](./operations/rollback.md)

完整研究模式需要单独获取数据、冻结配置并执行训练与模型注册。Showcase 运行不依赖该流程。
