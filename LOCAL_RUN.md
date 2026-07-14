# TokenSea 本地运行说明

当前代码使用 `392xx` 本地端口范围。

## 访问地址

- 控制台：http://localhost:39210
- 控制面 API：http://localhost:39211
- 网关运行时：http://localhost:39212
- PostgreSQL：`localhost:39213`
- Redis：`localhost:39214`
- Prometheus：http://localhost:39215
- Grafana：http://localhost:39216
- Runtime Core：http://localhost:39218

## 管理员账号

- 用户名：`admin`
- 密码：`TokenSea@local2026`

## 启动命令

### 使用本地启动技能脚本

在 PowerShell 中，从 APIGateway 工作区执行：

```powershell
cd "D:\12_其他项目\30_APIGateway"
& "C:\Users\Administrator\.codex\skills\tokensea-local\scripts\start-tokensea.ps1"
```

脚本会自动完成以下操作：

- Docker Desktop 未启动时自动尝试启动
- 应用当前 `.env` 配置
- 使用 `tokensea` Compose 项目重新创建容器
- 检查控制台、控制面、网关和 Runtime Core 健康状态

前端代码、端口或 Vite API 配置变更后，重新构建控制台：

```powershell
& "C:\Users\Administrator\.codex\skills\tokensea-local\scripts\start-tokensea.ps1" -RebuildConsole
```

Java/Python 服务代码或 Dockerfile 变更后，重新构建全部镜像：

```powershell
& "C:\Users\Administrator\.codex\skills\tokensea-local\scripts\start-tokensea.ps1" -RebuildAll
```

只有在明确需要清空数据库时，才使用以下命令：

```powershell
& "C:\Users\Administrator\.codex\skills\tokensea-local\scripts\start-tokensea.ps1" -ResetVolumes
```

`-ResetVolumes` 会删除本地 Docker 数据卷，并清除现有 TokenSea 数据库数据，请谨慎使用。

### 直接使用 Docker Compose

```powershell
cd "D:\12_其他项目\30_APIGateway\tokensea\deploy\compose"
Copy-Item .env.example .env
# 编辑 .env，替换所有 REPLACE_* 值
docker compose -p tokensea --env-file ./.env up -d --build
docker compose -p tokensea --env-file ./.env ps
docker compose -p tokensea --env-file ./.env logs -f --tail=200
docker compose -p tokensea --env-file ./.env config --quiet
docker compose -p tokensea --env-file ./.env down
```

### 开发热更新前端
```powershell
cd "D:\12_其他项目\30_APIGateway\tokensea\deploy\compose"
docker compose -p tokensea --env-file ./.env stop tokensea-console
cd "D:\12_其他项目\30_APIGateway\tokensea\apps\console"
npm install   # 仅首次需要
npm run dev
然后访问 http://localhost:39210/。之后修改 Vue、CSS、路由等前端文件会自动热更新，通常秒级生效。
```


## 注意事项

- 控制台 API 地址和网关地址会在 Vite 构建时写入前端静态资源。
- 修改端口或前端环境变量后，需要重新构建 `tokensea-console`。
- 使用 Docker Compose 时必须带上 `-p tokensea --env-file ./.env`，避免操作到错误的 Compose 项目或环境文件。
- 本地 `tokensea-local` Codex 技能已适配当前代码版本。
