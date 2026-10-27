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

## 2026-10-20 RT-GW 复测批次

授权来源：主调度 `01a1584f-393e-71e0-98ac-fc1804d31748` 的 RT-GW 派发及其
`docs/retest-plan-2026-10-20.md`。允许复测、范围内缺陷修复及本包临时资源收尾；不创建Goal，
不提交、合并、推送、部署、正式发布或修改生效规则。所属计划承接 PLAN-0001/0002 的自身门禁。

- 启动基线：master / `58a4342ea5a4fa5f88cd806ef00c08122561f656`，启动时 dirty 为空。
- 模型：创建型号默认、强度 high 来自派发；实际模型/强度无可核实元数据，登记未知。
- 后端输入：RT-MIX 本轮 `3fc0df91e7e0693a713334a988d6bb2bc1498384` 的正式 fixture 交接；
  manifest SHA256 `09ca9b609631fc34fa3b1beb2e23d6e99662ce4a13c4d42adf004ac919ac4a6e`，
  IAM JAR `ff65e213057a83c8ea4e5665d03afe2ef3ce6a1ddddca6a0d7f7e2092ecb2fa8`，
  Audit JAR `e6c1dca2d017a254e8c0c2bd2f39cabdcf9b1c10849d6df7644b9138d9186f1b`。
  启动前重算并核验 toolkit 字节，不借相邻 Java 源码、target classpath 或共享 Maven 缓存。
- Gateway POM 无 Framework 依赖且禁止内部制品；Framework 空缓存解析对本仓为 NA。
  IAM/Audit 的正式坐标构建来源按 fixture manifest 核验，不冒充本仓构建结果。
- 原始证据根：本仓 `target/rt-gw-20261020-01/`。双构建使用系统临时目录中全新明确目录，
  不清理目录树；独占回环端口、owner/run、日志及精确 stop 入口在运行回执登记。
- 单代理执行；最终安全/实现审查使用未参与实现的独立审查者。若需安全契约新取舍，先 ADR。

| 验收项 | 本轮方式 | 初始状态 |
| --- | --- | --- |
| 输入/源码/制品绑定 | HEAD、dirty、文件清单及交接 SHA256 重算 | 待执行 |
| 完整门禁 | 自身 Wrapper + verify，格式/架构/路由/文档，空本地缓存 | 待执行 |
| 双构建与入口 | deliver 的两份冻结副本，各完整 verify，JAR 摘要比较；脚本回归 | 待执行 |
| 协议和故障 | 精确前缀、raw path/query、相邻前缀、伪造代理头、管理面、不可达/超时 | 待执行 |
| 最终 JAR 生命周期/TLS | 现有端口冲突、在途关闭、释放端口、TLS 信任/SAN/过期用例 | 待执行 |
| 真实 IAM/Audit | 固定 fixture、新独占 PG/进程/端口，Gateway 实际 JAR 经路由联验 | 待执行 |
| 独审/收尾 | 只读独审、差异/秘密检查、精确新资源收尾、历史候选只盘点 | 待执行 |
| Nacos/System/QBC/生产 | 未实现能力或未授权环境 | NA |

测试替身、真实后端、历史证据分别登记；不得用健康或 HTTP 替代真实业务或浏览器接受。
失败和中断证据保留；先审活进程与句柄，仅补缺口，不盲目重放写链。

### RT-GW 当轮结果与交接

2026-10-20 已完成本包已实现能力的本机复测；上表为启动时矩阵，实际结果如下。
生产 Java、测试、POM、默认配置和交付脚本未改；仅本计划补证、运行说明首段纠正历史/本轮范围。
临时联验与资源观察脚本、回执及失败均保存在本包 `target/rt-gw-20261020-01/`。

