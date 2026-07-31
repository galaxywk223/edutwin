# 版本回滚

## 回滚边界

EduTwin 提供发布镜像回滚与模型候选回滚。数据库迁移遵循 Flyway 前向兼容原则，不自动执行逆向 DDL。

| 变更类型 | 回滚动作 |
| --- | --- |
| 应用或完整模型制品发布异常 | 切换旧冻结镜像标签 |
| 已执行不兼容 Schema 迁移 | 恢复发布前 MySQL 备份，再切换镜像 |
| Redis Stream 或缓存异常 | 恢复 Redis，由 MySQL Outbox 和事实表重建投影 |
| 当前胜者模型异常 | 同步切换模型服务加载模式与数据库部署指针至 runner-up 冻结候选 |

## 发布镜像回滚

```powershell
pwsh -File .\infra\scripts\rollback\rollback.ps1 `
  -ImageTag <frozen-release-tag> `
  -EnvFile .\infra\env\.env
```

脚本拉取指定标签的后端、模型服务和前端镜像，并以 `--no-build` 方式重新创建 Compose 服务。标签必须对应不可变发布清单。

## 数据库与镜像联合回滚

```powershell
pwsh -File .\infra\scripts\rollback\rollback.ps1 `
  -ImageTag <frozen-release-tag> `
  -DatabaseBackup D:\EduTwinBackups\edutwin-mysql-20260711T120000Z.sql.gz `
  -DatabaseBackupSha256 <sha256> `
  -EnvFile .\infra\env\.env
```

联合回滚先执行数据库安全备份和恢复，再切换冻结镜像。数据库恢复后，Redis 投影由 MySQL 重建。

## 模型候选回滚

模型镜像同时包含验证集胜者与 runner-up 冻结制品。`EDUTWIN_MODEL_DEPLOYMENT_MODE` 固定接受以下值：

| 值 | 加载结果 |
| --- | --- |
| `active` | 加载知识与风险验证集胜者 |
| `rollback` | 加载另一个 DKT/AKT 候选、固定顺序的风险 runner-up 及其原生 SHAP 解释器 |

模型回滚事务同步交换 `NEXT_CORRECT`、`RISK` 和 `EXPLANATION` 的 `active_version_id` 与 `rollback_version_id`，并同步更新对应 `model_version.status`。模型服务仅在数据库指针交换成功后以 `rollback` 模式重建。

本地与服务器验收脚本如下：

```powershell
pwsh -File .\tests\resilience\verify-model-rollback.ps1 `
  -BaseUrl http://127.0.0.1 `
  -EnvFile .\infra\env\.env `
  -OutputPath .\.runtime\model-rollback.json

pwsh -File .\scripts\verify\verify-server-rollback.ps1 `
  -Server $Server `
  -SshUser $SshUser `
  -BaseUrl http://localhost:18080 `
  -EnvFile .\infra\env\.env `
  -ClosedLoopEvidence <server-closed-loop.json> `
  -IdentityFile <ssh-key> `
  -OutputPath .\.runtime\server-rollback.json
```

## 回滚验证

模型回滚完成条件如下：

1. `NEXT_CORRECT`、`RISK` 和 `EXPLANATION` 激活版本均与回滚部署指针一致。
2. 回滚版本与原胜者版本不同，且均引用已冻结的实际制品。
3. 公共答题接口返回 `202`，任务通过 SSE 完成。
4. 回滚闭环不得出现知识、风险、超时或模型合同降级。
5. 回滚快照的有效模型版本与数据库部署指针一致。
6. 胜者恢复后再次完成公共闭环，且有效版本重新匹配原部署指针。
7. Compose 服务无 OOM 或持续重启。

任一条件失败时，回滚门禁保持失败，验收清单不得标记完成。
