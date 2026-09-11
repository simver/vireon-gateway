# Vireon Gateway 治理文档

本目录保存 Gateway 自身的架构、路由、安全、配置、部署和发布决策。Platform、QBC 与 Framework
的内部实现事实不在本仓复制维护；Gateway 只记录其消费的公开契约。

## 当前文档

- [PLAN-0002：独立交付与运行验收（本地验收完成）](plans/PLAN-0002-independent-delivery.md)

- [运行、配置与独立验证](runtime.md)
- [ADR-0004：独立 WebFlux 静态网关运行契约](adr/ADR-0004-static-webflux-runtime.md)

- [Gateway 开发流程](development-workflow.md)
- [开发计划索引](plans/README.md)
- [PLAN-0001：基础 Gateway 自身开发与独立验收](plans/PLAN-0001-basic-gateway.md)
- [ADR-0003：先完成 Gateway 自身开发与独立验收](adr/ADR-0003-gateway-independent-acceptance.md)

- [ADR-0002：开发联调基础版与上线增强分阶段交付](adr/ADR-0002-staged-gateway-delivery.md)

- [ADR-0001：Gateway 独立边界与路径契约](adr/ADR-0001-gateway-boundary.md)
- [项目 README](../README.md)
- [部署资产边界](../ops/README.md)

新增高影响决策时使用递增编号的 ADR。ADR 一经接受不得静默重写结论；需要改变时新增 ADR，说明
被替代决策、兼容方案、迁移步骤与回滚边界。
