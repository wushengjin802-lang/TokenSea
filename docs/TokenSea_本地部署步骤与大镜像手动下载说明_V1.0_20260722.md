# TokenSea 本地部署步骤与大镜像手动下载说明

版本：V1.0  
日期：2026-07-22  
适用目录：`D:\12_其他项目\30_APIGateway\tokensea`

## 1. 本次部署结论

本次部署最终完成。部署过程中主要耗时来自：

1. Docker Desktop 重装后需要重新拉取基础镜像。
2. Docker Desktop 代理端口配置为 `7890`，但本机实际代理监听 `7897`，导致 Docker Hub 返回连接拒绝。
3. Maven Central TLS 握手不稳定，Control Plane 镜像构建失败。
4. Gateway Runtime 首次启动早于数据库迁移完成，出现 `accounting_outbox does not exist`，需要在 Control Plane 完成迁移后重新创建 Gateway Runtime。

## 2. 部署前检查

### 2.1 Docker Desktop

确认 Docker Desktop 已启动，并执行：

```powershell
docker info --format 'Server={{.ServerVersion}}; Mirrors={{json .RegistryConfig.Mirrors}}'
```

如果使用本机代理，确认代理端口确实有监听：

```powershell
Get-NetTCPConnection -State Listen |
  Where-Object { $_.LocalPort -in 7890,7897,1080,8080,3128 } |
  Select-Object LocalAddress,LocalPort,OwningProcess
```

Docker Desktop 中的 HTTP/HTTPS 代理必须填写实际监听端口。代理修改后，使用 `Apply & Restart` 重启 Docker Desktop。

### 2.2 Compose 配置

所有 Compose 命令统一使用项目名 `tokensea` 和部署目录中的 `.env`：

```powershell
cd D:\12_其他项目\30_APIGateway\tokensea\deploy\compose
docker compose -p tokensea --env-file ./.env config --quiet
```

不要使用根目录中不存在或过期的 `.env` 文件，也不要混用其他 Compose 项目名。

## 3. 全量部署步骤

### 3.1 全量重建

Java、Python、Dockerfile 或基础镜像发生变化时执行：

```powershell
& "$env:USERPROFILE\.codex\skills\tokensea-local\scripts\start-tokensea.ps1" `
  -ProjectRoot "D:\12_其他项目\30_APIGateway\tokensea" `
  -RebuildAll
```

普通重启或已完成镜像构建后执行：

```powershell
& "$env:USERPROFILE\.codex\skills\tokensea-local\scripts\start-tokensea.ps1" `
  -ProjectRoot "D:\12_其他项目\30_APIGateway\tokensea"
