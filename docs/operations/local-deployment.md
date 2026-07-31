# 本地容器部署

## 部署边界

本地部署由 `infra/compose/compose.yaml` 定义。部署目标固定为 `linux/amd64`，包含以下服务：

| 服务 | 职责 | 网络 | 公网端口 |
| --- | --- | --- | --- |
| `mysql` | 业务事实、任务、快照和 Flyway 迁移 | `data` | 无 |
| `redis` | Stream、任务状态与派生缓存 | `data` | 无 |
| `model-service` | 冻结制品 CPU 推理 | `services` | 无 |
| `backend` | 公共 API、事件编排与规则降级 | `services`、`data` | 无 |
| `frontend` | Vue 静态资源 | `edge` | 无 |
| `caddy` | HTTP 入口和反向代理 | `edge`、`services` | `80/tcp` |

`data` 网络设置为 Compose 内部网络。模型服务、Actuator、MySQL 和 Redis 不通过 Caddy 暴露。公网 HTTP 仅用于合成数据课程演示。

## 构建前置条件

镜像构建要求以下制品已经存在：

- `frontend/package.json`、`frontend/package-lock.json`、Vite 配置和可构建前端源码。
- `edutwin_modeling.serving.app:app` FastAPI 应用。
- `modeling/configs/serving/service.yaml`。
- `modeling/artifacts/knowledge`、`modeling/artifacts/risk` 和 `modeling/artifacts/manifests` 下的冻结推理制品。
- `data/demo/generated/manifest.json`、`database/registry.json`、数据库 CSV 和 `database/knowledge_lineage.csv` 标识映射制品。

数据处理和模型测试完成后，`pwsh -File .\scripts\seed-demo\build-demo-registry.ps1` 生成数据库注册表。完整导入合同见 `docs/data/demo-database-import.md`。

模型镜像仅从 `data/` 复制演示清单与题目、知识点标识映射，不复制学生来源映射、答题数据、训练配置、notebook 或处理中数据。后端镜像复制演示清单和数据库导入 CSV，用于空数据库事务导入。根目录 `.dockerignore` 排除 `data/raw`、`data/interim`、`data/processed`、报告和本地环境文件。

## 环境配置

环境模板位于 `infra/env/.env.example`。本地环境文件固定为 `infra/env/.env`，不得进入 Git。

```powershell
Copy-Item .\infra\env\.env.example .\infra\env\.env
```

所有 `CHANGE_ME` 值必须替换。`EDUTWIN_JWT_SECRET` 至少包含 32 个随机字节。`EDUTWIN_AI_CONFIG_ENCRYPTION_KEY` 使用 32 字节随机值的 Base64 编码，负责加密管理员保存的 API Key。`EDUTWIN_ADMIN_USERNAME`、`EDUTWIN_ADMIN_PASSWORD` 和 `EDUTWIN_ADMIN_DISPLAY_NAME` 幂等引导首个系统管理员，管理员密码至少 16 位。`EDUTWIN_DEMO_TEACHER_PASSWORD` 和 `EDUTWIN_DEMO_STUDENT_PASSWORD` 分别控制教师和全部合成学生的演示密码。供应商 Key 仅写入未跟踪的 `.env` 或管理员加密配置；默认配置关闭 AI 并使用规则降级。

配置检查命令如下：

```powershell
docker compose `
  --env-file .\infra\env\.env `
  -f .\infra\compose\compose.yaml `
  config --quiet
```

## 数据库工作台接入

MySQL 工作台接入由 `infra/compose/workbench.override.yaml` 按需启用。默认 Compose 不发布数据库端口，服务器发布、重启、回滚和验收流程不得加载该覆盖文件。

启用命令如下：

```powershell
docker compose `
  --env-file .\infra\env\.env `
  -f .\infra\compose\compose.yaml `
  -f .\infra\compose\workbench.override.yaml `
  up -d --no-deps --wait mysql
```

连接参数如下：

| 参数 | 值 |
| --- | --- |
| 主机 | `127.0.0.1` |
| 端口 | `3307` |
| 用户 | `MYSQL_USER` 的配置值 |
| 密码 | `MYSQL_PASSWORD` 的配置值 |
| 默认数据库 | `MYSQL_DATABASE` 的配置值 |

恢复默认网络隔离的命令如下：

```powershell
docker compose `
  --env-file .\infra\env\.env `
  -f .\infra\compose\compose.yaml `
  up -d --no-deps --force-recreate --wait mysql
```

## 镜像构建

本地构建固定使用 BuildKit 和 `linux/amd64`：

```powershell
$env:DOCKER_DEFAULT_PLATFORM = 'linux/amd64'
docker compose `
  --env-file .\infra\env\.env `
  -f .\infra\compose\compose.yaml `
  build --pull
```

镜像标签由 `EDUTWIN_IMAGE_TAG` 冻结。发布标签应使用代码树或发布提交标识，不得复用可变标签。

## 启动与检查

```powershell
docker compose `
  --env-file .\infra\env\.env `
  -f .\infra\compose\compose.yaml `
  up -d --remove-orphans

docker compose `
  --env-file .\infra\env\.env `
  -f .\infra\compose\compose.yaml `
  ps
```

系统入口为 `http://localhost/`，入口健康检查为 `http://localhost/healthz`。后端由 Flyway 在启动时执行前向迁移。所有服务必须进入 `healthy` 或稳定 `running` 状态后再执行闭环验收。

## 停止

```powershell
docker compose `
  --env-file .\infra\env\.env `
  -f .\infra\compose\compose.yaml `
  down
```

普通停止不删除命名卷。`down --volumes` 会删除 MySQL、Redis 和 Caddy 状态，只能在已有验证备份且明确重建环境时使用。

## 资源约束

Compose 为所有服务设置 CPU、内存、PID、日志轮转与停止宽限期。模型服务固定单进程 CPU 推理。Redis 使用 AOF，但 MySQL 始终是事实来源；Redis 数据丢失后由业务恢复逻辑重建。
