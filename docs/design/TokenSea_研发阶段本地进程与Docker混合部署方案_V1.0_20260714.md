# TokenSea 研发阶段本地进程与 Docker 混合部署方案

版本：V1.0
日期：2026-07-14
适用范围：TokenSea 日常研发、联调、功能验证、预发布与生产交付

---

## 1. 方案结论

TokenSea 在研发阶段不建议所有业务服务都通过 Docker 镜像运行。

推荐采用：

```text
研发环境：基础设施 Docker + 业务服务本地进程
测试/预发布：完整 Docker Compose
生产环境：固定版本 Docker 镜像
```

研发阶段只把 PostgreSQL、Redis、LiteLLM Runtime Core、出口代理等基础设施保留在 Docker 中；Console、Control Plane 和 Gateway Runtime 直接在 Windows 本地启动。

这样修改 Vue、Java 或 Python 代码后，无需每次重新构建业务镜像，也不会重复下载 Maven、npm 和 Python 依赖。

但不能等到生产环境才第一次验证 Docker。功能合并、版本发布和预发布阶段仍需执行完整镜像构建及 Compose 验证。

---

## 2. 推荐研发拓扑

```text
浏览器
  │
  ├─ Console 本地 Vite             :39210
  ├─ Control Plane 本地 Spring Boot :39211
  └─ Gateway Runtime 本地 Uvicorn   :39212
                 │
                 ▼
      Docker PostgreSQL             :39213
      Docker Redis                  :39214
      Docker Egress Proxy           :18080
      Docker LiteLLM Runtime Core   :39218
      Docker Prometheus/Grafana     按需启动
```

### 2.1 保留在 Docker 中的服务

| 服务 | 默认端口 | 原因 |
|---|---:|---|
| PostgreSQL | 39213 | 保持数据库版本、数据卷和 Flyway 环境一致 |
| Redis | 39214 | 避免本地安装，保持密码和持久化配置一致 |
| LiteLLM Runtime Core | 39218 | 依赖较多，容器运行最稳定 |
| Egress Proxy | 18080 | 保持供应商出口安全和动态白名单链路一致 |
| Prometheus | 39215 | 开发阶段按需启动 |
| Grafana | 39216 | 开发阶段按需启动 |

### 2.2 本地直接运行的服务

| 服务 | 本地方式 | 开发体验 |
|---|---|---|
| Console | Vite Dev Server | Vue/TypeScript 自动热更新 |
| Control Plane | Maven Spring Boot | Java 修改后重启，或后续引入 DevTools |
| Gateway Runtime | Uvicorn `--reload` | Python 文件修改后自动重启 |

---

## 3. 为什么依赖不会每次重新下载

首次准备后，依赖会保存在本机缓存和虚拟环境中：

```text
Maven 依赖   → C:\Users\<用户>\.m2\repository
npm 依赖     → 项目 node_modules + npm 本地缓存
Python 依赖  → services/gateway-runtime/.venv
```

日常修改源代码时，不会重新下载依赖。

只有发生以下变化时才需要补充依赖：

| 文件变化 | 动作 |
|---|---|
| `pom.xml` | Maven 下载新增或变更依赖 |
| `package.json` / lock 文件 | 执行 `npm install` |
| `requirements.txt` | 在 `.venv` 中执行 `pip install -r requirements.txt` |
| Dockerfile 或系统级依赖 | 重建对应镜像 |

---

## 4. 开发覆盖 Compose 文件

已新增：

```text
deploy/compose/docker-compose.dev.yml
```

推荐内容：

```yaml
services:
  tokensea-egress-proxy:
    ports:
      - "127.0.0.1:18080:18080"
    environment:
      TOKENSEA_EGRESS_POLICY_URL: http://host.docker.internal:39211/internal/egress/allowed-hosts
```

作用：

```text
本地 Control Plane
→ localhost:18080
→ Docker Egress Proxy

Docker Egress Proxy
→ host.docker.internal:39211
→ 本地 Control Plane 动态出口策略接口
```

Windows Docker Desktop 支持通过 `host.docker.internal` 访问宿主机。

---

## 5. 启动 Docker 基础设施

进入 Compose 目录：

```powershell
cd D:\12_其他项目\30_APIGateway\tokensea\deploy\compose
```

只启动研发需要的基础设施：

