# 备份与恢复

## 备份范围

备份事实源为 MySQL。`infra/scripts/backup/backup.ps1` 使用一致性事务生成压缩 SQL，并记录以下元数据：

- UTC 创建时间；
- SQL Gzip 文件名、字节数与 SHA-256；
- 当前 Compose 镜像清单。

Redis 不作为业务备份源。Redis 保存 Stream 和派生缓存，其状态可由 MySQL 中的 Outbox、分析任务、快照和计划恢复。

## 创建备份

```powershell
pwsh -File .\infra\scripts\backup\backup.ps1 `
  -EnvFile .\infra\env\.env
```

默认输出目录为 `infra/backups`，默认保留 14 天。输出目录在 `.gitignore` 和 `.dockerignore` 范围之外仍不得加入 Git 或镜像构建上下文。

自定义目录与保留期命令如下：

```powershell
pwsh -File .\infra\scripts\backup\backup.ps1 `
  -EnvFile .\infra\env\.env `
  -OutputDirectory D:\EduTwinBackups `
  -RetentionDays 30
```

备份完成后，元数据 JSON 路径写入标准输出。异机保存必须同时复制 `.sql.gz` 与对应 JSON。

## 恢复流程

恢复属于破坏性操作。流程停止后端、导入 SQL、清空 Redis、再启动后端。默认情况下，恢复前创建一次安全备份。

```powershell
pwsh -File .\infra\scripts\backup\restore.ps1 `
  -BackupFile D:\EduTwinBackups\edutwin-mysql-20260711T120000Z.sql.gz `
  -ExpectedSha256 <sha256> `
  -EnvFile .\infra\env\.env
```

`ExpectedSha256` 应来自对应元数据 JSON。无校验哈希的恢复不适用于服务器验收或发布回滚。

恢复完成后必须执行以下检查：

1. MySQL 健康检查通过。
2. Flyway Schema 历史与目标应用版本兼容。
3. 后端健康检查通过。
4. Redis 缓存与未终止任务由恢复流程重新建立。
5. 公共答题闭环和 SSE 断线恢复通过。

## 恢复限制

SQL 恢复不替代模型制品回滚。数据库、后端、模型服务和前端版本必须来自同一冻结发布清单。服务器备份文件不得包含 ASSISTments 或 OULAD 原始数据和训练数据。
