-- 为系统基础设置提供可编辑的用途与影响说明，供控制台直接展示。

UPDATE platform_setting
SET description = CASE setting_key
  WHEN 'BASE_CURRENCY' THEN '平台汇总、预算与内部核算的基准币种。修改会改变后续汇总与预算口径，不会改写历史明细或成本快照。'
  WHEN 'BUDGET_CURRENCY' THEN '预算规则使用的默认币种。新建预算时作为默认值；修改不会自动换算或修改已有预算。'
  WHEN 'FX_AUTO_UPDATE_ENABLED' THEN '是否按计划从汇率源自动更新汇率。关闭后仅保留人工维护和已有汇率，可能导致后续外币成本无法自动折算。'
  WHEN 'FX_MANAGED_CURRENCIES' THEN '自动同步并折算至基准币种的外币列表（以逗号分隔）。新增币种前需确认汇率源支持；未维护的币种不会自动折算。'
  WHEN 'FX_RATE_SOURCE_URL' THEN '自动汇率同步使用的官方参考数据地址。改为不兼容或不可访问的地址会导致自动同步失败，但不影响已生效的历史汇率。'
  ELSE description
END
WHERE setting_key IN (
  'BASE_CURRENCY',
  'BUDGET_CURRENCY',
  'FX_AUTO_UPDATE_ENABLED',
  'FX_MANAGED_CURRENCIES',
  'FX_RATE_SOURCE_URL'
);