切换到研发模式前，必须先停止 Docker 中的 `tokensea-console`、`tokensea-control-plane` 和 `tokensea-gateway-runtime`，否则它们会继续占用 `39210`、`39211`、`39212` 端口。项目已提供下列脚本，它会移除这三个业务容器并启动基础设施，不会删除数据卷：

```powershell
& .\scripts\dev\start-infra.ps1
```

```powershell
docker compose `
  -p tokensea `
  --env-file ./.env `
  -f docker-compose.yml `
  -f docker-compose.dev.yml `
  up -d `
  tokensea-postgres `
  tokensea-redis `
  tokensea-egress-proxy `
  tokensea-runtime-core
```

监控组件按需启动：

```powershell
docker compose `
  -p tokensea `
  --env-file ./.env `
  -f docker-compose.yml `
  -f docker-compose.dev.yml `
  up -d `
  tokensea-prometheus `
  tokensea-grafana
```

查看状态：

```powershell
docker compose `
  -p tokensea `
  --env-file ./.env `
  -f docker-compose.yml `
  -f docker-compose.dev.yml `
  ps
```

研发阶段不要执行：

```powershell
docker compose down -v
```

`-v` 会删除 PostgreSQL、Redis、Grafana 和 Gateway Outbox 数据卷。

---

## 6. 本地运行 Control Plane

### 6.1 环境要求

```text
Java：21
Maven：与项目兼容的 3.9.x
```

进入目录：

```powershell
cd D:\12_其他项目\30_APIGateway\tokensea\services\control-plane
```

配置 Java 21：

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"

java -version
mvn -version
```

### 6.2 配置环境变量

以下密码和密钥必须与 `deploy\compose\.env` 一致，不要写入代码仓库。

```powershell
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:39213/tokensea"
$env:SPRING_DATASOURCE_USERNAME="tokensea"
$env:SPRING_DATASOURCE_PASSWORD="<数据库密码>"

$env:SPRING_DATA_REDIS_HOST="localhost"
$env:SPRING_DATA_REDIS_PORT="39214"
$env:SPRING_DATA_REDIS_PASSWORD="<Redis密码>"

$env:TOKENSEA_JWT_SECRET="<JWT密钥>"
$env:TOKENSEA_CRYPTO_KEY="<加密密钥>"
$env:TOKENSEA_RUNTIME_ENGINE_KEY="<LiteLLM主密钥>"
$env:TOKENSEA_RUNTIME_ENGINE_URL="http://localhost:39218"

$env:TOKENSEA_EGRESS_PROXY_HOST="localhost"
$env:TOKENSEA_EGRESS_PROXY_PORT="18080"
$env:TOKENSEA_ALLOWED_EGRESS_HOSTS="<全局出口基线域名>"
$env:TOKENSEA_EGRESS_ALLOWED_PORTS="80,443"
$env:TOKENSEA_EGRESS_POLICY_TOKEN="<与Compose一致的出口策略Token>"

$env:TOKENSEA_CORS_ALLOWED_ORIGINS="http://localhost:39210"
$env:TOKENSEA_BOOTSTRAP_TOKEN="<引导令牌，按需>"
$env:TOKENSEA_BUDGET_CURRENCY="CNY"
```

### 6.3 启动

```powershell
& .\scripts\dev\start-control-plane.ps1
```

脚本会使用 JDK 21 打包后运行可执行 JAR，避免 Windows 下中文工作目录触发 Maven forked JVM 类路径编码问题。

访问健康检查：

```text
http://localhost:39211/actuator/health
```

Control Plane 启动时会连接 Docker PostgreSQL，并自动执行尚未执行的 Flyway 迁移。

必须遵循：

```text
已经执行过的 V1～V14 不修改
新增数据库变化使用 V15、V16……
```

### 6.4 Java 热更新建议

当前未依赖 Spring Boot DevTools。Java 代码改变后通常需要停止并重新启动：

```powershell
Ctrl+C
& .\scripts\dev\start-control-plane.ps1
```

后续可仅在开发环境增加 `spring-boot-devtools`，提升类路径变化后的自动重启体验，但生产构建不得启用开发工具。

---

## 7. 本地运行 Console

### 7.1 环境要求

```text
Node.js：>=20
npm：与 Node.js 配套版本
```

首次安装：

```powershell
cd D:\12_其他项目\30_APIGateway\tokensea
npm install
```

### 7.2 启动开发服务器

