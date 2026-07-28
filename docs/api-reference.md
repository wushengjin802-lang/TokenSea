# TokenSea API Reference

## Control Plane

以下管理列表已统一支持 `page`、`size`、`keyword`、`status`、`sort`、`order` 参数，并在 `ApiResponse.data` 中返回：

```json
{
  "items": [],
  "total": 0,
  "page": 1,
  "size": 20
}
```

`page` 从 1 开始，默认每页 20 条，单次最多 500 条。当前统一分页范围包括租户、Virtual Key、账户、角色、操作审计和价格差异审核。

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
- `GET/POST/PUT/DELETE /api/keys`：Key 申请；GET 返回统一分页结构。
- `POST /api/keys/{id}/approve`：审批通过。
- `POST /api/keys/{id}/generate`：生成 Key 明文，仅返回一次。
- `POST /api/keys/{id}/disable`：禁用 Key。
- `GET /api/runtime/config.yaml`：导出内部运行时配置。
- `GET /api/usage`：用量记录。
- `GET /api/billing`：账单记录。
- `GET /api/users`：账户列表，返回统一分页结构。
- `GET /api/roles`：角色列表，返回统一分页结构。
- `GET /api/provider-price-diffs`：价格差异审核列表，返回统一分页结构。
- `GET/POST/PATCH /api/provider-price-sources`：多源价格目录与官方文档价格源；适配器包括 LiteLLM、models.dev、Azure、AWS、Google、通用文档及现有供应商专用解析器。
- `POST /api/provider-price-sources/{id}/test`：测试获取价格来源。
- `POST /api/provider-price-sources/{id}/test-parse`：测试解析并返回诊断证据。
- `POST /api/provider-price-sources/{id}/sync`：立即同步价格目录。
- `GET/POST/PATCH /api/provider-billing-sources`：供应商 Costs/账单 API 来源，列表返回统一分页结构。
- `POST /api/provider-billing-sources/{id}/test`：测试获取并预览标准化账单记录，可传 `from`、`to`。
- `POST /api/provider-billing-sources/{id}/sync`：同步账单证据并生成或刷新供应商对账，可传 `from`、`to`。
- `POST /api/provider-billing-sources/{id}/enable`：启用账单源定时同步。
- `POST /api/provider-billing-sources/{id}/pause`：暂停账单源定时同步。
- `GET /api/provider-billing-sync-runs`：账单同步任务，返回统一分页结构。
- `GET /api/provider-billing-records`：供应商账单证据明细，返回统一分页结构。
- `GET /api/audit`：审计日志，返回统一分页结构。

## Gateway Runtime

- `POST /v1/chat/completions`
- `POST /v1/embeddings`
- `POST /v1/responses`
- `GET /metrics`
- `GET /health`
