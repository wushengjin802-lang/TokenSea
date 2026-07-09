# TokenSea 架构说明

TokenSea 采用控制面 + 数据面架构。

```text
Console -> Control Plane -> Gateway Runtime -> Runtime Engine -> Model Providers
```

控制面由 Java Spring Boot 实现，负责租户、模型、Key、价格、计费、审计。

数据面由 TokenSea Gateway Runtime 实现，负责 OpenAI-compatible 入口、Key 校验、预算、路由、用量写入，并转发到内部运行时底座。

内部运行时底座在合规目录中披露，不在产品接口和业务命名中暴露。