```powershell
$env:VITE_API_BASE="http://localhost:39211"
$env:VITE_GATEWAY_BASE="http://localhost:39212"

npm run console:dev
```

访问：

```text
http://localhost:39210
```

Vue、TypeScript 和 CSS 文件改变后，Vite 自动热更新，不需要重建 Console 镜像。

生产构建仍需验证：

```powershell
npm run console:build
```

---

## 8. 本地运行 Gateway Runtime

### 8.1 创建 Python 虚拟环境

首次执行：

```powershell
cd D:\12_其他项目\30_APIGateway\tokensea\services\gateway-runtime

python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install -r requirements.txt
```

后续只需要：

```powershell
cd D:\12_其他项目\30_APIGateway\tokensea\services\gateway-runtime
.\.venv\Scripts\Activate.ps1
```

### 8.2 配置环境变量

```powershell
$env:TOKENSEA_CONTROL_BASE="http://localhost:39211"

$env:TOKENSEA_DB_HOST="localhost"
$env:TOKENSEA_DB_PORT="39213"
$env:TOKENSEA_DB_NAME="tokensea"
$env:TOKENSEA_DB_USER="tokensea"
$env:TOKENSEA_DB_PASSWORD="<数据库密码>"

$env:TOKENSEA_REDIS_URL="redis://:<Redis密码>@localhost:39214/0"
$env:TOKENSEA_REDIS_PASSWORD="<Redis密码>"

$env:TOKENSEA_RUNTIME_ENGINE_URL="http://localhost:39218"
$env:TOKENSEA_RUNTIME_CORE_BASE="http://localhost:39218"
$env:TOKENSEA_RUNTIME_ENGINE_KEY="<LiteLLM主密钥>"
$env:TOKENSEA_CRYPTO_KEY="<加密密钥>"

$env:TOKENSEA_OUTBOX_DIR="$PWD\runtime-data\outbox"
```

### 8.3 启动

```powershell
uvicorn app.main:app `
  --host 0.0.0.0 `
  --port 39212 `
  --reload
```

访问健康检查：

```text
http://localhost:39212/health
```

Python 文件改变后，Uvicorn 自动重启。

---

## 9. 推荐日常研发流程

每天启动顺序：

```text
1. 启动 Docker Desktop
2. 启动 PostgreSQL、Redis、Egress Proxy、LiteLLM
3. 启动本地 Control Plane
4. 启动本地 Gateway Runtime
5. 启动本地 Vite Console
```

修改代码后的动作：

| 改动 | 操作 |
|---|---|
| Vue/TypeScript/CSS | 自动热更新 |
| Python | Uvicorn 自动重启 |
| Java | 重启 `mvn spring-boot:run`，或后续配置 DevTools |
| 新 Flyway 迁移 | 重启 Control Plane |
| `package.json` | `npm install` |
| `requirements.txt` | 虚拟环境执行 `pip install -r requirements.txt` |
| `pom.xml` | Maven 自动解析新增依赖 |
| Dockerfile/OS 依赖 | 重建对应镜像 |
| LiteLLM 镜像版本 | 拉取或重建 Runtime Core |
| Egress Proxy 源码 | 当前方案仍需重建代理镜像，或后续也改为本地进程 |

---

## 10. 本地开发与 Docker 网络注意事项

### 10.1 容器访问宿主机

Docker Egress Proxy 访问本地 Control Plane 使用：

```text
host.docker.internal:39211
```

### 10.2 宿主机访问容器

本地业务服务访问容器使用已发布到 `127.0.0.1` 的端口：

```text
PostgreSQL：localhost:39213
Redis：localhost:39214
Egress Proxy：localhost:18080
LiteLLM：localhost:39218
```

### 10.3 CORS

Control Plane 需要允许：

```text
http://localhost:39210
```

### 10.4 动态出口策略

Docker Egress Proxy 获取动态价格源域名时，必须能访问本地 Control Plane 的内部策略接口，并且使用相同的：

```text
TOKENSEA_EGRESS_POLICY_TOKEN
```

---

## 11. 建议增加开发启动脚本

为降低每次手工设置环境变量的成本，项目已提供不含敏感值的脚本：

```text
scripts/dev/start-infra.ps1
scripts/dev/start-control-plane.ps1
scripts/dev/start-gateway.ps1
scripts/dev/start-console.ps1
scripts/dev/stop-infra.ps1
```

本地启动顺序：

```powershell
# 在四个终端分别运行。脚本只从 deploy/compose/.env 读取本机密钥，不输出密钥。
& .\scripts\dev\start-infra.ps1
& .\scripts\dev\start-control-plane.ps1
& .\scripts\dev\start-gateway.ps1
& .\scripts\dev\start-console.ps1
```

如需为个人环境追加变量，推荐方式：

```text
仓库提交不含密钥的 .example.ps1
开发人员复制为本地 .local.ps1
.local.ps1 加入 .gitignore
```

不建议把数据库密码、JWT 密钥、加密密钥或供应商 Key 写进可提交的脚本。

---

## 12. 测试和预发布策略

研发阶段本地运行不代表可以放弃容器验证。

推荐节奏：

```text
日常开发
→ 本地业务进程 + Docker 基础设施