| 项目 | 本轮结果与证据 |
| --- | --- |
| 输入绑定 | HEAD 保持 `58a4342ea5a4fa5f88cd806ef00c08122561f656`；交接 manifest、IAM/Audit 及 9 项 toolkit 文件均重算相符；H0/H1 运行输入未漂移 |
| 空缓存完整门禁 | 新冻结副本、自身 Wrapper、全新空消费缓存、只读第三方 file 仓库；53 单元/协议 + 17 JAR（含 4 TLS），零失败/错误/跳过，格式/架构门禁及文档通过；`direct-wrapper-receipt.json` |
| 双构建 | `ops/deliver.ps1` 两份先冻结再各完整 verify，均 70 项且零失败/错误/跳过；使用前述本包已填充缓存离线构建，不能称这两份缓存初始为空；`delivery.json` 为 PASS |
| 脚本/文档 | `ops/test-delivery.ps1` 25 项合成回归通过；44 个本地文档链接通过，外链与锚点未检查 |
| H0 真实后端 | 初始改密/登录、Cookie 身份与 Audit 内省、角色写入/回读/审计事实、Origin/CSRF 拒绝且无写入、伪造身份/相邻前缀/内部路径拒绝、真实服务故障恢复、刷新退出通过；实际 Chrome 155.0.8059.85 |
| H1 真实后端 | 同一固定三 JAR 的 TLS 认证与读写链通过；Gateway→IAM/Audit 分别拒绝未知 CA、错误 SAN、过期证书并恢复，共 6 项；内部凭据边界、服务故障恢复和刷新退出通过；未跑 H1 浏览器 |
| 实际资源归属 | 首次 H1 快照仅支持七端口回环/专属容器映射，跨 TLS 重启的 PID 采样不能证明完整所有权；修正版 `bindings-only-02` 新专项只做资源观察，前后 PID 一致、六 Java 监听均属直属进程且业务/管理成对，七端口回环，PASS |
| 独立审查 | 未参与实现的 `gateway_review` 审查生产边界、门禁、联验脚本及最终证据；联验输入全集绑定、浏览器有界清理/异步拒绝、无写入比较断言、观察脚本比较优先级等问题已修并复核；无未闭合 P1/P2 |
| 收尾 | 四个本包新容器逐一由 fixture 核精确 ID/标签/专属匿名卷后收尾；另行只读核四 ID 缺失、已观测卷缺失、无 RT-GW 进程/容器残留；端口释放通过，运行秘密扫描未检出；`resource-closure.json` |

冻结交付目录：`C:/Users/simver/AppData/Local/Temp/vireon-gateway-rtgw-20261020-01/`。
交付输入摘要 `9F364AD570B25C57B39CD2C846C776AC29947FBD71AA6D2DD0CE49781C8E7BD8`；
受测和最终 JAR SHA256 `64D129B454316CD0E1BF9C8320F7E10552622295C0DDBC6528A610E8C4516D0B`。
JAR 与历史摘要相同是本轮重新构建/测试所得；真实 IAM/Audit 使用本轮新 JAR，不混用历史 bundle 字节。
702 项实际解析的第三方 JAR/POM 与只读公共仓库逐项核对相符；不修改共享缓存，也不宣称公网冷下载成功。

失败均保留：Central 超时、用户缓存诊断缺依赖、旧 target JAR 不可写、file 源缺 dependency 插件、
H0 活态快照未捕获、H1 首次采样竞态及一致观察脚本的 PowerShell 比较错误。
`bindings-only-01` 原 FAIL 和收尾证据保留；修复后新专项闭环，未重放已过业务写链。详见 `failure-ledger.json`。

运行目录、诊断缓存、TLS 材料、浏览器 profile 及必要证据未作目录清理；非必要目录仅列手工清理候选，
最终双构建候选和原失败证据保留。历史资源只有盘点，不依据名称/状态删除；其它包、旧 UNKNOWN/UAT、
共享基础设施和 CallHub 未被本包操作。不能据此声明全部临时文件已清完。

本轮未验证 H1 浏览器、真实短信、30 分钟自然过期、Windows 控制台/服务管理器及 Linux 停止信号、
Nacos、生产镜像或共享/生产部署。JAR 生命周期证据限现有测试覆盖的进程冲突、JVM 关闭流程和端口释放。
最终结果补记后只重查文档和差异；冻结候选不改，后补计划与构建输入的文档字节差异明确保留。
未创建 Goal、提交、合并、推送、部署或正式发布；本包交回主调度，返修仍留本聊。
