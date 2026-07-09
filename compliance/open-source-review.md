# TokenSea 开源合规评审

## 结论

本方案采用 TokenSea 自研控制面 + TokenSea Gateway Runtime 封装第三方开源 AI Gateway 数据面能力。产品代码和业务命名统一 TokenSea 化，但许可证、NOTICE、SBOM、客户尽调资料必须保留第三方信息。

## 强制要求

1. 不删除第三方 LICENSE / NOTICE / Copyright。
2. 不复制或集成第三方 Enterprise-only 目录代码。
3. 不使用第三方 Enterprise-only 功能，除非采购授权。
4. 不使用 `latest` 镜像；必须固定版本或 digest。
5. 生成 SBOM，并随私有化交付包提供。
6. 供应商 Key 加密存储，日志默认不落完整 Prompt/Response。
7. 私有化部署包必须包含第三方组件声明。

## 交付资料

- `NOTICE`
- `third_party/notices/*`
- `compliance/third-party-notices.md`
- `compliance/sbom/`
- `security/vulnerability-response.md`
