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
- `GET /api/provider-secrets?providerInstanceId={id}&purpose={INFERENCE|PRICING_READ|BILLING_READ}`：按供应商渠道与用途查询已托管密钥元数据，不返回密文或明文。
- `POST /api/provider-secrets`：加密保存供应商密钥；`secretPurpose` 区分推理、价格只读和账单只读用途。
- `GET/POST/PUT/DELETE /api/models`：模型资产。
- `GET/POST/PUT/DELETE /api/model-deployments`：模型部署。
- `GET /api/model-deployment-governance`：查询渠道真实模型部署、能力探测、生产准入及自动参考价匹配状态；公共参考价缺失不阻止生产审核或调用。
- `GET /api/model-deployment-governance/{id}/reference-price`：查询模型部署当前精确匹配的公共参考价；无匹配时返回 `MISSING_REFERENCE` 和非阻断提示。
- `POST /api/model-deployment-governance/{id}/production-transition`：平台管理员确认、拒绝或暂停生产准入；准入依据为渠道启用、真实探测通过和管理员确认，不要求正式价格版本。
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
- `GET /api/reference-prices/overview`：公共参考价格自动维护总览，返回覆盖率、最近成功时间、过期数量、系统来源数量和离线快照使用状态。
- `GET /api/reference-prices/sources`：查询系统内置参考价格源及每日同步状态；系统来源只读。
- `GET /api/reference-prices/models`：分页查询当前生效的公共参考价格。系统按未过期、来源优先级、置信度和观测时间自动选择一条当前参考价。
- `GET /api/model-deployment-governance`：查询模型部署及其自动参考价绑定。参考价状态区分 `OFFICIAL_REFERENCE`、`VENDOR_REFERENCE`、`AGGREGATOR_REFERENCE`、`BUNDLED_REFERENCE` 和 `MISSING_REFERENCE`；返回真实价格来源渠道、模型原始厂商、匹配类型、匹配置信度、匹配依据及原币种价格。
- `GET /api/model-deployment-governance/{id}/reference-price`：查询指定部署当前选中的参考价绑定。系统仅进行完整模型名精确匹配；官方/厂商精确价优先于聚合渠道精确价，聚合渠道价格不会被改写为模型厂商官方价。
- `POST /api/reference-prices/sources/{id}/retry`：对在线系统参考源执行一次后台重试；离线快照源不支持联网重试。
- `GET /api/reference-prices/sources/{id}/runs`：查询系统参考源最近 100 次自动同步记录。
- `GET /api/provider-price-diffs`：正式供应商价格差异审核列表，返回统一分页结构；公共参考价不进入该流程。
- `GET/POST/PATCH /api/provider-price-sources`：高级运维价格源接口。内置系统参考源由平台自动维护，不允许通过旧 CRUD 编辑、启用或暂停；用户自定义正式价格源仍兼容原有接口。
- `POST /api/provider-price-sources/{id}/test`：测试获取价格来源。
- `POST /api/provider-price-sources/{id}/test-parse`：测试解析并返回诊断证据。
- `POST /api/provider-price-sources/{id}/sync`：立即同步价格目录。通用文档先写入抽取运行、记录级证据和校验结果；仅已自动接受的确定性记录进入价格差异。
- `GET /api/provider-price-connectors`：查询价格连接器元数据与安全默认值。
- `GET /api/provider-price-connectors/provider-options`：查询供应商价格源推荐配置，包括推荐名称、采集适配器、官方来源地址、官方域名、区域、币种和提取策略。
- `GET /api/provider-price-connectors/{code}/schema`：查询指定连接器的配置 Schema。
- `GET/POST/PATCH/DELETE /api/provider-price-mappings`：维护 Azure、AWS、Google 等云目录 SKU 到 TokenSea 模型及价格组件的映射规则。
- `GET /api/provider-price-unmapped-records`：查询未映射目录记录；原始 SKU 证据不会静默丢弃。
- `POST /api/provider-price-unmapped-records/{id}/ignore`：人工标记非价格或无需映射的目录记录。
- `GET /api/price-document-extraction-runs`：价格文档抽取运行列表，返回统一分页结构。
- `GET /api/price-document-extraction-runs/{id}`：查询抽取诊断、原始快照与记录级证据。
- `GET /api/price-document-extracted-records`：查询标准化候选记录，支持按运行和审核状态筛选。
- `POST /api/price-document-extracted-records/{id}/review`：管理员执行 `ACCEPTED`、`CORRECTED`、`REJECTED` 或 `NON_PRICE` 审核；修正记录会重新校验。
- `POST /api/price-document-extraction-runs/{id}/submit`：全部待审核记录处理完成后，将接受/修正记录提交到现有价格差异流程。
- `GET/POST/PATCH /api/provider-billing-sources`：供应商 Costs/账单 API 来源，列表返回统一分页结构。
- `POST /api/provider-billing-sources/{id}/test`：测试获取并预览标准化账单记录，可传 `from`、`to`。
- `POST /api/provider-billing-sources/{id}/sync`：同步账单证据并生成或刷新供应商对账，可传 `from`、`to`。
- `POST /api/provider-billing-sources/{id}/enable`：启用账单源定时同步。
- `POST /api/provider-billing-sources/{id}/pause`：暂停账单源定时同步。
- `GET /api/provider-billing-sync-runs`：账单同步任务，返回统一分页结构。
- `GET /api/provider-billing-records`：供应商账单证据明细，返回统一分页结构。
- `GET /api/provider-billing-snapshots`：查询供应商账单 API 原始响应快照。
- `GET /api/audit`：审计日志，返回统一分页结构。

## Gateway Runtime

- `POST /v1/chat/completions`
- `POST /v1/embeddings`
- `POST /v1/responses`
- `GET /metrics`
- `GET /health`
