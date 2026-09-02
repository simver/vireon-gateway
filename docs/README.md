# Vireon Gateway 治理文档

本目录保存 Gateway 自身的架构、路由、安全、配置、部署和发布决策。Platform、QBC 与 Framework
的内部实现事实不在本仓复制维护；Gateway 只记录其消费的公开契约。

## 当前文档

- [ADR-0001：Gateway 独立边界与路径契约](adr/ADR-0001-gateway-boundary.md)
- [项目 README](../README.md)
- [部署资产边界](../ops/README.md)

新增高影响决策时使用递增编号的 ADR。ADR 一经接受不得静默重写结论；需要改变时新增 ADR，说明
被替代决策、兼容方案、迁移步骤与回滚边界。