提交前
→ 单元测试、类型检查、构建

每日或合并后
→ CI 构建完整 Docker 镜像

版本发布前
→ 完整 Docker Compose 预发布验证

生产环境
→ 固定版本镜像部署
```

### 12.1 提交前最低验证

Control Plane：

```powershell
mvn test
mvn -DskipTests package
```

Console：

```powershell
npm run console:build
```

Gateway：

```powershell
pytest -q
```

Egress Proxy：

```powershell
pytest -q
```

Compose：

```powershell
docker compose -p tokensea --env-file ./.env config
```

### 12.2 发布前完整验证

```powershell
docker compose -p tokensea --env-file ./.env build

docker compose -p tokensea --env-file ./.env up -d --force-recreate

docker compose -p tokensea --env-file ./.env ps
```

重点验证：

- Flyway 迁移是否执行；
- Console 是否使用正确 API 地址；
- 容器内部服务名是否正确；
- Egress Proxy 是否能加载动态策略；
- LiteLLM 是否能通过代理访问供应商；
- 只读文件系统、数据卷和 Outbox 是否正常；
- 健康检查是否通过；
- 价格同步、模型发现、Virtual Key 和 Gateway 调用是否形成闭环。

---

## 13. 开发模式与生产模式对照

| 项目 | 研发模式 | 生产模式 |
|---|---|---|
| Console | Vite Dev Server | Nginx/静态文件镜像 |
| Control Plane | Maven 本地进程 | Java 镜像 |
| Gateway | Uvicorn `--reload` | Uvicorn/Gunicorn 容器进程 |
| PostgreSQL | Docker | Docker、Kubernetes 或托管数据库 |
| Redis | Docker | Docker、Kubernetes 或托管 Redis |
| LiteLLM | Docker | 固定版本镜像 |
| Egress Proxy | Docker | 固定版本安全镜像 |
| 密钥 | 本机环境变量 | Secret Manager/K8s Secret |
| 日志 | 终端输出 | 集中日志系统 |
| 镜像版本 | 不作为日常运行入口 | 固定 Tag 或镜像摘要 |

---

## 14. 风险与控制措施

### 风险一：本地可运行，容器不可运行

原因可能包括 Dockerfile 漏文件、环境变量缺失、容器服务名错误和只读文件系统不兼容。

控制：每日 CI 构建镜像，发布前完整 Compose 验证。

### 风险二：开发配置与生产配置偏离

控制：本地环境变量名称与 Compose 保持一致，仅改变主机地址和端口。

### 风险三：本地脚本泄露密钥

控制：敏感脚本使用 `.local.ps1` 并加入 `.gitignore`；仓库只保存模板。

### 风险四：数据库迁移误修改

控制：已执行迁移永不修改，所有变化新增前向 Flyway 版本。

### 风险五：本地进程绕过出口代理

控制：Control Plane 的供应商访问仍配置 `TOKENSEA_EGRESS_PROXY_HOST=localhost` 和端口 `18080`；LiteLLM 继续通过 Docker Egress Proxy 出口。

---

## 15. 最终推荐

```text
研发环境
PostgreSQL + Redis + LiteLLM + Egress Proxy 使用 Docker
Console + Control Plane + Gateway 使用本地进程

预发布环境
完整 Docker Compose

生产环境
经过 CI 和预发布验证的固定版本 Docker 镜像
```

该方式能够显著减少重复构建镜像和重复下载依赖的时间，同时保留数据库、Redis、LiteLLM 和出口安全链路的一致性。
