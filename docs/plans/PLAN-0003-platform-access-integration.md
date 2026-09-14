# PLAN-0003：IAM/Audit真实接入的Gateway交付与TLS验证

- 日期：2026-09-14；所有者：Gateway；状态：计划基线已提交`8388301031fee8b14ef90aa0be15fc44aafa596b`，TLS测试与完整双构建通过；真实IAM/Audit本机终验收已通过，完整范围见Mix PLAN-0007。
- 用户要求执行联合Gateway计划，终验收本机、不接入真实短信；共同编排和矩阵由独立Mix仓库PLAN-0007、已接受本机范围的ADR-0010维护，不形成相邻源码或构建依赖。
- 调研起点：master `1c77c5086b61f3665295795b5350442e576743b3`；当前计划基线见页首。G0～G3开发与本机验证已授权，计划基线提交已按后续明确授权完成；源码提交仍另循授权。

## 范围与方案

保持 [ADR-0004](../adr/ADR-0004-static-webflux-runtime.md)的唯一路由源、完整路径、精确前缀、无网关身份、无代理信任及独立管理面。
由显式运行配置指向交付的IAM/Audit，默认仍空路由；System/QBC保持404，不提供假下游默认值。
本仓仅拥有网关交付、TLS/协议验证和发现后的边缘缺陷修复；Mix拥有真实组合，Platform拥有数据库和服务运行工具包。

推荐隔离TLS模式：浏览器HTTPS到Gateway，Gateway HTTPS到两服务，服务鉴权看到真实TLS，不新增Forwarded信任。
服务端使用Boot4.0.8支持的SSL配置，下游使用显式信任材料或独立JVM truststore，保持主机名验证。
不使用useInsecureTrustManager或浏览器TLS绕过作为成功证据；不修改用户全局CA，不在JAR/Git中放私钥或密码。
官方 [Gateway5.0.3 TLS](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/tls-and-ssl.html)
与 [头过滤](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/httpheadersfilters.html)已核验机制；TLS页旧属性示例需按当前配置元数据核对，不能照抄。
本轮已读取实际`spring-cloud-gateway-server-webflux-5.0.3.jar`配置元数据，确认`spring.cloud.gateway.server.webflux.httpclient.ssl.trusted-x509-certificates`为字符串列表，`use-insecure-trust-manager`默认false；实际配置绑定及TLS正反例由新增真实JAR测试通过。

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

已新增GatewayTlsJarIT，使用任务CA及真实JAR验证HTTPS入口与受信/未知CA/错SAN/过期下游；独立审查指出的keytool超时等待、HTTPS后端finally与关闭日志检查已修复。完整双构建各53项单元、17项JAR集成测试通过，失败/错误/跳过均0；17项包含原13项与新增4项TLS。交付脚本25项回归、本地文档44个链接通过；外链和标题锚点不在该检查范围。

证据根为`%TEMP%/vireon-artifacts/gateway-platform-plan-0007/`。`gateway-delivery-01`因Windows keytool本地编码输出被严格按UTF-8读取而失败，原回执保留；修复为按字节读取并检查秘密值后，`gateway-delivery-02/delivery.json`为PASS。两个独立输入副本完整verify并得到相同受测/交付JAR SHA256：`64d129b454316cd0e1bf9c8320f7e10552622295c0ddbc6528a610e8c4516d0b`。本次只改测试及说明，无生产源码、默认配置或版本变化；工作区测试变更未提交，交付以基线revision加dirty输入清单标识。

用户已于2026-09-14回复“授权”，同意将本计划及三个相关入口文档作一次本地基线提交，再继续实施与本机验收；不推送，不扩展为后续源码提交授权。

已核对规则、POM、配置、GatewayRouting、EdgeBoundary、运行说明和PLAN-0002。跨会话基线仅提交本文件及`README.md`、`docs/README.md`、`docs/plans/README.md`四份文档；后续测试与说明仍在工作区。未推送、发布或部署，不创建Goal。

G0～G3本仓工作完成：上述同一JAR已由Mix固定到`combination-02.json`，`h0-05/receipt.json`与`h1-05/receipt.json`完整verify通过，包含真实IAM/Audit、浏览器、TLS各跳拒绝、超时后已提交写回读与共享来源限流；清理通过。本仓没有因此修改默认路由或承担Platform构建。Mix最终自然过期及E01～E16总收束也已通过，证据由其PLAN-0007记录；真实短信成功和共享/生产部署不在本轮范围。
