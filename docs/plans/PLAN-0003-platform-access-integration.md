# PLAN-0003：IAM/Audit真实接入的Gateway交付与TLS验证

- 日期：2026-09-14；所有者：Gateway；状态：已获执行授权，准备跨会话基线，未实施/提交/验收。
- 用户要求执行联合Gateway计划，终验收本机、不接入真实短信；共同编排和矩阵由独立Mix仓库PLAN-0007、已接受本机范围的ADR-0010维护，不形成相邻源码或构建依赖。
- 基线：master `1c77c5086b61f3665295795b5350442e576743b3`；前轮计划及相关入口未提交，未发现生产源码差异。PLAN-0002已有独立交付证据，本次未重跑。G0～G3开发与本机验证已授权，跨会话计划基线提交仍待明确授权。

## 范围与方案

保持 [ADR-0004](../adr/ADR-0004-static-webflux-runtime.md)的唯一路由源、完整路径、精确前缀、无网关身份、无代理信任及独立管理面。
由显式运行配置指向交付的IAM/Audit，默认仍空路由；System/QBC保持404，不提供假下游默认值。
本仓仅拥有网关交付、TLS/协议验证和发现后的边缘缺陷修复；Mix拥有真实组合，Platform拥有数据库和服务运行工具包。

推荐隔离TLS模式：浏览器HTTPS到Gateway，Gateway HTTPS到两服务，服务鉴权看到真实TLS，不新增Forwarded信任。
服务端使用Boot4.0.8支持的SSL配置，下游使用显式信任材料或独立JVM truststore，保持主机名验证。
不使用useInsecureTrustManager或浏览器TLS绕过作为成功证据；不修改用户全局CA，不在JAR/Git中放私钥或密码。
官方 [Gateway5.0.3 TLS](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/tls-and-ssl.html)
与 [头过滤](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/httpheadersfilters.html)已核验机制；TLS页旧属性示例需按当前配置元数据核对，不能照抄。
本轮已读取实际`spring-cloud-gateway-server-webflux-5.0.3.jar`配置元数据，确认`spring.cloud.gateway.server.webflux.httpclient.ssl.trusted-x509-certificates`为字符串列表，`use-insecure-trust-manager`默认false；尚未运行绑定或证书正反例，G0不能据此全项关闭。

## 批次与验收

| 批次 | 工作 | 完成条件 |
| --- | --- | --- |
| G0 | 核对交付包/revision/SHA256及Boot/Gateway精确SSL键，设计TLS协议测试 | 不引用相邻target；真实IAM/Audit由Mix提供冻结URI/交付身份；不升级版本 |
| G1 | 本仓验证HTTPS监听、HTTPS下游、未知CA/错SAN/过期证书拒绝、错误脱敏 | 自身测试后端只证明协议；默认路由/代理头/管理隔离/超时/无写重试全部保持；必要缺陷先复现后修 |
| G2 | 本仓完整verify及ops/deliver.ps1独立交付 | 最终受测JAR、双构建结果和delivery摘要可恢复；原66项与脚本回归不得退化，实际数量按本次报告 |
| G3 | 向Mix交接并根据组合发现返修 | 配置合同、超时/错误/停止说明明确；修复后重新冻结并通知组合重验，不用旧包覆盖新源码 |

若涉及路由、代理信任或公开安全边界改变，先在本仓新增ADR并经独立审查，不借Mix计划绕过本仓规则。
真实浏览器、两条内部调用、本机目标环境及缺发送器503由Mix记录；真实短信成功和共享环境为本轮明确排除项，不算PASS或本轮阻塞。Gateway健康仅表示自身。
Windows控制台/服务管理器及Linux SIGTERM属于各目标运行环境验收，既有测试agent的System.exit结果不能替代。

## 本轮结果

用户已于2026-09-14回复“授权”，同意将本计划及三个相关入口文档作一次本地基线提交，再继续实施与本机验收；不推送，不扩展为后续源码提交授权。

已核对规则、POM、配置、GatewayRouting、EdgeBoundary、运行说明和PLAN-0002。仅新增本计划和索引/状态说明；无生产代码、构建、服务或部署变更。
本轮已回写开发授权、完整revision、本机范围及TLS元数据核验；未构建或运行服务。跨会话计划基线提交范围为本文件及`README.md`、`docs/README.md`、`docs/plans/README.md`，授权后仅提交这四份文档，再继续技术准入和实施。提交/推送/发布/部署/删除分别授权；不创建Goal。
