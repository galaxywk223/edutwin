# 服务器部署

## 目标边界

服务器地址与 SSH 用户由部署环境显式提供，不写入仓库、接口契约或脚本默认值。服务器仅保存运行所需的 Compose 定义、环境文件、合成演示库导入制品和冻结推理制品。ASSISTments、OULAD 原始文件、处理表和训练集不得传输至服务器。

Caddy 默认仅绑定服务器回环接口 `127.0.0.1:80`。MySQL、Redis、Spring Boot、FastAPI 和前端 Nginx 只连接 Compose 网络。域名、DNS 和 HTTPS 配置完成前，应用不得开放公网入口。

部署环境变量如下：

```powershell
$Server = $env:EDUTWIN_SERVER
$SshUser = $env:EDUTWIN_SSH_USER
if ([string]::IsNullOrWhiteSpace($Server) -or [string]::IsNullOrWhiteSpace($SshUser)) {
  throw 'EDUTWIN_SERVER and EDUTWIN_SSH_USER are required.'
}
$Remote = "${SshUser}@${Server}"
```

## 主机初始化

初始化脚本安装 Docker Engine、Buildx、Compose 插件，启用 Docker 服务，创建至少 `2 GiB` swap，并将 `vm.swappiness` 固定为 `10`：

```powershell
scp -i $HOME\.ssh\id_rsa `
  .\infra\scripts\deploy\bootstrap-server.sh `
  "${Remote}:/tmp/edutwin-bootstrap-server.sh"

ssh -i $HOME\.ssh\id_rsa $Remote `
  "sh /tmp/edutwin-bootstrap-server.sh"
```

脚本可重复执行。`/opt/edutwin/releases` 保存冻结发布目录，`/opt/edutwin/shared` 保存受限环境配置。

## 私有 GHCR 凭据

应用镜像使用以下私有包：

- `ghcr.io/galaxywk223/edutwin-backend`
- `ghcr.io/galaxywk223/edutwin-model-service`
- `ghcr.io/galaxywk223/edutwin-frontend`

本地发布账号需要 `write:packages`。服务器使用独立的只读 classic PAT，令牌仅包含 `read:packages`，并保存为 `/opt/edutwin/shared/ghcr-read-token`：

```powershell
$token | ssh -i $HOME\.ssh\id_rsa $Remote `
  "umask 077; cat > /opt/edutwin/shared/ghcr-read-token"
```

令牌不得写入仓库、Compose 环境文件、发布目录、发布清单或命令日志。

## 增量镜像发布

发布流程固定执行以下步骤：

1. 本地以 `linux/amd64` 构建后端、模型服务和前端镜像。
2. 应用镜像推送至私有 GHCR；Registry 仅传输缺失的内容寻址层。
3. 发布清单记录完整 Git 版本、代码树哈希、镜像标签和 Registry 摘要。
4. SSH 仅传输 Compose、Caddy、环境配置和发布清单。
5. 服务器以只读 PAT 拉取应用镜像，以固定标签拉取 MySQL、Redis 和 Caddy。
6. 服务器执行 `--no-build` 启动并验证所有本地镜像摘要与发布清单一致。

模型运行时依赖和应用源码使用独立镜像层。PyTorch、PyArrow、CatBoost 等大型依赖仅在依赖声明或基础镜像变化时重新推送。后端演示数据库使用稳定链接层，普通 Java 代码变化不重新传输该层。

发布命令如下：

```powershell
$tag = (git rev-parse --short=12 HEAD).Trim()
& .\infra\scripts\deploy\deploy.ps1 `
  -ImageTag $tag `
  -Server $Server `
  -SshUser $SshUser `
  -IdentityFile $HOME\.ssh\id_rsa `
  -EnvFile .\infra\env\.env
```

服务器不得运行应用构建、数据准备、候选训练、测试集评估或模型选择命令。

## SSH 隧道验收

回环入口通过 SSH 本地端口转发验收：

```powershell
ssh -i $HOME\.ssh\id_rsa `
  -N -L 127.0.0.1:18080:127.0.0.1:80 `
  $Remote
```

隧道运行期间，另一个终端执行以下检查：

```powershell
curl.exe --fail --header "Host: localhost" http://127.0.0.1:18080/healthz

pwsh -File .\scripts\verify-delivery.ps1 `
  -Target Server `
  -Server $Server `
  -SshUser $SshUser `
  -ServerBaseUrl http://localhost:18080 `
  -IdentityFile $HOME\.ssh\id_rsa `
  -EnvFile .\infra\env\.env
```

远端验收至少检查以下项目：

- `docker compose ps` 中全部服务稳定且健康。
- `swapon --show --bytes` 总量不少于 `2000000000`。
- `sysctl -n vm.swappiness` 返回 `10`。
- Caddy 仅发布至 `127.0.0.1:80`，其他应用服务不发布主机端口。
- 运行镜像架构为 `linux/amd64`，Registry 摘要与发布清单一致。
- 镜像文件系统不包含 `data/raw`、`data/interim` 或 `data/processed`。
- 隧道健康检查返回 `200`。
- 登录、答题、SSE、孪生、计划和教师驾驶舱闭环通过；管理员和辅导员权限闭环单独通过。
- 重启恢复、模型回滚和性能门禁分别生成远端证据。

## HTTPS 上线条件

公网入口保持关闭，直到以下条件全部满足：

1. 已配置专用域名及正确的 DNS 记录。
2. `EDUTWIN_SITE_ADDRESS` 设置为该域名的 HTTPS 地址。
3. Compose 发布端口改为经过评审的 `80/tcp` 与 `443/tcp` 公网绑定。
4. Caddy 自动签发证书成功，HTTPS 健康检查通过。
5. 防火墙、备份、监控、速率限制和回滚演练完成。

未满足上线条件时，回环绑定不得改为公网绑定。
