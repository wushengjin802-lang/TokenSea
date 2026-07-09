# TokenSea API Reference

## Control Plane

- `POST /api/bootstrap/admin`：首次初始化管理员，仅在用户表为空时可用。
- `POST /api/auth/login`：登录，返回 JWT。
- `GET/POST/PUT/DELETE /api/tenants`：租户。
- `GET/POST/PUT/DELETE /api/projects`：项目。
- `GET/POST/PUT/DELETE /api/apps`：应用。
- `GET/POST/PUT/DELETE /api/providers`：供应商。
- `POST /api/provider-secrets`：加密保存供应商密钥。
- `GET/POST/PUT/DELETE /api/models`：模型资产。
- `GET/POST/PUT/DELETE /api/model-deployments`：模型部署。
- `GET/POST/PUT/DELETE /api/model-prices`：价格版本。
- `GET/POST/PUT/DELETE /api/keys`：Key 申请。
- `POST /api/keys/{id}/approve`：审批通过。
- `POST /api/keys/{id}/generate`：生成 Key 明文，仅返回一次。
- `POST /api/keys/{id}/disable`：禁用 Key。
- `GET /api/runtime/config.yaml`：导出内部运行时配置。
- `GET /api/usage`：用量记录。
- `GET /api/billing`：账单记录。
- `GET /api/audit`：审计日志。

## Gateway Runtime

- `POST /v1/chat/completions`
- `POST /v1/embeddings`
- `POST /v1/responses`
- `GET /metrics`
- `GET /health`
