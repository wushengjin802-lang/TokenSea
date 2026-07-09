# TokenSea 部署说明

## Docker Compose

```bash
cd deploy/compose
cp ../../.env.example .env
# 编辑 .env

docker compose up -d --build
```

## 初始化

```bash
curl -X POST http://localhost:39211/api/bootstrap/admin \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"强密码","displayName":"系统管理员"}'
```

## 配置流程

1. 登录控制台。
2. 创建租户。
3. 创建项目与应用。
4. 创建供应商。
5. 保存供应商密钥。
6. 创建模型资产。
7. 创建模型部署。
8. 创建价格版本。
9. 创建 Key 申请，审批，生成 Key。
10. 使用 OpenAI SDK 只替换 base_url 和 api_key 调用。

## 注意

默认不内置示例业务数据。内部运行时底座的模型配置需要通过 `/api/runtime/config.yaml` 导出并挂载，生产建议由运维脚本自动化处理并滚动重启运行时底座。
