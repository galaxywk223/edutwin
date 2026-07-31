# EduTwin

[English](./README.en.md) | 中文

[![CI](https://github.com/galaxywk223/edutwin/actions/workflows/ci.yml/badge.svg)](https://github.com/galaxywk223/edutwin/actions/workflows/ci.yml)
[![Pages](https://github.com/galaxywk223/edutwin/actions/workflows/pages.yml/badge.svg)](https://galaxywk223.github.io/edutwin/)
[![License](https://img.shields.io/badge/license-Apache--2.0-2563eb.svg)](./LICENSE)

EduTwin 是面向高校教学场景的学习分析与学情画像平台。项目将课程活动、作答事件、知识追踪、风险预测和学习计划组织为可追溯的异步闭环，并为学生、教师、辅导员和系统管理员提供独立工作台。

![教师教学分析工作台](./docs/assets/showcase-teacher.png)

## 在线演示

GitHub Pages 演示运行在浏览器内，使用确定性合成数据，不连接真实后端，不需要账号、API Key 或受限数据集。

- [打开 EduTwin 交互演示](https://galaxywk223.github.io/edutwin/)
- 登录页支持学生、教师、辅导员和系统管理员四种角色一键进入。
- 练习、计划、风险反馈和助手对话在当前浏览器会话内模拟状态变化。

## 核心能力

| 角色 | 工作流 |
| --- | --- |
| 学生 | 课程学习、作业测验、针对性练习、学情画像、学习计划、风险行动项 |
| 教师 | 课程管理、教学内容、考核与名单、教学分析、学生画像、风险工单 |
| 辅导员 | 授权范围内的学生统计、班级对比、跨课程风险与干预跟踪 |
| 系统管理员 | 用户与组织、数据和模型版本、AI 配置、审计与运行治理 |

### 四角色工作台

| 学生学情画像 | 教师教学分析 |
| --- | --- |
| ![学生学情画像](./docs/assets/showcase-student.png) | ![教师教学分析](./docs/assets/showcase-teacher.png) |
| 辅导员学业观察 | 系统管理员治理 |
| ![辅导员学业观察](./docs/assets/showcase-counselor.png) | ![系统管理员治理](./docs/assets/showcase-admin.png) |

助手基于当前角色的只读数据边界返回模板诊断，并明确标注合成数据来源。

![EduTwin 助手](./docs/assets/showcase-assistant.png)

核心处理链路：

```text
学习活动与作答
  -> 事务内写入 Answer / Analysis Job / Outbox
  -> Redis Stream 异步分析
  -> 掌握度、下一题概率与课程风险
  -> 不可变学情画像、规则学习计划与结构化诊断
  -> SSE 刷新学生和教师界面
```

## 系统架构

![EduTwin 系统架构](./docs/assets/architecture-overview.png)

| 模块 | 技术与职责 |
| --- | --- |
| `frontend` | Vue 3、TypeScript、Vite、Element Plus、ECharts |
| `backend` | Java 21、Spring Boot、Spring Security、Flyway、Outbox |
| `modeling` | FastAPI、PyTorch、scikit-learn、LightGBM、CatBoost、SHAP |
| `contracts` | OpenAPI 3.1 与 AsyncAPI 合同 |
| `infra` | Docker Compose、Caddy、备份、回滚与发布脚本 |
| `data` | 来源许可、哈希清单、合成演示数据和复现配置 |

## 快速开始

### 浏览器演示

```powershell
Set-Location frontend
npm ci
npm run dev:showcase
```

开发服务器地址为 `http://localhost:5173/`。

### 精简全栈演示

Docker Desktop 或 Docker Engine 24+ 是唯一前置条件。演示栈不下载 ASSISTments/OULAD，不加载训练模型，不调用外部大模型。

```powershell
docker compose -f infra/compose/showcase.yaml up --build --wait
```

系统地址为 `http://localhost:8080/`。停止命令如下：

```powershell
docker compose -f infra/compose/showcase.yaml down --volumes
```

## 数据与模型边界

- Showcase 数据全部由固定种子生成，不包含真实学生、学校或课程记录。
- ASSISTments 2009-2010 数据受非标准研究条款约束，原始文件和学生级记录禁止进入 Git、镜像、Release 或公开演示。
- OULAD 采用 CC BY 4.0；完整署名和固定来源见 [数据来源与许可](./docs/data/sources-and-licenses.md)。
- 完整研究模式保留 IRT、BKT、DKT、AKT、Logistic Regression、LightGBM、CatBoost 和 SHAP 流程，但不属于首次演示前置条件。
- 默认 AI 功能关闭。规则引擎和模板诊断保证无 API Key 时仍可完成业务闭环。

## 测试证据

普通 PR 持续验证前端类型与交互、Spring 业务测试、Python 建模测试、OpenAPI/AsyncAPI 合同、两套 Compose 配置和完整 Git 历史安全扫描。Pages 构建单独验证固定种子生成与浏览器展示产物。完整数据训练、性能与服务器验收保留为手动工作流。

## 开发与验证

```powershell
# 前端
npm --prefix frontend ci
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend run build

# 后端
.\backend\mvnw.cmd -f backend\pom.xml test

# 模型
.\modeling\.venv\python.exe -m ruff check modeling
.\modeling\.venv\python.exe -m mypy --config-file modeling\pyproject.toml modeling\src
.\modeling\.venv\python.exe -m pytest modeling\tests

# 合同与安全
pwsh -File scripts/check/verify-contracts.ps1
pwsh -File scripts/verify/verify-public-history.ps1
```

完整数据处理、训练与部署说明位于 [项目文档索引](./docs/README.md)。

## 路线图

- 扩展 showcase 的课程语义和可交互分析场景。
- 增加模型实验结果的可视化比较与版本回放。
- 完善无障碍审计、国际化和浏览器兼容测试。
- 提供可选的 OIDC/校园统一身份认证适配层。

## 参与项目

贡献流程见 [CONTRIBUTING.md](./CONTRIBUTING.md)，安全问题见 [SECURITY.md](./SECURITY.md)。项目采用 [Apache License 2.0](./LICENSE)，数据来源适用独立条款，详见 [NOTICE](./NOTICE)。
