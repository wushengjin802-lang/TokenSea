# TokenSea 供应商与模型页面修改记录

日期：2026-07-09

## 1. 供应商模板启用状态

### 修改前

- 供应商模板列表中状态使用“可启用 / 可配置”等表达。
- 该状态表达的是按钮能力，不是业务当前状态。
- 用户无法判断模板是否已经创建过供应商实例。

### 修改后

- 供应商模板状态统一为：
  - 未启用：尚未创建供应商实例。
  - 已启用：已基于模板创建至少一个供应商实例。
  - 已启用 N 个实例：同一模板下存在多个启用中的实例。
  - 已停用：模板不可启用。
- 新增数据库迁移 `V3__provider_template_enable_status.sql`，将旧状态归一化。
- 后端供应商模板列表会根据 `provider_instance` 实际数量补充启用状态。

## 2. 供应商模板“启用”按钮

### 修改前

- 已启用的供应商模板再次点击“启用”，会重复创建供应商实例。
- 重复实例会导致供应商实例列表、路由策略、健康检查和计费归因混乱。

### 修改后

- 未启用模板：显示“启用”，点击后创建一个供应商实例。
- 已启用模板：按钮显示“已启用”，并置灰，不允许重复点击。
- 已停用模板：按钮显示“已停用”，并置灰。
- 后端 `/api/provider-templates/{id}/enable` 增加幂等判断：如果该模板已存在启用中的实例，则直接返回已有实例，不再重复插入。

## 3. 供应商模板“模型”按钮

- 按要求未修改按钮名称。
- 仍保持“模型”按钮，用于查看供应商模板关联的模型模板。

## 4. 供应商页面其他合理性修正

- 供应商模板列表状态列名称由“状态”改为“启用状态”。
- 供应商模板新建表单补充“供应商类型”字段，避免新增自定义模板时缺少 `provider_type` 导致后端保存失败。
- 复制模板时状态设为“未启用”，避免复制出的自定义模板被误认为已经启用。
- 复制模板增加名称与类型去重逻辑，重复复制时自动生成 `-2`、`-3` 等后缀。
- 供应商实例新增 / 编辑增加基础校验：实例名称、来源模板、协议必填。
- 供应商实例名称增加重复校验，避免同名实例造成路由和运维识别混乱。
- API 客户端增加 `success=false` 判断，避免后端返回失败但前端仍提示操作完成。

## 5. 模型页面合理性修正

- 平台模型列表补充“展示名称”字段，避免新增平台模型时缺少 `display_name` 导致保存失败。
- 平台模型已发布后，“发布”按钮显示为“已发布”并置灰，避免重复发布。
- 平台模型页底部说明改为业务语义：平台模型是业务系统调用的模型别名。
- 模型模板页底部说明去掉“兼容写入旧 model 表”等技术化表述，改为提醒业务系统应调用平台模型名。
- 供应商模板、平台模型、模型模板列表都支持状态筛选。

## 6. 修改文件

```text
apps/console/src/api/client.ts
apps/console/src/pages/DataPage.vue
apps/console/src/router.ts
services/control-plane/src/main/java/com/tokensea/asset/controller/ProviderTemplateController.java
services/control-plane/src/main/java/com/tokensea/asset/controller/ProviderInstanceController.java
services/control-plane/src/main/java/com/tokensea/asset/entity/ProviderTemplate.java
services/control-plane/src/main/resources/db/migration/V3__provider_template_enable_status.sql
```

## 7. 验证建议

```bash
# 前端
cd apps/console
npm install
npm run build

# 后端
cd services/control-plane
mvn test

# 启动后验证
# 1. 打开供应商管理 → 供应商模板。
# 2. 找到未启用模板，点击“启用”。
# 3. 页面刷新后按钮应显示“已启用”并置灰。
# 4. 进入供应商实例页，应只新增一条实例。
# 5. 再次刷新供应商模板页，不应出现重复实例。
# 6. 点击“模型”按钮，应仍然打开关联模型模板列表。
# 7. 打开模型目录 → 平台模型，已发布模型的“发布”按钮应显示“已发布”并置灰。
```
