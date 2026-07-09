# TokenSea 企业级统一 LLM API Gateway

TokenSea 是面向企业内部与私有化交付场景的统一 LLM API Gateway 与计费运营平台。系统采用控制面与数据面分离架构：控制面负责租户、项目、应用、Key、模型、价格、用量、计费、审计和运维；数据面负责统一 API、鉴权、预算校验、模型路由、Fallback、调用转发与用量回传。

## 技术栈

- 前端：Vue 3 + TypeScript + Vite + Ant Design Vue
- 控制面：Java 21 + Spring Boot 3 + Spring Security + MyBatis-Plus + Flyway
- 数据面：TokenSea Gateway Runtime
- 数据库：PostgreSQL 16
- 缓存与限流：Redis 7
- 监控：Prometheus + Grafana
- 部署：Docker Compose，预留 Helm / Kubernetes

## 已实现功能

- 登录与首个管理员初始化
- 租户、项目、应用管理
- 供应商、模型、价格管理
- Key 申请、审批、生成、禁用和范围配置
- 用量记录、账单记录、预算入口
- 路由策略、调用日志、操作审计、指标监控
- OpenAI-compatible 网关入口
- 国内模型供应商模板说明
- 第三方组件许可证、SBOM 与供应链治理目录

## 数据原则

系统不会内置业务预置数据。首次部署后需要管理员初始化账号，然后录入真实租户、供应商、模型、价格、路由和 Key。

## 端口

| 服务 | 端口 |
|---|---:|
| Console | 39210 |
| Control Plane | 39211 |
| Gateway Runtime | 39212 |
| PostgreSQL | 39213 |
| Redis | 39214 |
| Prometheus | 39215 |
| Grafana | 39216 |
| Runtime Core | 39218 |

## 启动

```bash
cd deploy/compose
docker compose --env-file ../../.env.example up -d --build
```

访问控制台：

```text
http://localhost:39210
```

## 合规说明

TokenSea 在产品代码、服务命名和业务接口中使用自有命名体系。第三方开源组件的许可证、版权声明、SBOM、版本锁定和漏洞响应材料保存在 `NOTICE`、`third_party/notices`、`compliance` 与 `security` 目录中。生产交付时不得删除这些合规材料。