```

不要使用 `-ResetVolumes`，除非明确接受数据库和 Redis 数据清空。

### 3.2 首次启动后的 Gateway Runtime 修复

如果 Gateway Runtime 日志出现：

```text
relation "accounting_outbox" does not exist
```

说明 Control Plane 的 Flyway 迁移尚未完成。等待 Control Plane 健康后执行：

```powershell
cd D:\12_其他项目\30_APIGateway\tokensea\deploy\compose
docker compose -p tokensea --env-file ./.env up -d --force-recreate tokensea-gateway-runtime
```

## 4. 体积较大或跨注册表镜像的手动下载

浏览器页面不能直接下载 Docker 镜像，使用下面的 `docker pull` 命令下载。下载完成后，部署脚本会复用本地镜像。

### 4.1 PostgreSQL

```powershell
docker pull postgres:16-alpine
```

下载地址：[Docker Hub PostgreSQL 16 Alpine](https://hub.docker.com/_/postgres/tags?name=16-alpine)

### 4.2 Redis

```powershell
docker pull redis:7-alpine
```

下载地址：[Docker Hub Redis 7 Alpine](https://hub.docker.com/_/redis/tags?name=7-alpine)

### 4.3 Prometheus

```powershell
docker pull prom/prometheus:v2.55.1
```

下载地址：[Prometheus Docker Hub 标签](https://hub.docker.com/r/prom/prometheus/tags)

### 4.4 Grafana

```powershell
docker pull grafana/grafana:11.3.0
```

下载地址：[Grafana Docker Hub 标签](https://hub.docker.com/r/grafana/grafana/tags)

### 4.5 LiteLLM Runtime Core

Compose 当前使用 GHCR 官方镜像：

```powershell
docker pull ghcr.io/berriai/litellm:v1.91.0
```

下载地址：[LiteLLM GitHub Container Registry](https://github.com/BerriAI/litellm/pkgs/container/litellm)

如果 GHCR 无法访问，可尝试官方 Docker Hub 仓库中的同版本标签：

```powershell
docker pull litellm/litellm:v1.91.0
docker tag litellm/litellm:v1.91.0 ghcr.io/berriai/litellm:v1.91.0
```

下载地址：[LiteLLM Docker Hub 标签](https://hub.docker.com/r/litellm/litellm/tags)

如果 Docker Hub 中不存在完全相同的 `v1.91.0` 标签，不要直接替换为其他版本；应先确认版本兼容性，再修改 Compose 配置并记录版本变更。


### 4.6 TokenSea 自有镜像

以下镜像由项目源码构建，不需要手动从公网下载：

```text
tokensea-tokensea-console
tokensea-tokensea-control-plane
tokensea-tokensea-gateway-runtime
tokensea-tokensea-egress-proxy
```
### 4.7 备份到普通文件：

```text
docker save -o D:\12_其他项目\30_APIGateway\tokensea-images.tar `
  postgres:16-alpine redis:7-alpine prom/prometheus:v2.55.1 grafana/grafana:11.3.0 ghcr.io/berriai/litellm:v1.91.0
```

### 4.8 恢复：

```text
docker load -i D:\12_其他项目\30_APIGateway\tokensea-images.tar
```

## 5. 部署完成检查

### 5.1 容器状态

```powershell
cd D:\12_其他项目\30_APIGateway\tokensea\deploy\compose
docker compose -p tokensea --env-file ./.env ps
```

所有服务应为 `Up`；PostgreSQL、Redis、Egress Proxy 应显示 `healthy`。

### 5.2 HTTP 健康检查

```powershell
(Invoke-WebRequest http://localhost:39210 -UseBasicParsing).StatusCode
(Invoke-WebRequest http://localhost:39211/actuator/health -UseBasicParsing).StatusCode
(Invoke-WebRequest http://localhost:39212/health -UseBasicParsing).StatusCode
(Invoke-WebRequest http://localhost:39218/health/liveliness -UseBasicParsing).StatusCode
```

四个接口均应返回 `200`。

### 5.3 前端 API 地址检查

```powershell
$consoleId = docker compose -p tokensea ps -q tokensea-console
docker exec $consoleId sh -c "grep -R 'localhost:39211' /usr/share/nginx/html/assets/*.js >/dev/null && echo control-plane-ok"
docker exec $consoleId sh -c "grep -R 'localhost:39212' /usr/share/nginx/html/assets/*.js >/dev/null && echo gateway-ok"
```

## 6. 常见故障处理

| 现象 | 原因 | 处理 |
|---|---|---|
| `connectex ... 127.0.0.1:7890` | Docker Desktop 代理端口错误 | 改为实际监听端口，重启 Docker Desktop |
| Maven `Remote host terminated the handshake` | Maven Central TLS 或网络链路不稳定 | 使用项目 Control Plane Dockerfile 中配置的 Maven 公共镜像 |
| Docker Hub 返回 `EOF` | Registry 访问不稳定 | 配置代理，或先手动 `docker pull` 大镜像 |
| `accounting_outbox does not exist` | Gateway 早于 Control Plane 数据库迁移启动 | 等待 Control Plane 健康后重建 Gateway Runtime |
| 端口冲突 | 旧容器或旧进程占用端口 | 先确认进程/容器属于 TokenSea，再处理；不要盲目终止未知进程 |

## 7. 数据安全要求

- 不执行 `docker compose down -v`，避免删除数据库和 Redis 数据卷。
- 不把 `.env`、供应商 Key、Virtual Key、数据库密码或代理认证信息写入文档或 Git。
- 仅在明确接受数据清空时使用 `-ResetVolumes`。
